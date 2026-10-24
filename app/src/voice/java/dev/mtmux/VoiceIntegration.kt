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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mtmux.voice.VoiceRecorder
import dev.mtmux.voice.WhisperTranscriber
import dev.mtmux.voice.PunctuationCancellation
import dev.mtmux.voice.PunctuationRestorer
import dev.mtmux.voice.hasAudibleSpeech
import dev.mtmux.voice.recognitionLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

internal fun initializeVoiceFlavor(context: android.content.Context) = Unit

@Composable internal fun VoiceSettings() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("voice-settings", 0) }
    var automatic by remember { mutableStateOf(preferences.getBoolean("automatic-language", false)) }
    var punctuation by remember { mutableStateOf(preferences.getBoolean("restore-punctuation", true)) }
    Text(stringResource(R.string.voice_settings_title), color = MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.voice_auto_language))
            Text(stringResource(R.string.voice_auto_language_help), style = MaterialTheme.typography.bodySmall)
        }
        Switch(automatic, { automatic = it; preferences.edit().putBoolean("automatic-language", it).apply() },
            modifier = Modifier.testTag("voice-auto-language"))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.voice_punctuation))
            Text(stringResource(R.string.voice_punctuation_help), style = MaterialTheme.typography.bodySmall)
        }
        Switch(punctuation, { punctuation = it; preferences.edit().putBoolean("restore-punctuation", it).apply() },
            modifier = Modifier.testTag("voice-punctuation"))
    }
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
    val transcriber=remember {WhisperTranscriber(context.applicationContext)}
    val punctuator=remember {PunctuationRestorer(context.applicationContext)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val uiLanguage=LocalConfiguration.current.locales[0].language.let {if(it=="zh") "zh" else "en"}
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
    var punctuationCancellation by remember {mutableStateOf<PunctuationCancellation?>(null)}

    fun cancelAll() {
        val hadWork=state.phase!=VoicePhase.IDLE || requestId!=0L
        state.cancel()
        if(requestId!=0L) transcriber.cancel(requestId)
        punctuationCancellation?.cancel()
        punctuationCancellation=null
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
            var ownPunctuation: PunctuationCancellation?=null
            try {
                // Serialize stop with start/cancel, including a slow microphone initialization.
                val pcm=withContext(recorderDispatcher) {
                    if(state.owns(id)) recorder.stop() else null
                } ?: return@launch
                if(!state.owns(id)) return@launch
                if(!hasAudibleSpeech(pcm)) {
                    errorText=R.string.voice_error_no_speech;state.error(id);return@launch
                }
                val automatic=context.getSharedPreferences("voice-settings",0).getBoolean("automatic-language",false)
                val language=recognitionLanguage(automatic,uiLanguage)
                ownRequest=transcriber.newRequestId()
                requestId=ownRequest
                DebugLog.event(DebugLog.Event.VOICE_TRANSCRIBE,pcm.size/VoiceRecorder.SAMPLE_RATE)
                val result=withContext(Dispatchers.IO) {
                    transcriber.transcribe(pcm,language,minOf(4,Runtime.getRuntime().availableProcessors()),ownRequest)
                }
                if(!state.owns(id)) return@launch
                val restorePunctuation=context.getSharedPreferences("voice-settings",0).getBoolean("restore-punctuation",true)
                val offeredText=if(result.noSpeechProbability>=0.8f) "" else if(restorePunctuation) {
                    val cancellation=PunctuationCancellation()
                    ownPunctuation=cancellation;punctuationCancellation=cancellation
                    DebugLog.event(DebugLog.Event.VOICE_PUNCTUATE)
                    val restored=withContext(Dispatchers.IO) {punctuator.restore(result.text,cancellation)}
                    if(!state.owns(id)) return@launch
                    DebugLog.event(if(restored.failed) DebugLog.Event.VOICE_PUNCTUATE_FAILED else DebugLog.Event.VOICE_PUNCTUATE_DONE,
                        restored.elapsedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),if(restored.processed) 1 else 0)
                    restored.text
                } else result.text
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
                ownPunctuation?.cancel()
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
                if(punctuationCancellation===ownPunctuation) punctuationCancellation=null
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
    if(state.phase==VoicePhase.STARTING || state.phase==VoicePhase.RECORDING) VoiceRecordingFeedback(state)

    if(state.phase in listOf(VoicePhase.TRANSCRIBING,VoicePhase.RESULT,VoicePhase.ERROR)) {
        ModalBottomSheet(onDismissRequest={cancelAll()},containerColor=MaterialTheme.colorScheme.surface,
            shape=MaterialTheme.shapes.extraLarge,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.fillMaxWidth().heightIn(max=540.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    AppIcon(UiSymbol.MIC,tint=MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.voice_title),style=MaterialTheme.typography.titleLarge)
                }
                Text(stringResource(R.string.voice_local_badge),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                InsetPanel {
                    when(state.phase) {
                        VoicePhase.TRANSCRIBING -> {
                            Text(stringResource(R.string.voice_transcribing),Modifier.testTag("voice-transcribing"))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        VoicePhase.RESULT -> {
                            Text(stringResource(R.string.voice_result_help),Modifier.testTag("voice-result"))
                            SelectionContainer {Text(resultText,style=MaterialTheme.typography.bodyLarge)}
                        }
                        VoicePhase.ERROR -> Text(stringResource(errorText),Modifier.testTag("voice-error"),color=MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                }
                if(state.phase==VoicePhase.RESULT) {
                    Button(onClick={
                        if(latestCommit(capturedToken,capturedTarget,resultText,true)) state.complete(state.generation)
                        else {errorText=R.string.voice_error_target_changed;state.error(state.generation)}
                    },modifier=Modifier.fillMaxWidth().testTag("voice-append")) {Text(stringResource(R.string.quick_reply_append))}
                    OutlinedButton(onClick={
                        if(latestCommit(capturedToken,capturedTarget,resultText,false)) state.complete(state.generation)
                        else {errorText=R.string.voice_error_target_changed;state.error(state.generation)}
                    },modifier=Modifier.fillMaxWidth().testTag("voice-replace")) {Text(stringResource(R.string.quick_reply_replace))}
                }
                TextButton(onClick={cancelAll()},modifier=Modifier.fillMaxWidth()) {
                    Text(stringResource(if(state.phase==VoicePhase.ERROR) R.string.common_close else R.string.common_cancel))
                }
            }
        }
    }
}
