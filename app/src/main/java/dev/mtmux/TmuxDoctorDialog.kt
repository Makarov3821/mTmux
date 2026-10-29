package dev.mtmux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mtmux.core.*
import kotlinx.coroutines.*

private enum class DoctorAction { CHECK, INITIALIZE, UNDO }

@Composable internal fun TmuxDoctorInvitation(onRun: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(onDismissRequest=onSkip, title={Text(stringResource(R.string.doctor_invite_title))},
        text={Text(stringResource(R.string.doctor_invite_body))},
        confirmButton={TextButton(onClick=onRun,modifier=Modifier.testTag("run-tmux-doctor")){Text(stringResource(R.string.doctor_run))}},
        dismissButton={TextButton(onClick=onSkip,modifier=Modifier.testTag("skip-tmux-doctor")){Text(stringResource(R.string.doctor_skip))}})
}

/** Owns one operation at a time; no SSH chain is kept while the user reads a report or preview. */
@Composable internal fun TmuxDoctorDialog(
    profile: ServerProfile, pool: WarmConnectionPool<SshClient>, forCreate: Boolean = false,
    remoteTouch: Boolean = true, onEnableTouch: (() -> Unit)? = null,
    beforeConnect: suspend () -> Unit = {}, onChanged: () -> Unit,
    onDismiss: () -> Unit, onCreateReady: () -> Unit = {}
) {
    ProtectSensitiveContent()
    val context=LocalContext.current
    val store=remember {ServerProfiles(context)}
    val cache=remember {TmuxDoctorStore(context)}
    val pins=remember {context.getSharedPreferences("host-pins",0)}
    val scope=rememberCoroutineScope()
    val initialRevision=remember(profile) {store.connectionRevision(profile)}
    var stored by remember(profile) {mutableStateOf(cache.report(profile.id,initialRevision))}
    var receipt by remember(profile) {mutableStateOf(cache.receipt(profile.id,initialRevision))}
    var historical by remember {mutableStateOf(stored!=null)}
    var detailed by remember {mutableStateOf(!forCreate)}
    var attempt by remember {mutableIntStateOf(0)}
    var action by remember {mutableStateOf(DoctorAction.CHECK)}
    var busy by remember {mutableStateOf(true)}
    var progress by remember {mutableStateOf(uiText(R.string.doctor_waiting))}
    var error by remember {mutableStateOf<UiText?>(null)}
    var notice by remember {mutableStateOf<UiText?>(null)}
    var approval by remember {mutableStateOf<HostApproval?>(null)}
    var preview by remember {mutableStateOf(false)}
    var confirmUndo by remember {mutableStateOf(false)}
    var history by remember {mutableStateOf("10000")}
    var firstSetup by remember {mutableStateOf(false)}
    val operation=remember(attempt) {SshOperation()}
    val latestBeforeConnect by rememberUpdatedState(beforeConnect)
    val latestChanged by rememberUpdatedState(onChanged)
    val latestCreate by rememberUpdatedState(onCreateReady)
    fun dismiss() {operation.cancel();onDismiss()}
    DisposableEffect(operation) {onDispose {operation.cancel()}}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,operation) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) dismiss()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(attempt) {
        busy=true;error=null;historical=stored!=null
        val actionNow=action
        val detailNow=detailed || actionNow!=DoctorAction.CHECK
        val undoReceipt=receipt
        val previewHome=stored?.report?.get("home")
        val selectedHistory=history.toIntOrNull() ?: 10000
        var connection:SshClient?=null
        var revision=store.connectionRevision(profile)
        var authenticated=false
        var createReady=false
        fun ensureCurrent() {
            operation.ensureNotCancelled()
            if(store.all().firstOrNull {it.id==profile.id}!=profile || store.connectionRevision(profile)!=revision) throw AppError(R.string.err_profile_changed)
        }
        val staged=StagedHostKeys(object:PinStore {override fun get(endpoint:String)=pins.getString(endpoint,null)})
        try {
            DebugLog.event(if(actionNow==DoctorAction.CHECK) DebugLog.Event.DOCTOR else DebugLog.Event.DOCTOR_CONFIG,profile.id.hashCode(),actionNow.ordinal)
            progress=uiText(R.string.doctor_waiting)
            latestBeforeConnect()
            progress=uiText(R.string.doctor_connecting)
            val result=withContext(Dispatchers.IO) {
                ensureCurrent()
                val pooled=pool.take(revision)
                val client=pooled ?: SshClient(staged) {challenge->
                    val prompt=HostApproval(challenge)
                    if(!operation.offer(prompt)) false else {
                        scope.launch {if(!operation.cancelled) approval=prompt}
                        prompt.answer.get() && !operation.cancelled && staged.approve(challenge)
                    }
                }
                connection=client;operation.client.set(client)
                operation.ensureNotCancelled()
                val traceAttempt=DebugLog.nextAttempt()
                client.observe {DebugLog.connectionTrace(DebugLog.Owner.DOCTOR,profile.id.hashCode(),it,traceAttempt)}
                if(pooled!=null) client.reportReuse() else client.connect(store.login(profile))
                authenticated=true
                ensureCurrent()
                if(staged.accepted().isNotEmpty()) {
                    store.save(profile,verifiedPins=staged.accepted())
                    revision=store.connectionRevision(profile)
                }
                scope.launch {if(!operation.cancelled) progress=uiText(R.string.doctor_reading)}
                var report=TmuxEnvironmentProbe.parse(client.exec(TmuxEnvironmentProbe.command(profile.path,detailNow),phase=ConnectionPhase.TMUX_DOCTOR,timeoutMs=5000))
                ensureCurrent()
                when(actionNow) {
                    DoctorAction.INITIALIZE -> {
                        if(!report.canInitialize || report["home"]!=previewHome) throw AppError(R.string.doctor_config_changed)
                        scope.launch {if(!operation.cancelled) progress=uiText(R.string.doctor_creating_config)}
                        val created=client.exec(TmuxConfigBootstrap.createCommand(profile.path,report["home"],selectedHistory,report["version.client"]),phase=ConnectionPhase.TMUX_CONFIG,timeoutMs=5000)
                        val value=TmuxConfigBootstrap.parseCreated(created,report["home"],selectedHistory)
                        ensureCurrent()
                        // Persist receipt before the post-write probe; a later read failure still permits review/undo.
                        cache.saveReceipt(profile.id,revision,value)
                        report=TmuxEnvironmentProbe.parse(client.exec(TmuxEnvironmentProbe.command(profile.path),phase=ConnectionPhase.TMUX_DOCTOR,timeoutMs=5000))
                    }
                    DoctorAction.UNDO -> {
                        val value=undoReceipt ?: throw AppError(R.string.doctor_config_changed)
                        if(value.home!=report["home"]) throw AppError(R.string.doctor_config_changed)
                        val undone=client.exec(TmuxConfigBootstrap.undoCommand(value),phase=ConnectionPhase.TMUX_CONFIG,timeoutMs=5000)
                        if(undone.status!=0) throw AppError(if(undone.status==74 || undone.status==75) R.string.doctor_undo_changed else R.string.doctor_config_uncertain)
                        val backup=TmuxConfigBootstrap.parseUndone(undone,value)
                        ensureCurrent();cache.saveReceipt(profile.id,revision,null)
                        scope.launch {if(!operation.cancelled) notice=uiText(R.string.doctor_undone,backup.backup)}
                        report=TmuxEnvironmentProbe.parse(client.exec(TmuxEnvironmentProbe.command(profile.path),phase=ConnectionPhase.TMUX_DOCTOR,timeoutMs=5000))
                    }
                    else -> Unit
                }
                ensureCurrent()
                cache.save(profile.id,revision,report,detailNow)
                StoredDoctorReport(System.currentTimeMillis(),report)
            }
            ensureActive();ensureCurrent()
            stored=result;historical=false;receipt=cache.receipt(profile.id,revision)
            if(actionNow==DoctorAction.INITIALIZE) notice=uiText(R.string.doctor_initialized)
            latestChanged()
            createReady=forCreate && !detailNow && result.report.canCreate && !result.report.belowBaseline &&
                TmuxRelease.parse(result.report["version.client"])!=null &&
                (result.report["version.server"].isEmpty() || TmuxRelease.parse(result.report["version.server"])!=null)
            DebugLog.event(if(actionNow==DoctorAction.CHECK) DebugLog.Event.DOCTOR_DONE else DebugLog.Event.DOCTOR_CONFIG_DONE,profile.id.hashCode(),actionNow.ordinal)
        } catch(failure:Exception) {
            if(failure is CancellationException || operation.cancelled) throw CancellationException("doctor cancelled",failure)
            DebugLog.event(if(actionNow==DoctorAction.CHECK) DebugLog.Event.DOCTOR_FAILED else DebugLog.Event.DOCTOR_CONFIG_FAILED,profile.id.hashCode(),actionNow.ordinal,failure)
            error=when {
                failure is HostKeyRejected -> uiText(if(failure.challenge.changed) R.string.home_host_key_changed else R.string.home_needs_fingerprint)
                failure is AppError -> errorText(failure)
                actionNow!=DoctorAction.CHECK -> uiText(R.string.doctor_config_uncertain)
                else -> errorText(failure)
            }
            receipt=cache.receipt(profile.id,revision)
            latestChanged()
        } finally {
            approval?.answer?.complete(false);approval=null
            val client=connection
            if(client!=null) withContext(NonCancellable+Dispatchers.IO) {
                operation.client.compareAndSet(client,null)
                if(authenticated && !operation.cancelled && client.isReusable() &&
                    store.all().firstOrNull {it.id==profile.id}==profile && store.connectionRevision(profile)==revision) pool.put(revision,client)
                else client.close()
            }
            busy=false
        }
        if(createReady && isActive && !operation.cancelled) latestCreate()
    }
    val report=stored?.report
    val prompt=approval
    AlertDialog(properties=protectedDialogProperties,onDismissRequest=::dismiss,
        title={Text(stringResource(R.string.doctor_title))},
        text={Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(profile.name,style=MaterialTheme.typography.titleSmall)
            if(prompt!=null) {
                Text(stringResource(R.string.trust_verify_title));Text(prompt.challenge.endpoint.substringBefore("|via:"))
                Text(prompt.challenge.fingerprint,Modifier.testTag("doctor-fingerprint"));Text(stringResource(R.string.trust_verify_hint))
            } else {
                if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(progress.string(),Modifier.testTag("doctor-progress"))}
                if(stored!=null) Text(stringResource(if(historical) R.string.doctor_historical else R.string.doctor_checked,
                    java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.ROOT).format(java.util.Date(stored!!.checkedAt))),style=MaterialTheme.typography.bodySmall)
                error?.let {Text(it.string(),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("doctor-error"))}
                notice?.let {Text(it.string(),color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("doctor-notice"))}
                if(report!=null) DoctorFindings(report,profile.enableTmuxMouse,if(detailed) DoctorCheck.entries.toSet() else setOf(DoctorCheck.SSH,DoctorCheck.EXECUTABLE,DoctorCheck.VERSION,DoctorCheck.SESSIONS),remoteTouch)
                else DoctorCheck.entries.forEach {Text("— "+stringResource(doctorTitle(it)),style=MaterialTheme.typography.bodyMedium)}
                if(detailed && !remoteTouch && onEnableTouch!=null) TextButton(onClick=onEnableTouch,modifier=Modifier.testTag("doctor-enable-touch")){Text(stringResource(R.string.doctor_enable_touch))}
                if(!busy && report?.availability==TmuxAvailability.UNAVAILABLE) {
                    Text(stringResource(R.string.doctor_install_help))
                    report["path.candidate"].takeIf {it.isNotBlank()}?.let {Text(stringResource(R.string.doctor_path_candidate,it))}
                }
                if(!busy && error==null && report?.canInitialize==true && detailed) {
                    OutlinedButton(onClick={firstSetup=false;preview=true},modifier=Modifier.fillMaxWidth().testTag("doctor-config-preview")){Text(stringResource(R.string.doctor_config_preview))}
                }
                if(!busy && error==null && report!=null && detailed && !report.canInitialize && report.availability==TmuxAvailability.AVAILABLE &&
                    TmuxRelease.parse(report["version.client"])?.let {it>=TmuxRelease.BASELINE}==true) {
                    TextButton(onClick={
                        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("tmux", "# Review before applying to your tmux configuration\nset -g mouse on\n# history-limit changes apply to new windows\n"))
                        notice=uiText(R.string.doctor_suggestions_copied)
                    },modifier=Modifier.testTag("doctor-copy-suggestions")){Text(stringResource(R.string.doctor_copy_suggestions))}
                }
                if(!busy && receipt!=null) TextButton(onClick={confirmUndo=true},modifier=Modifier.testTag("doctor-undo")){Text(stringResource(R.string.doctor_undo))}
                if(!busy && forCreate && error==null && report?.canCreate==true) TextButton(onClick=onCreateReady,modifier=Modifier.testTag("doctor-continue-create")){Text(stringResource(R.string.doctor_continue_create))}
            }
        }},
        confirmButton={when {
            prompt!=null -> TextButton(onClick={prompt.answer.complete(true);approval=null},modifier=Modifier.testTag("doctor-trust-key")){Text(stringResource(R.string.host_key_trust))}
            !busy -> TextButton(onClick={action=DoctorAction.CHECK;detailed=true;notice=null;attempt++},modifier=Modifier.testTag("doctor-recheck")){Text(stringResource(R.string.doctor_recheck))}
        }},
        dismissButton={TextButton(onClick=::dismiss,modifier=Modifier.testTag("close-tmux-doctor")){Text(stringResource(if(busy) R.string.common_cancel else R.string.common_close))}})
    if(preview) AlertDialog(properties=protectedDialogProperties,onDismissRequest={preview=false},
        title={Text(stringResource(R.string.doctor_config_preview))},
        text={Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.doctor_config_scope,report?.get("home").orEmpty()+"/.tmux.conf"))
            OutlinedTextField(history,{history=it},label={Text(stringResource(R.string.doctor_history_lines))},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("doctor-history-lines"))
            Text(TmuxConfigBootstrap.template(history.toIntOrNull()?.takeIf {it in 2000..500000} ?: 10000),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.doctor_config_pending))
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(firstSetup,{firstSetup=it},modifier=Modifier.testTag("doctor-first-setup"));Text(stringResource(R.string.doctor_first_setup),Modifier.weight(1f))}
        }},confirmButton={TextButton(enabled=firstSetup && history.toIntOrNull() in 2000..500000,onClick={preview=false;action=DoctorAction.INITIALIZE;notice=null;attempt++},modifier=Modifier.testTag("doctor-create-config")){Text(stringResource(R.string.doctor_apply_config))}},
        dismissButton={TextButton(onClick={preview=false}){Text(stringResource(R.string.common_cancel))}})
    if(confirmUndo) AlertDialog(properties=protectedDialogProperties,onDismissRequest={confirmUndo=false},title={Text(stringResource(R.string.doctor_undo))},
        text={Text(stringResource(R.string.doctor_undo_help))},confirmButton={TextButton(onClick={confirmUndo=false;action=DoctorAction.UNDO;notice=null;attempt++},modifier=Modifier.testTag("doctor-confirm-undo")){Text(stringResource(R.string.doctor_undo))}},
        dismissButton={TextButton(onClick={confirmUndo=false}){Text(stringResource(R.string.common_cancel))}})
}

