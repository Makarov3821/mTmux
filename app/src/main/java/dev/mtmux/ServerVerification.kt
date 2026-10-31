package dev.mtmux

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mtmux.core.*
import kotlinx.coroutines.*

internal data class ServerDraft(val profile: ServerProfile, val credentials: SavedCredentials)

/** A draft never reaches persistence before verification or an explicit Save anyway. */
@Composable internal fun ServerVerification(
    draft: ServerDraft, store: ServerProfiles, pool: WarmConnectionPool<SshClient>,
    onDismiss: () -> Unit, onSaved: (ServerProfile, ServerSaveOutcome) -> Unit
) {
    ProtectSensitiveContent()
    val context = LocalContext.current
    val pins = remember { context.getSharedPreferences("host-pins", 0) }
    // Keep explicitly confirmed draft keys across a user-requested retry, never across closing the editor.
    val staged = remember { StagedHostKeys(object : PinStore { override fun get(endpoint: String) = pins.getString(endpoint, null) }) }
    val scope = rememberCoroutineScope()
    var retry by remember { mutableIntStateOf(0) }
    val operation = remember(retry) { SshOperation() }
    var prompt by remember(retry) { mutableStateOf<HostApproval?>(null) }
    var progress by remember(retry) { mutableStateOf(uiText(R.string.editor_checking)) }
    var failure by remember(retry) { mutableStateOf<UiText?>(null) }
    var committing by remember(retry) { mutableStateOf(false) }
    val hops = draft.profile.login(draft.credentials).jumps.size
    var stage by remember(retry) { mutableStateOf<ConnectProgress?>(null) }
    var trusted by remember(retry) { mutableIntStateOf(0) }
    var failedHop by remember(retry) { mutableStateOf<Int?>(null) }
    var forcedSave by remember { mutableStateOf<Job?>(null) }
    fun dismiss() {
        if (committing) return
        operation.cancel(); forcedSave?.cancel(); onDismiss()
    }
    DisposableEffect(operation) { onDispose { operation.cancel() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, operation, committing) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !committing) dismiss()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(operation) {
        val attempt = DebugLog.nextAttempt()
        val connection = SshClient(staged) { challenge ->
            val request = HostApproval(challenge)
            if (!operation.offer(request)) false else {
                scope.launch { if (!operation.cancelled) prompt = request }
                val approved = request.answer.get()
                approved && !operation.cancelled && staged.approve(challenge)
            }
        }
        operation.client.set(connection)
        connection.observe { DebugLog.connectionTrace(DebugLog.Owner.VERIFY, draft.profile.id.hashCode(), it, attempt) }
        var retained = false
        try {
            DebugLog.event(DebugLog.Event.SERVER_VERIFY, draft.profile.id.hashCode())
            withContext(Dispatchers.IO) {
                connection.connect(draft.profile.login(draft.credentials)) { next ->
                    scope.launch { if (!operation.cancelled) { progress = progressText(next); stage = next } }
                }
            }
            ensureActive()
            operation.ensureNotCancelled()
            committing = true
            progress = uiText(R.string.editor_saving)
            // Once the local commit starts, complete it and navigation as one short operation.
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    store.save(draft.profile, draft.credentials, staged.accepted())
                    if (operation.client.compareAndSet(connection, null)) {
                        pool.put(store.connectionRevision(draft.profile), connection)
                        retained = true
                    }
                }
                DebugLog.event(DebugLog.Event.SERVER_VERIFY_DONE, draft.profile.id.hashCode())
                onSaved(draft.profile, ServerSaveOutcome.VERIFIED)
            }
        } catch (error: Exception) {
            if (error is CancellationException || operation.cancelled) throw CancellationException("verification cancelled", error)
            DebugLog.event(DebugLog.Event.SERVER_VERIFY_FAILED, draft.profile.id.hashCode(), error = error)
            failedHop = (error as? ConnectionFailure)?.hop ?: stage?.hop
            failure = when (error) {
                is HostKeyRejected -> uiText(if (error.challenge.changed) R.string.home_host_key_changed else R.string.editor_trust_declined)
                else -> errorText(error)
            }
            committing = false
        } finally {
            prompt?.answer?.complete(false)
            operation.client.compareAndSet(connection, null)
            if (!retained) withContext(NonCancellable + Dispatchers.IO) { connection.close() }
        }
    }
    val approval = prompt
    // Hop order: jumps 1..n, then target (0). Index in that order of the current/failed hop.
    fun order(hop: Int) = if (hop == 0) hops else hop - 1
    val current = order((if (failure != null) failedHop else stage?.hop) ?: (if (hops > 0) 1 else 0))
    val names = (1..hops).map { hopText(it) } + hopText(0)
    val route = listOf(stringResource(R.string.route_phone) to RouteState.NORMAL) + names.mapIndexed { i, name -> name.string() to when {
        committing && failure == null -> RouteState.DONE
        i < current -> RouteState.DONE
        i == current -> if (failure != null) RouteState.FAILED else RouteState.CURRENT
        else -> RouteState.NORMAL
    } }
    if (approval != null) HostKeyDialog(approval.challenge,
        onDismiss = { approval.answer.complete(false); prompt = null },
        onTrust = { approval.answer.complete(true); prompt = null; trusted++ },
        trustLabel = stringResource(R.string.editor_trust_continue),
        subtitle = progress.string() + "\n" + stringResource(R.string.trust_verify_hint),
        headerEnd = { Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(stringResource(R.string.editor_hop_of, current + 1, hops + 1), Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        route = route.takeIf { hops > 0 })
    VerificationPanel(names.map { it.string() }, current, failure?.string(), committing, progress.string(), ::dismiss, { retry++ }, {
        committing = true
        forcedSave = scope.launch {
            try {
                withContext(NonCancellable) {
                    // Intentionally no provisional pins and no authenticated chain.
                    withContext(Dispatchers.IO) { store.save(draft.profile, draft.credentials) }
                    DebugLog.event(DebugLog.Event.SERVER_SAVED_UNVERIFIED, draft.profile.id.hashCode())
                    onSaved(draft.profile, ServerSaveOutcome.UNVERIFIED)
                }
            } catch (error: Exception) { failure = errorText(error); committing = false }
        }
    })
}

/** Staged verification sheet: route chips, one step per hop, then save. */
@Composable internal fun VerificationPanel(names: List<String>, current: Int, failure: String?, committing: Boolean, progress: String,
    onDismiss: () -> Unit, onRetry: () -> Unit, onSaveAnyway: () -> Unit) {
    val route = listOf(stringResource(R.string.route_phone) to RouteState.NORMAL) + names.mapIndexed { i, name -> name to when {
        committing && failure == null -> RouteState.DONE
        i < current -> RouteState.DONE
        i == current -> if (failure != null) RouteState.FAILED else RouteState.CURRENT
        else -> RouteState.NORMAL
    } }
    AppSheet(onDismiss = onDismiss, secure = true, dismissible = !committing) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (failure != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToneBadge(UiSymbol.ALERT_CIRCLE, Tone.DANGER)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.editor_check_failed), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(failure, color = StatusColors.attention(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("editor-check-error"))
                }
            } else {
                Text(stringResource(R.string.editor_verifying_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(stringResource(R.string.editor_verifying_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RouteChips(route, Modifier.padding(vertical = 10.dp))
            names.forEachIndexed { i, name ->
                val state = when {
                    committing && failure == null -> StepState.DONE
                    i < current -> StepState.DONE
                    i == current -> if (failure != null) StepState.FAILED else StepState.ACTIVE
                    else -> StepState.PENDING
                }
                StepRow(uiText(R.string.step_hop_auth, UiText.Raw(name)).string(), state, if (state == StepState.ACTIVE) stringResource(R.string.step_in_progress) else null)
            }
            StepRow(stringResource(R.string.editor_step_save), if (committing && failure == null) StepState.ACTIVE else StepState.PENDING)
            if (failure == null) Text(progress, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            if (failure != null) {
                Text(stringResource(R.string.editor_save_anyway_hint), Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalActionButton(DialogAction(stringResource(R.string.editor_back_edit), onDismiss, enabled = !committing), Modifier.weight(1f))
                    PrimaryActionButton(DialogAction(stringResource(R.string.editor_retry_verification), onRetry, "retry-server-check", enabled = !committing), Modifier.weight(1f))
                }
                QuietActionButton(DialogAction(stringResource(R.string.editor_save_anyway), onSaveAnyway, "save-server-anyway", enabled = !committing), Modifier.fillMaxWidth())
            } else TonalActionButton(DialogAction(stringResource(R.string.common_cancel), onDismiss, enabled = !committing), Modifier.fillMaxWidth())
        }
    }
}
