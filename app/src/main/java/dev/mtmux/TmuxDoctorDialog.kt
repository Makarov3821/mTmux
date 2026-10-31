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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mtmux.core.*
import kotlinx.coroutines.*

private enum class DoctorAction { CHECK, INITIALIZE, UNDO }

@Composable internal fun TmuxDoctorInvitation(onRun: () -> Unit, onSkip: () -> Unit) {
    AppSheet(onDismiss=onSkip) {
        Column(Modifier.padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ToneBadge(UiSymbol.CHECK,Tone.SUCCESS)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.doctor_saved_title),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold))
                    Text(stringResource(R.string.doctor_saved_detail),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ToneBadge(UiSymbol.SPARKLE,Tone.ACCENT,36.dp,18.dp)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.doctor_invite_title),style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.SemiBold))
                            Text(stringResource(R.string.doctor_invite_body),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        QuietActionButton(DialogAction(stringResource(R.string.doctor_skip),onSkip,"skip-tmux-doctor"),Modifier.weight(1f))
                        PrimaryActionButton(DialogAction(stringResource(R.string.doctor_run),onRun,"run-tmux-doctor"),Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
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
    val checkedLabel=stored?.let { stringResource(if(historical) R.string.doctor_historical else R.string.doctor_checked,
        java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.ROOT).format(java.util.Date(it.checkedAt))) }
    if(prompt!=null) HostKeyDialog(prompt.challenge,onDismiss={prompt.answer.complete(false);approval=null},
        onTrust={prompt.answer.complete(true);approval=null},fingerprintTag="doctor-fingerprint",trustTag="doctor-trust-key")
    AppSheet(onDismiss=::dismiss,floating=false,secure=true) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal=8.dp)) {
            SheetHeader(stringResource(R.string.doctor_title),listOfNotNull(profile.name,checkedLabel ?: stringResource(R.string.doctor_readonly)).joinToString(" · "))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=4.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                val findings=report?.findings().orEmpty()
                val failures=findings.count { it.grade==DoctorGrade.FAIL }
                val warnings=findings.count { it.grade==DoctorGrade.WARNING } + if(!remoteTouch) 1 else 0
                val passed=findings.count { it.grade==DoctorGrade.PASS } + if(remoteTouch) 1 else 0
                when {
                    busy -> DoctorSummary(UiSymbol.SPARKLE,Tone.ACCENT,stringResource(R.string.doctor_running),progress.string(),progressTag="doctor-progress",spinning=true)
                    error!=null -> DoctorSummary(UiSymbol.ALERT_CIRCLE,Tone.DANGER,stringResource(R.string.doctor_check_failed_title),error!!.string(),detailTag="doctor-error")
                    report!=null && failures>0 -> DoctorSummary(UiSymbol.CLOSE,Tone.DANGER,
                        if(report.availability==TmuxAvailability.UNAVAILABLE) stringResource(R.string.doctor_summary_missing) else pluralStringResource(R.plurals.doctor_summary_fail,failures,failures),
                        report["version.client"].takeIf { it.isNotBlank() }?.let { "tmux "+it.removePrefix("tmux ") })
                    report!=null && warnings>0 -> DoctorSummary(UiSymbol.ALERT,Tone.WARNING,pluralStringResource(R.plurals.doctor_summary_warn,warnings,warnings),
                        listOfNotNull(report["version.client"].takeIf { it.isNotBlank() }?.let { "tmux "+it.removePrefix("tmux ") },pluralStringResource(R.plurals.doctor_passed_count,passed,passed)).joinToString(" · "))
                    report!=null -> DoctorSummary(UiSymbol.CHECK,Tone.SUCCESS,stringResource(R.string.doctor_summary_ok),pluralStringResource(R.plurals.doctor_passed_count,passed,passed))
                }
                notice?.let { NoticeStrip(it.string(),Tone.SUCCESS,UiSymbol.CHECK,Modifier.testTag("doctor-notice")) }
                if(report!=null) DoctorFindings(report,profile.enableTmuxMouse,if(detailed) DoctorCheck.entries.toSet() else setOf(DoctorCheck.SSH,DoctorCheck.EXECUTABLE,DoctorCheck.VERSION,DoctorCheck.SESSIONS),remoteTouch,collapsePassing=true)
                else Column { DoctorCheck.entries.forEach { StepRow(stringResource(doctorTitle(it)),if(busy && it==DoctorCheck.SSH) StepState.ACTIVE else StepState.PENDING) } }
                if(detailed && !remoteTouch && onEnableTouch!=null) TonalActionButton(DialogAction(stringResource(R.string.doctor_enable_touch),onEnableTouch,"doctor-enable-touch"),Modifier.fillMaxWidth())
                if(!busy && report?.availability==TmuxAvailability.UNAVAILABLE) InsetPanel {
                    Text(stringResource(R.string.doctor_install_help),style=MaterialTheme.typography.bodySmall)
                    report["path.candidate"].takeIf {it.isNotBlank()}?.let {
                        Text(stringResource(R.string.doctor_path_candidate,it),style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),color=MaterialTheme.colorScheme.primary)
                    }
                }
                if(!busy && error==null && report?.canInitialize==true && detailed)
                    PrimaryActionButton(DialogAction(stringResource(R.string.doctor_config_preview),{firstSetup=false;preview=true},"doctor-config-preview"),Modifier.fillMaxWidth())
                if(!busy && error==null && report!=null && detailed && !report.canInitialize && report.availability==TmuxAvailability.AVAILABLE &&
                    TmuxRelease.parse(report["version.client"])?.let {it>=TmuxRelease.BASELINE}==true && warnings>0)
                    TonalActionButton(DialogAction(stringResource(R.string.doctor_copy_suggestions),{
                        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("tmux", "# Review before applying to your tmux configuration\nset -g mouse on\n# history-limit changes apply to new windows\n"))
                        notice=uiText(R.string.doctor_suggestions_copied)
                    },"doctor-copy-suggestions"),Modifier.fillMaxWidth())
                if(!busy && forCreate && error==null && report?.canCreate==true)
                    PrimaryActionButton(DialogAction(stringResource(R.string.doctor_continue_create),onCreateReady,"doctor-continue-create"),Modifier.fillMaxWidth())
                if(!busy && receipt!=null) QuietActionButton(DialogAction(stringResource(R.string.doctor_undo),{confirmUndo=true},"doctor-undo",danger=true),Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(top=10.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TonalActionButton(DialogAction(stringResource(if(busy) R.string.common_cancel else R.string.common_close),::dismiss,"close-tmux-doctor"),Modifier.weight(1f))
                if(!busy && prompt==null) PrimaryActionButton(DialogAction(stringResource(R.string.doctor_recheck),{action=DoctorAction.CHECK;detailed=true;notice=null;attempt++},"doctor-recheck"),Modifier.weight(1f))
            }
        }
    }
    if(preview) DoctorConfigPreview(report?.get("home").orEmpty(),history,{history=it},firstSetup,{firstSetup=it},onDismiss={preview=false},
        onApply={preview=false;action=DoctorAction.INITIALIZE;notice=null;attempt++})
    if(confirmUndo) DoctorUndoDialog(onDismiss={confirmUndo=false},onConfirm={confirmUndo=false;action=DoctorAction.UNDO;notice=null;attempt++})
}

