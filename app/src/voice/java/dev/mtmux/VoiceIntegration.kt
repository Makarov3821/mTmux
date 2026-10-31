package dev.mtmux

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mtmux.voice.VoiceRecorder
import dev.mtmux.voice.XasrTranscriber
import dev.mtmux.voice.hasAudibleSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

internal fun initializeVoiceFlavor(context: android.content.Context) {
    kotlin.concurrent.thread(name = "voice-upgrade-cleanup", isDaemon = true) {
        runCatching { XasrTranscriber.removeLegacyModels(context.applicationContext) }
    }
}

@Composable internal fun VoiceSettings() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SettingRow(stringResource(R.string.voice_settings_title), icon = UiSymbol.MIC)
    Text(stringResource(R.string.voice_model_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("voice-model-info"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VoiceInputControl(
    modifier: Modifier, enabled: Boolean, connectionToken: String, targetKey: String,
    onHideKeyboard: () -> Unit, onOfferDraft: (String,String,String) -> VoiceDraftOffer,
    onCommitDraft: (String,String,String,Boolean) -> Boolean,
    onStatus: (TerminalStatus) -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val state=remember {VoiceHoldState()}
    val recorder=remember {VoiceRecorder()}
    val recorderControl=remember {Executors.newSingleThreadExecutor()}
    val recorderDispatcher=remember(recorderControl) {recorderControl.asCoroutineDispatcher()}
    val mainHandler=remember {Handler(Looper.getMainLooper())}
    val transcriber=remember {XasrTranscriber(context.applicationContext)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestToken by rememberUpdatedState(connectionToken)
    val latestTarget by rememberUpdatedState(targetKey)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestHideKeyboard by rememberUpdatedState(onHideKeyboard)
    val latestOffer by rememberUpdatedState(onOfferDraft)
    val latestCommit by rememberUpdatedState(onCommitDraft)
    val latestStatus by rememberUpdatedState(onStatus)
    var errorText by remember {mutableIntStateOf(R.string.voice_error_generic)}
    var resultText by remember {mutableStateOf("")}
    var capturedToken by remember {mutableStateOf("")}
    var capturedTarget by remember {mutableStateOf("")}
    var requestId by remember {mutableLongStateOf(0L)}

    fun cancelAll() {
        val hadWork=state.phase!=VoicePhase.IDLE || requestId!=0L
        state.cancel()
        if(requestId!=0L) transcriber.cancel(requestId)
        requestId=0L
        if(hadWork) {
            runCatching {recorderControl.execute {runCatching {recorder.discard()}}}
            DebugLog.event(DebugLog.Event.VOICE_CANCEL)
        }
    }

    fun transcribe(id: Long) {
        val token=capturedToken
        val target=capturedTarget
        val elapsed=(SystemClock.elapsedRealtime()-state.startedAt).coerceAtLeast(0L)
        DebugLog.event(DebugLog.Event.VOICE_RECORD_STOP,(elapsed/1000).toInt())
        scope.launch {
            var ownRequest=0L
            try {
                // Serialize stop with start/cancel, including a slow microphone initialization.
                val pcm=withContext(recorderDispatcher) {
                    if(state.owns(id)) recorder.stop() else null
                } ?: return@launch
                if(!state.owns(id)) return@launch
                if(!hasAudibleSpeech(pcm)) {
                    errorText=R.string.voice_error_no_speech;state.error(id);return@launch
                }
                ownRequest=transcriber.newRequestId()
                requestId=ownRequest
                DebugLog.event(DebugLog.Event.VOICE_TRANSCRIBE,pcm.size/VoiceRecorder.SAMPLE_RATE)
                val result=withContext(Dispatchers.IO) {
                    transcriber.transcribe(pcm,minOf(4,Runtime.getRuntime().availableProcessors()),ownRequest)
                }
                if(!state.owns(id)) return@launch
                val offeredText=result.text
                resultText=offeredText
                when(latestOffer(token,target,offeredText)) {
                    VoiceDraftOffer.TARGET_CHANGED -> {errorText=R.string.voice_error_target_changed;state.error(id)}
                    VoiceDraftOffer.NO_SPEECH -> {errorText=R.string.voice_error_no_speech;state.error(id)}
                    VoiceDraftOffer.INSERTED -> {
                        DebugLog.event(DebugLog.Event.VOICE_RESULT,result.elapsedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        state.complete(id)
                        latestStatus(TerminalStatus(uiText(R.string.voice_inserted)))
                    }
                    VoiceDraftOffer.NEEDS_CHOICE -> {
                        DebugLog.event(DebugLog.Event.VOICE_RESULT,result.elapsedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        state.result(id)
                    }
                }
            } catch(error: CancellationException) {
                if(ownRequest!=0L) transcriber.cancel(ownRequest)
                throw error
            } catch(error: Exception) {
                if(!state.owns(id)) return@launch
                errorText=when {
                    "VOICE_TOO_SHORT" in error.message.orEmpty() -> R.string.voice_error_too_short
                    "VOICE_MODEL" in error.message.orEmpty() -> R.string.voice_error_model
                    "VOICE_AUDIO" in error.message.orEmpty() -> R.string.voice_error_audio
                    "VOICE_CANCELLED" in error.message.orEmpty() -> R.string.voice_error_cancelled
                    else -> R.string.voice_error_generic
                }
                state.error(id)
                DebugLog.event(DebugLog.Event.VOICE_ERROR)
            } finally {
                // An old completion must never clear a newer recording's cancellation handle.
                if(requestId==ownRequest) requestId=0L
                if(ownRequest!=0L) transcriber.releaseRequest(ownRequest)
            }
        }
    }

    fun handleRelease(id: Long, decision: VoiceRelease) {
        when(decision) {
            VoiceRelease.NONE -> Unit
            VoiceRelease.CANCEL -> {
                runCatching {recorderControl.execute {runCatching {recorder.discard()}}}
                DebugLog.event(DebugLog.Event.VOICE_CANCEL)
            }
            VoiceRelease.TRANSCRIBE -> transcribe(id)
        }
    }

    fun finishRecording(id: Long) {handleRelease(id,state.finish(id))}

    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        DebugLog.event(DebugLog.Event.VOICE_PERMISSION,if(granted) 1 else 0)
        // Permission UI ends the original gesture: granting must not start a background recording.
        if(granted) Toast.makeText(context,R.string.voice_permission_ready,Toast.LENGTH_SHORT).show()
        else {errorText=R.string.voice_error_permission;state.error(state.generation)}
    }

    fun requestStart(accessibility: Boolean=false): Long? {
        if(!latestEnabled || state.phase!=VoicePhase.IDLE) return null
        capturedToken=latestToken;capturedTarget=latestTarget;resultText=""
        latestHideKeyboard()
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return null
        }
        val id=state.begin(accessibility) ?: return null
        latestStatus(TerminalStatus.NONE)
        runCatching {recorderControl.execute {
            if(!state.owns(id)) return@execute
            val started=runCatching {recorder.start {finishRecording(id)}}
            val readyAt=SystemClock.elapsedRealtime()
            mainHandler.post {
                // Cancellation is already queued after start; do not cancel a newer session here.
                if(!state.owns(id)) return@post
                if(started.isSuccess) {
                    if(state.ready(id,readyAt)) DebugLog.event(DebugLog.Event.VOICE_RECORD_START)
                } else {
                    errorText=R.string.voice_error_audio;state.error(id)
                    DebugLog.event(DebugLog.Event.VOICE_ERROR)
                }
            }
        }}.onFailure {errorText=R.string.voice_error_audio;state.error(id)}
        return id
    }

    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_STOP) cancelAll()}
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            cancelAll()
            runCatching {recorderControl.execute {recorder.close();recorderControl.shutdown()}}
        }
    }
    LaunchedEffect(connectionToken,targetKey,enabled) {
        if(state.phase!=VoicePhase.IDLE && (!enabled || connectionToken!=capturedToken || targetKey!=capturedTarget)) cancelAll()
    }
    LaunchedEffect(state.phase,state.startedAt,state.generation) {
        if(state.phase==VoicePhase.RECORDING) {
            val id=state.generation
            delay(state.remainingMillis(SystemClock.elapsedRealtime()))
            finishRecording(id)
        }
    }
    BackHandler(enabled=state.phase==VoicePhase.STARTING || state.phase==VoicePhase.RECORDING) {cancelAll()}

    VoiceHoldButton(state,enabled,modifier,onPress={requestStart()},onMove={id,armed -> state.move(id,armed)},
        onRelease={id -> handleRelease(id,state.release(id))},
        onCancel={id -> if(state.owns(id) && state.phase in listOf(VoicePhase.STARTING,VoicePhase.RECORDING)) cancelAll()},
        onAccessibleClick={
            if(state.phase in listOf(VoicePhase.STARTING,VoicePhase.RECORDING)) finishRecording(state.generation)
            else requestStart(accessibility=true)
        })
    VoiceRecordingFeedback(state,{recorder.level},visible=state.phase==VoicePhase.STARTING || state.phase==VoicePhase.RECORDING)
    BackHandler(enabled=state.phase==VoicePhase.TRANSCRIBING) {cancelAll()}
    VoiceTranscribingBubble(visible=state.phase==VoicePhase.TRANSCRIBING,onCancel={cancelAll()})

    when(state.phase) {
        VoicePhase.ERROR -> if(errorText==R.string.voice_error_permission) AppSheet(onDismiss={cancelAll()}) {
            SheetHeader(stringResource(R.string.voice_title),stringResource(R.string.voice_local_badge),UiSymbol.MIC,Tone.WARNING)
            Text(stringResource(errorText),Modifier.padding(horizontal=12.dp,vertical=8.dp).testTag("voice-error"),style=MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TonalActionButton(DialogAction(stringResource(R.string.common_close),{cancelAll()}),Modifier.weight(1f))
                PrimaryActionButton(DialogAction(stringResource(R.string.voice_open_settings),{
                    cancelAll()
                    runCatching {context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.fromParts("package",context.packageName,null)))}
                }),Modifier.weight(1f))
            }
        } else VoiceErrorHint(stringResource(errorText)) {cancelAll()}
        VoicePhase.RESULT -> AppSheet(onDismiss={cancelAll()}) {
            SheetHeader(stringResource(R.string.voice_title),stringResource(R.string.voice_result_help),UiSymbol.MIC,Tone.ACCENT)
            InsetPanel(Modifier.padding(horizontal=12.dp).testTag("voice-result")) {SelectionContainer {Text(resultText,style=MaterialTheme.typography.bodyLarge)}}
            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(DialogAction(stringResource(R.string.quick_reply_append),{
                    if(latestCommit(capturedToken,capturedTarget,resultText,true)) state.complete(state.generation)
                    else {errorText=R.string.voice_error_target_changed;state.error(state.generation)}
                },"voice-append"),Modifier.fillMaxWidth())
                TonalActionButton(DialogAction(stringResource(R.string.quick_reply_replace),{
                    if(latestCommit(capturedToken,capturedTarget,resultText,false)) state.complete(state.generation)
                    else {errorText=R.string.voice_error_target_changed;state.error(state.generation)}
                },"voice-replace"),Modifier.fillMaxWidth())
                QuietActionButton(DialogAction(stringResource(R.string.common_cancel),{cancelAll()}),Modifier.fillMaxWidth())
            }
        }
        else -> Unit
    }
}

