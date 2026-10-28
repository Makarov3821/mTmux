package dev.mtmux

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                connection.connect(draft.profile.login(draft.credentials)) { stage ->
                    scope.launch { if (!operation.cancelled) progress = progressText(stage) }
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
    AlertDialog(properties = protectedDialogProperties, onDismissRequest = ::dismiss,
        title = { Text(stringResource(if (approval != null) R.string.trust_verify_title else if (failure != null) R.string.editor_check_failed else R.string.editor_verifying_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (approval != null) {
                Text(progress.string(), style = MaterialTheme.typography.bodySmall)
                Text(approval.challenge.endpoint.substringBefore("|via:"))
                Text(approval.challenge.fingerprint, modifier = Modifier.testTag("editor-fingerprint"))
                Text(stringResource(R.string.trust_verify_hint))
            } else if (failure != null) {
                Text(failure!!.string(), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("editor-check-error"))
                Text(stringResource(R.string.editor_save_anyway_hint), style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress.string())
            }
        } },
        confirmButton = {
            when {
                approval != null -> TextButton(onClick = {
                    approval.answer.complete(true); prompt = null
                }, modifier = Modifier.testTag("editor-trust-key")) { Text(stringResource(R.string.host_key_trust)) }
                failure != null -> TextButton(enabled = !committing, onClick = {
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
                }, modifier = Modifier.testTag("save-server-anyway")) { Text(stringResource(R.string.editor_save_anyway)) }
            }
        },
        dismissButton = { Row {
            if (failure != null) TextButton(enabled = !committing, onClick = { retry++ }, modifier = Modifier.testTag("retry-server-check")) { Text(stringResource(R.string.editor_retry_verification)) }
            TextButton(enabled = !committing, onClick = {
                if (approval != null) { approval.answer.complete(false); prompt = null } else dismiss()
            }) { Text(stringResource(R.string.common_cancel)) }
        } })
}
