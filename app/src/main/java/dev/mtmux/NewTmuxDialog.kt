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

@Composable internal fun NewTmuxDialog(
    profile: ServerProfile, pool: WarmConnectionPool<SshClient>,
    onDismiss: (Boolean) -> Unit, onTrustRequired: (HostKeyChallenge) -> Unit,
    onCreated: (RecentTask, SshClient) -> Unit
) {
    ProtectSensitiveContent()
    val context = LocalContext.current
    val store = remember { ServerProfiles(context) }
    val pins = remember { context.getSharedPreferences("host-pins", 0) }
    val doctorCache = remember { TmuxDoctorStore(context) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<UiText?>(null) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<SshOperation?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    val mutationStarted = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val activeOperation by rememberUpdatedState(operation)
    fun dismiss() { operation?.cancel(); work?.cancel(); onDismiss(mutationStarted.get()) }
    DisposableEffect(Unit) { onDispose { activeOperation?.cancel() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) dismiss() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AlertDialog(properties = protectedDialogProperties, onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.tmux_create_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.tmux_name_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it; error = null }, enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.tmux_name_label)) }, modifier = Modifier.fillMaxWidth().testTag("new-tmux-name"))
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.tmux_creating)) }
            error?.let { Text(it.string(), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("new-tmux-error")) }
        } },
        confirmButton = { TextButton(enabled = !busy, modifier = Modifier.testTag("create-tmux"), onClick = {
            val requested = name.trim()
            if (!TmuxSessionCreation.validName(requested)) { error = uiText(R.string.tmux_name_invalid); return@TextButton }
            error = null; busy = true
            val owner = SshOperation(); operation = owner
            work = scope.launch {
                var connection: SshClient? = null
                var handedOff = false
                var revision: String? = null
                var authenticated = false
                try {
                    val task = withContext(Dispatchers.IO) {
                        if (store.all().firstOrNull { it.id == profile.id } != profile) throw AppError(R.string.err_profile_changed)
                        revision = store.connectionRevision(profile)
                        val pooled = pool.take(revision!!)
                        val client = pooled ?: SshClient(object : PinStore { override fun get(endpoint: String) = pins.getString(endpoint, null) })
                        connection = client; owner.client.set(client)
                        if (owner.cancelled) { client.cancel(); throw CancellationException("creation cancelled") }
                        val attempt = DebugLog.nextAttempt()
                        client.observe { DebugLog.connectionTrace(DebugLog.Owner.CREATE, profile.id.hashCode(), it, attempt) }
                        if (pooled != null) client.reportReuse() else client.connect(store.login(profile))
                        authenticated = true
                        DebugLog.event(DebugLog.Event.TMUX_CREATE, profile.id.hashCode())
                        owner.ensureNotCancelled()
                        mutationStarted.set(true)
                        val created = client.createTmuxSession(requested, profile.path)
                        if (store.all().firstOrNull { it.id == profile.id } != profile || store.connectionRevision(profile) != revision) throw AppError(R.string.err_profile_changed)
                        doctorCache.save(profile.id,revision!!,TmuxDoctorReport(mapOf("path.status" to "ok","server.status" to "ready")),false)
                        // Only the first session after a Doctor initialization needs a full post-create check.
                        if(doctorCache.receipt(profile.id,revision!!)!=null && doctorCache.report(profile.id,revision!!)?.report?.get("server.status")=="none") {
                            runCatching {
                                val report=TmuxEnvironmentProbe.parse(client.exec(TmuxEnvironmentProbe.command(profile.path),phase=ConnectionPhase.TMUX_DOCTOR,timeoutMs=3000))
                                if(store.connectionRevision(profile)==revision) doctorCache.save(profile.id,revision!!,report,true)
                            }
                        }
                        val hostKey = pins.getString(profile.trustEndpoint(), null) ?: throw AppError(R.string.home_verify_fingerprint)
                        RecentTask(profile.id, profile.host, profile.port, profile.user, profile.path, hostKey,
                            created.binding.identity, requested, "${created.windowIndex} · $requested", route = profile.routeContext())
                    }
                    ensureActive()
                    val client = requireNotNull(connection)
                    if (owner.cancelled || !owner.client.compareAndSet(client, null)) throw CancellationException("creation cancelled")
                    handedOff = true
                    DebugLog.event(DebugLog.Event.TMUX_CREATE_DONE, profile.id.hashCode())
                    onCreated(task, client)
                } catch (failure: Exception) {
                    if (failure is CancellationException || owner.cancelled) throw CancellationException("creation cancelled", failure)
                    DebugLog.event(DebugLog.Event.TMUX_CREATE_FAILED, profile.id.hashCode(), error = failure)
                    if(failure is CodedError && failure.code==ErrorCode.TMUX_UNAVAILABLE && revision!=null && store.connectionRevision(profile)==revision) {
                        doctorCache.save(profile.id,revision!!,TmuxDoctorReport(mapOf("path.status" to "failed")),false)
                    }
                    if (failure is HostKeyRejected) {
                        error = uiText(if (failure.challenge.changed) R.string.home_host_key_changed else R.string.home_verify_fingerprint)
                        onTrustRequired(failure.challenge)
                    } else error = errorText(failure)
                } finally {
                    val client = connection
                    if (!handedOff && client != null) withContext(NonCancellable + Dispatchers.IO) {
                        owner.client.compareAndSet(client, null)
                        if (authenticated && !owner.cancelled && client.isReusable() &&
                            store.all().firstOrNull { it.id == profile.id } == profile && store.connectionRevision(profile) == revision)
                            pool.put(revision!!, client)
                        else client.close()
                    }
                    busy = false
                }
            }
        }) { Text(stringResource(R.string.tmux_create)) } },
        dismissButton = { TextButton(onClick = ::dismiss) { Text(stringResource(R.string.common_cancel)) } })
}