/**
 * Transcription in place of the recording bubble. A non-focusable popup so it never takes the IME or
 * focus from the editor card that opens next; it settles in from the bubble position and fades out
 * while the card slides up. Tapping outside the bubble or Back cancels.
 */
@Composable internal fun VoiceTranscribingBubble(visible: Boolean = true, onCancel: () -> Unit) {
    val shown=remember {androidx.compose.animation.core.MutableTransitionState(false)}
    shown.targetState=visible
    if (!shown.currentState && !shown.targetState) return
    val progress by androidx.compose.animation.core.rememberTransition(shown,label="voice-transcribe").animateFloat(transitionSpec={
        androidx.compose.animation.core.tween(if (false isTransitioningTo true) 200 else 180)
    },label="voice-transcribe-progress") { if (it) 1f else 0f }
    val density=androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.ui.window.Popup(alignment=androidx.compose.ui.Alignment.Center,
        properties=androidx.compose.ui.window.PopupProperties(focusable=false,usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize().then(if(visible) Modifier.clickable(interactionSource=null,indication=null,onClick=onCancel) else Modifier),
            contentAlignment=androidx.compose.ui.Alignment.Center) {
            Surface(Modifier.padding(horizontal=32.dp).widthIn(max=360.dp).fillMaxWidth().graphicsLayer {
                alpha=progress;val k=.92f+.08f*progress;scaleX=k;scaleY=k
                translationY=(if (visible) 1f-progress else progress-1f)*with(density) {28.dp.toPx()}
            },shape=androidx.compose.foundation.shape.RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceVariant,shadowElevation=10.dp) {
                Column(Modifier.padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)
                        Text(stringResource(R.string.voice_transcribing),Modifier.weight(1f).testTag("voice-transcribing"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick=onCancel,enabled=visible){Text(stringResource(R.string.common_cancel))}
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp),trackColor=MaterialTheme.colorScheme.surface,strokeCap=androidx.compose.ui.graphics.StrokeCap.Round)
                    Text(stringResource(R.string.voice_result_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