@Composable internal fun DoctorFindings(report: TmuxDoctorReport, autoMouse: Boolean, visible: Set<DoctorCheck> = DoctorCheck.entries.toSet(), remoteTouch: Boolean = true) {
    if(DoctorCheck.WHEEL in visible) Column(Modifier.fillMaxWidth().testTag("doctor-row-touch"),verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text((if(remoteTouch) "✓ " else "⚠ ")+stringResource(R.string.doctor_check_touch),
            color=if(remoteTouch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,style=MaterialTheme.typography.titleSmall)
        Text(stringResource(if(remoteTouch) R.string.doctor_touch_on else R.string.doctor_touch_off),style=MaterialTheme.typography.bodySmall)
    }
    report.findings().filter {it.check in visible}.forEach {finding->
        val color=when(finding.grade) {DoctorGrade.PASS->MaterialTheme.colorScheme.primary;DoctorGrade.FAIL->MaterialTheme.colorScheme.error;DoctorGrade.WARNING->MaterialTheme.colorScheme.tertiary;DoctorGrade.UNKNOWN->MaterialTheme.colorScheme.onSurfaceVariant}
        Column(Modifier.fillMaxWidth().testTag("doctor-row-${finding.check.name.lowercase()}"),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text((when(finding.grade) {DoctorGrade.PASS->"✓";DoctorGrade.FAIL->"✕";DoctorGrade.WARNING->"⚠";DoctorGrade.UNKNOWN->"—"})+" "+stringResource(doctorTitle(finding.check)),color=color,style=MaterialTheme.typography.titleSmall)
            Text(stringResource(doctorReason(finding.reason)),style=MaterialTheme.typography.bodySmall)
            if(finding.detail.isNotBlank()) Text(finding.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(finding.check==DoctorCheck.MOUSE && autoMouse && finding.reason==DoctorReason.MOUSE_OFF) Text(stringResource(R.string.doctor_auto_mouse),style=MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun doctorTitle(check: DoctorCheck): Int = when(check) {
    DoctorCheck.SSH->R.string.doctor_check_ssh;DoctorCheck.EXECUTABLE->R.string.doctor_check_executable
    DoctorCheck.VERSION->R.string.doctor_check_version;DoctorCheck.SESSIONS->R.string.doctor_check_sessions
    DoctorCheck.PROTOCOL->R.string.doctor_check_protocol;DoctorCheck.MOUSE->R.string.doctor_check_mouse
    DoctorCheck.WHEEL->R.string.doctor_check_wheel;DoctorCheck.HISTORY->R.string.doctor_check_history
    DoctorCheck.PERSISTENCE->R.string.doctor_check_persistence;DoctorCheck.INPUT->R.string.doctor_check_input
    DoctorCheck.DEPENDENCIES->R.string.doctor_check_dependencies;DoctorCheck.DISPLAY->R.string.doctor_check_display
    DoctorCheck.CONFIG->R.string.doctor_check_config
}

internal fun doctorReason(reason: DoctorReason): Int = when(reason) {
    DoctorReason.VERIFIED->R.string.doctor_reason_verified
    DoctorReason.EXECUTABLE_OK->R.string.doctor_reason_executable_ok
    DoctorReason.PATH_MISSING->R.string.doctor_reason_path_missing
    DoctorReason.PATH_NOT_EXECUTABLE->R.string.doctor_reason_path_not_executable
    DoctorReason.EXECUTABLE_FAILED->R.string.doctor_reason_executable_failed
    DoctorReason.VERSION_OK->R.string.doctor_reason_version_ok
    DoctorReason.VERSION_OLD->R.string.doctor_reason_version_old
    DoctorReason.VERSION_UNKNOWN->R.string.doctor_reason_version_unknown
    DoctorReason.SESSIONS_OK->R.string.doctor_reason_sessions_ok
    DoctorReason.NO_SERVER->R.string.doctor_reason_no_server
    DoctorReason.SERVER_INACCESSIBLE->R.string.doctor_reason_server_inaccessible
    DoctorReason.PROTOCOL_OK->R.string.doctor_reason_protocol_ok
    DoctorReason.PROTOCOL_MISSING->R.string.doctor_reason_protocol_missing
    DoctorReason.MOUSE_ON->R.string.doctor_reason_mouse_on
    DoctorReason.MOUSE_OFF->R.string.doctor_reason_mouse_off
    DoctorReason.WHEEL_OK->R.string.doctor_reason_wheel_ok
    DoctorReason.WHEEL_CUSTOM->R.string.doctor_reason_wheel_custom
    DoctorReason.WHEEL_MISSING->R.string.doctor_reason_wheel_missing
    DoctorReason.HISTORY_OK->R.string.doctor_reason_history_ok
    DoctorReason.HISTORY_SMALL->R.string.doctor_reason_history_small
    DoctorReason.PERSISTENCE_OK->R.string.doctor_reason_persistence_ok
    DoctorReason.PERSISTENCE_CHANGED->R.string.doctor_reason_persistence_changed
    DoctorReason.INPUT_OK->R.string.doctor_reason_input_ok
    DoctorReason.INPUT_SYNC->R.string.doctor_reason_input_sync
    DoctorReason.HELPERS_OK->R.string.doctor_reason_helpers_ok
    DoctorReason.HELPERS_MISSING->R.string.doctor_reason_helpers_missing
    DoctorReason.DISPLAY_OK->R.string.doctor_reason_display_ok
    DoctorReason.DISPLAY_CHECK->R.string.doctor_reason_display_check
    DoctorReason.CONFIG_EXISTS->R.string.doctor_reason_config_exists
    DoctorReason.CONFIG_READY->R.string.doctor_reason_config_ready
    DoctorReason.CONFIG_UNAVAILABLE->R.string.doctor_reason_config_unavailable
    DoctorReason.NOT_CHECKED->R.string.doctor_reason_not_checked
}