@Composable internal fun DoctorUndoDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AppDialog(onDismiss=onDismiss,properties=protectedDialogProperties,title=stringResource(R.string.doctor_undo_title),body=stringResource(R.string.doctor_undo_help),
        icon=UiSymbol.ARROW_LEFT,tone=Tone.DANGER,
        confirm=DialogAction(stringResource(R.string.doctor_undo_short),onConfirm,"doctor-confirm-undo",danger=true),
        dismiss=DialogAction(stringResource(R.string.common_cancel),onDismiss))
}

@Composable internal fun DoctorConfigPreview(home: String, history: String, onHistory: (String) -> Unit, firstSetup: Boolean, onFirstSetup: (Boolean) -> Unit,
    onDismiss: () -> Unit, onApply: () -> Unit) {
    AppSheet(onDismiss=onDismiss,floating=false,secure=true) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=12.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.doctor_config_preview),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold))
            Text(stringResource(R.string.doctor_config_scope,"$home/.tmux.conf"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ConfigFileBlock("~/.tmux.conf",TmuxConfigBootstrap.template(history.toIntOrNull()?.takeIf {it in 2000..500000} ?: 10000))
            Text(stringResource(R.string.doctor_history_lines),Modifier.padding(top=4.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("2000","10000","50000").forEach { option -> FilterChip(selected=history==option,onClick={onHistory(option)},label={Text(option)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(10.dp)) }
            }
            OutlinedTextField(history,onHistory,label={Text(stringResource(R.string.doctor_history_custom))},singleLine=true,shape=appFieldShape,colors=appFieldColors(),
                isError=history.toIntOrNull() !in 2000..500000,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("doctor-history-lines"))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable {onFirstSetup(!firstSetup)}.padding(end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(firstSetup,onFirstSetup,modifier=Modifier.testTag("doctor-first-setup"))
                Text(stringResource(R.string.doctor_first_setup),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                AppIcon(UiSymbol.INFO,modifier=Modifier.size(14.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.doctor_config_pending),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(top=4.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                QuietActionButton(DialogAction(stringResource(R.string.common_cancel),onDismiss))
                PrimaryActionButton(DialogAction(stringResource(R.string.doctor_apply_config),onApply,"doctor-create-config",
                    enabled=firstSetup && history.toIntOrNull() in 2000..500000),Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun DoctorSummary(symbol: UiSymbol, tone: Tone, title: String, detail: String?, progressTag: String? = null, detailTag: String? = null, spinning: Boolean = false) {
    val c=tone.color()
    Row(Modifier.fillMaxWidth().background(c.copy(alpha=.10f),RoundedCornerShape(18.dp)).padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).background(c.copy(alpha=.16f),CircleShape),contentAlignment=Alignment.Center) {
            if(spinning) CircularProgressIndicator(Modifier.size(28.dp),strokeWidth=3.dp,color=c) else AppIcon(symbol,modifier=Modifier.size(24.dp),tint=c)
        }
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.SemiBold))
            if(detail!=null) Text(detail,Modifier.then(if(progressTag!=null) Modifier.testTag(progressTag) else if(detailTag!=null) Modifier.testTag(detailTag) else Modifier),
                style=MaterialTheme.typography.bodySmall,color=if(detailTag!=null) StatusColors.attention() else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Read-only file preview with line numbers. */
@Composable private fun ConfigFileBlock(name: String, body: String) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.background,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                AppIcon(UiSymbol.FILE,modifier=Modifier.size(14.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium.copy(fontFamily=FontFamily.Monospace),color=MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primary.copy(alpha=.14f)) {Text(stringResource(R.string.doctor_new_file),Modifier.padding(horizontal=8.dp,vertical=2.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}
            }
            Column(Modifier.padding(horizontal=12.dp,vertical=10.dp)) {
                body.trimEnd().lines().forEachIndexed { i, line ->
                    Row {
                        Text("${i+1}",Modifier.width(22.dp),style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.6f))
                        Text(line,style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),
                            color=if(line.startsWith("#")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable private fun FindingCard(tag: String, grade: DoctorGrade, title: String, reason: String, detail: String = "", extra: String? = null) {
    val (symbol,tone)=when(grade) {DoctorGrade.PASS->UiSymbol.CHECK to Tone.SUCCESS;DoctorGrade.FAIL->UiSymbol.CLOSE to Tone.DANGER;DoctorGrade.WARNING->UiSymbol.ALERT to Tone.WARNING;DoctorGrade.UNKNOWN->UiSymbol.INFO to Tone.NEUTRAL}
    val c=tone.color()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(14.dp)).padding(horizontal=14.dp,vertical=12.dp).testTag(tag),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(18.dp).background(c.copy(alpha=.18f),CircleShape),contentAlignment=Alignment.Center) {AppIcon(symbol,modifier=Modifier.size(11.dp),tint=c)}
            Text(title,style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.SemiBold))
        }
        Column(Modifier.padding(start=28.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(detail.isNotBlank()) Text(detail,style=MaterialTheme.typography.labelSmall.copy(fontFamily=FontFamily.Monospace),color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.75f))
            if(extra!=null) Text(extra,style=MaterialTheme.typography.bodySmall)
        }
    }
}

/** Problems first; with [collapsePassing], passing checks fold into a compact grid. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DoctorFindings(report: TmuxDoctorReport, autoMouse: Boolean, visible: Set<DoctorCheck> = DoctorCheck.entries.toSet(), remoteTouch: Boolean = true, collapsePassing: Boolean = false) {
    data class Row(val tag: String, val grade: DoctorGrade, val title: String, val reason: String, val detail: String = "", val extra: String? = null)
    val rows=buildList {
        if(DoctorCheck.WHEEL in visible) add(Row("doctor-row-touch",if(remoteTouch) DoctorGrade.PASS else DoctorGrade.WARNING,stringResource(R.string.doctor_check_touch),
            stringResource(if(remoteTouch) R.string.doctor_touch_on else R.string.doctor_touch_off)))
        report.findings().filter {it.check in visible}.forEach { finding -> add(Row("doctor-row-${finding.check.name.lowercase()}",finding.grade,stringResource(doctorTitle(finding.check)),
            stringResource(doctorReason(finding.reason)),finding.detail,
            if(finding.check==DoctorCheck.MOUSE && autoMouse && finding.reason==DoctorReason.MOUSE_OFF) stringResource(R.string.doctor_auto_mouse) else null)) }
    }
    val passing=rows.filter { it.grade==DoctorGrade.PASS }
    val unknown=if(collapsePassing) rows.filter { it.grade==DoctorGrade.UNKNOWN } else emptyList()
    val problems=rows.filter { it.grade!=DoctorGrade.PASS && it !in unknown }
    var expanded by remember { mutableStateOf(!collapsePassing) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(collapsePassing && problems.isNotEmpty()) Text(stringResource(R.string.doctor_needs_attention),Modifier.padding(start=4.dp,top=4.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        problems.forEach { FindingCard(it.tag,it.grade,it.title,it.reason,it.detail,it.extra) }
        if(passing.isNotEmpty() && collapsePassing) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {expanded=!expanded}.padding(start=4.dp,top=8.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.doctor_passed_count,passing.size,passing.size),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if(expanded) R.string.doctor_collapse else R.string.doctor_expand),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        }
        if(unknown.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.doctor_unchecked_count,unknown.size,unknown.size),Modifier.padding(start=4.dp,top=8.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            unknown.forEach { row -> Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=4.dp).testTag(row.tag),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top=4.dp).size(8.dp).background(StatusColors.unknown(),CircleShape))
                Column { Text(row.title,style=MaterialTheme.typography.bodyMedium); Text(row.reason,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
        }
        if(expanded) passing.forEach { FindingCard(it.tag,it.grade,it.title,it.reason,it.detail,it.extra) }
        else FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp),maxItemsInEachRow=2) {
            passing.forEach { row ->
                Row(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(10.dp)).padding(horizontal=10.dp,vertical=8.dp).testTag(row.tag),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(16.dp).background(StatusColors.ready().copy(alpha=.18f),CircleShape),contentAlignment=Alignment.Center) {AppIcon(UiSymbol.CHECK,modifier=Modifier.size(10.dp),tint=StatusColors.ready())}
                    Text(row.title,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
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
