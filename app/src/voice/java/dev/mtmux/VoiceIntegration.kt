package dev.mtmux

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mtmux.voice.VoiceRecorder
import dev.mtmux.voice.WhisperTranscriber
import dev.mtmux.voice.hasAudibleSpeech
import dev.mtmux.voice.recognitionLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlinx.coroutines.withContext

internal fun initializeVoiceFlavor(context: android.content.Context) = Unit

private enum class VoicePhase { IDLE, STARTING, RECORDING, TRANSCRIBING, RESULT, ERROR }

@Composable internal fun VoiceSettings() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("voice-settings", 0) }
    var automatic by remember { mutableStateOf(preferences.getBoolean("automatic-language", false)) }
    Text(stringResource(R.string.voice_settings_title), color = MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.voice_auto_language))
            Text(stringResource(R.string.voice_auto_language_help), style = MaterialTheme.typography.bodySmall)
        }
        Switch(automatic, { automatic = it; preferences.edit().putBoolean("automatic-language", it).apply() },
            modifier = Modifier.testTag("voice-auto-language"))
    }
}

@Composable internal fun VoiceInputControl(
    modifier: Modifier, enabled: Boolean, connectionToken: String, targetKey: String,
    onHideKeyboard: () -> Unit, onOfferDraft: (String,String,String) -> VoiceDraftOffer,
    onCommitDraft: (String,String,String,Boolean) -> Boolean,
    onStatus: (TerminalStatus) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder() }
    val recorderControl = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val transcriber = remember { WhisperTranscriber(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uiLanguage = LocalConfiguration.current.locales[0].language.let { if (it == "zh") "zh" else "en" }
    val latestToken by rememberUpdatedState(connectionToken)
    val latestTarget by rememberUpdatedState(targetKey)
    var phase by remember { mutableStateOf(VoicePhase.IDLE) }
    var dialog by remember { mutableStateOf(false) }
    var errorText by remember { mutableIntStateOf(R.string.voice_error_generic) }
    var resultText by remember { mutableStateOf("") }
    var capturedToken by remember { mutableStateOf("") }
    var capturedTarget by remember { mutableStateOf("") }
    var requestId by remember { mutableLongStateOf(0L) }
    var operation by remember { mutableIntStateOf(0) }
    var recordingStarted by remember { mutableLongStateOf(0L) }
    var startAfterPermission by remember { mutableStateOf(false) }

    fun cancelAll(close: Boolean = true) {
        operation++
        startAfterPermission = false
        if (requestId != 0L) transcriber.cancel(requestId)
        requestId = 0L
        runCatching { recorderControl.execute { recorder.cancel() } }
        phase = VoicePhase.IDLE
        if (close) dialog = false
        DebugLog.event(DebugLog.Event.VOICE_CANCEL)
    }

    lateinit var stopRecording: () -> Unit
    stopRecording = stop@{
        if (phase != VoicePhase.RECORDING) return@stop
        val thisOperation = operation
        phase = VoicePhase.TRANSCRIBING
        DebugLog.event(DebugLog.Event.VOICE_RECORD_STOP, ((System.currentTimeMillis() - recordingStarted) / 1000).toInt())
        scope.launch {
            try {
                val pcm = withContext(Dispatchers.IO) { recorder.stop() }
                if (thisOperation != operation) return@launch
                if(!hasAudibleSpeech(pcm)){errorText=R.string.voice_error_no_speech;phase=VoicePhase.ERROR;return@launch}
                val automatic = context.getSharedPreferences("voice-settings", 0).getBoolean("automatic-language", false)
                val language = recognitionLanguage(automatic, uiLanguage)
                val id = transcriber.newRequestId(); requestId = id
                DebugLog.event(DebugLog.Event.VOICE_TRANSCRIBE, pcm.size / 16000)
                val result = withContext(Dispatchers.IO) {
                    transcriber.transcribe(pcm, language, minOf(4, Runtime.getRuntime().availableProcessors()), id)
                }
                requestId = 0L
                if (thisOperation != operation) return@launch
                resultText = result.text
                val offeredText=if(result.noSpeechProbability>=0.8f) "" else result.text
                when (onOfferDraft(capturedToken,capturedTarget,offeredText)) {
                    VoiceDraftOffer.TARGET_CHANGED -> {errorText=R.string.voice_error_target_changed;phase=VoicePhase.ERROR}
                    VoiceDraftOffer.NO_SPEECH -> {errorText=R.string.voice_error_no_speech;phase=VoicePhase.ERROR}
                    VoiceDraftOffer.INSERTED -> {
                        DebugLog.event(DebugLog.Event.VOICE_RESULT, result.elapsedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        dialog=false;phase=VoicePhase.IDLE
                        onStatus(TerminalStatus(uiText(R.string.voice_inserted)))
                    }
                    VoiceDraftOffer.NEEDS_CHOICE -> {
                        DebugLog.event(DebugLog.Event.VOICE_RESULT, result.elapsedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        phase=VoicePhase.RESULT
                    }
                }
            } catch (error: Exception) {
                requestId = 0L
                if (thisOperation != operation) return@launch
                val message = error.message.orEmpty()
                errorText = when {
                    "VOICE_TOO_SHORT" in message -> R.string.voice_error_too_short
                    "VOICE_MODEL" in message -> R.string.voice_error_model
                    "VOICE_AUDIO" in message -> R.string.voice_error_audio
                    "VOICE_CANCELLED" in message -> R.string.voice_error_cancelled
                    else -> R.string.voice_error_generic
                }
                phase = VoicePhase.ERROR
                DebugLog.event(DebugLog.Event.VOICE_ERROR)
            }
        }
    }

    fun startRecording() {
        if (latestToken != capturedToken || latestTarget != capturedTarget) {
            errorText = R.string.voice_error_target_changed; phase = VoicePhase.ERROR; dialog = true; return
        }
        operation++
        val thisOperation=operation
        phase=VoicePhase.STARTING;dialog=true
        runCatching { recorderControl.execute {
            val started=runCatching { recorder.start { stopRecording() } }
            mainHandler.post {
                if(thisOperation!=operation){runCatching{recorderControl.execute{recorder.cancel()}};return@post}
                if(started.isSuccess){
                    recordingStarted=System.currentTimeMillis();phase=VoicePhase.RECORDING
                    DebugLog.event(DebugLog.Event.VOICE_RECORD_START)
                } else {
                    errorText=R.string.voice_error_audio;phase=VoicePhase.ERROR
                    DebugLog.event(DebugLog.Event.VOICE_ERROR)
                }
            }
        }}.onFailure {errorText=R.string.voice_error_audio;phase=VoicePhase.ERROR;dialog=true}
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        DebugLog.event(DebugLog.Event.VOICE_PERMISSION, if (granted) 1 else 0)
        if (startAfterPermission && granted) startRecording()
        else if (startAfterPermission) { errorText = R.string.voice_error_permission; phase = VoicePhase.ERROR; dialog = true }
        startAfterPermission = false
    }

    fun requestStart() {
        capturedToken = connectionToken; capturedTarget = targetKey; resultText = ""
        onHideKeyboard()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else { startAfterPermission = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancelAll() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer);cancelAll()
            runCatching {recorderControl.execute {recorder.close();recorderControl.shutdown()}}
        }
    }
    LaunchedEffect(connectionToken, targetKey) {
        if (phase != VoicePhase.IDLE && (connectionToken != capturedToken || targetKey != capturedTarget)) cancelAll()
    }

    val seconds by produceState(0, phase, recordingStarted) {
        while (phase == VoicePhase.RECORDING) { value = ((System.currentTimeMillis() - recordingStarted) / 1000).toInt(); delay(250) }
    }
    val description = stringResource(R.string.voice_button)
    TextButton(onClick = ::requestStart, enabled = enabled && phase == VoicePhase.IDLE,
        contentPadding = PaddingValues(0.dp), modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("voice-input")) {
        Text(if (phase == VoicePhase.RECORDING) "■" else "🎤", maxLines = 1,
            modifier = Modifier.semantics { contentDescription = description })
    }

    if (dialog) AlertDialog(onDismissRequest = { cancelAll() }, title = { Text(stringResource(R.string.voice_title)) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (phase) {
                VoicePhase.STARTING -> {Text(stringResource(R.string.voice_starting));LinearProgressIndicator(Modifier.fillMaxWidth())}
                VoicePhase.RECORDING -> { Text(stringResource(R.string.voice_recording, seconds)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                VoicePhase.TRANSCRIBING -> { Text(stringResource(R.string.voice_transcribing),Modifier.testTag("voice-transcribing")); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                VoicePhase.RESULT -> { Text(stringResource(R.string.voice_result_help),Modifier.testTag("voice-result")); SelectionContainer { Text(resultText) } }
                VoicePhase.ERROR -> Text(stringResource(errorText),Modifier.testTag("voice-error"),color = MaterialTheme.colorScheme.error)
                VoicePhase.IDLE -> Unit
            }
        }
    }, confirmButton = {
        when (phase) {
            VoicePhase.RECORDING -> TextButton(onClick = stopRecording, modifier = Modifier.testTag("voice-stop")) { Text(stringResource(R.string.voice_stop)) }
            VoicePhase.RESULT -> TextButton(onClick = {
                if(onCommitDraft(capturedToken,capturedTarget,resultText,true)){dialog=false;phase=VoicePhase.IDLE}
                else {errorText=R.string.voice_error_target_changed;phase=VoicePhase.ERROR}
            }, modifier = Modifier.testTag("voice-append")) { Text(stringResource(R.string.quick_reply_append)) }
            VoicePhase.ERROR -> TextButton(onClick = { dialog = false; phase = VoicePhase.IDLE }) { Text(stringResource(R.string.common_close)) }
            else -> TextButton(onClick = { cancelAll() }) { Text(stringResource(R.string.common_cancel)) }
        }
    }, dismissButton = {
        if (phase == VoicePhase.RESULT) Row {
            TextButton(onClick = {
                if(onCommitDraft(capturedToken,capturedTarget,resultText,false)){dialog=false;phase=VoicePhase.IDLE}
                else {errorText=R.string.voice_error_target_changed;phase=VoicePhase.ERROR}
            }, modifier = Modifier.testTag("voice-replace")) { Text(stringResource(R.string.quick_reply_replace)) }
            TextButton(onClick = { cancelAll() }) { Text(stringResource(R.string.common_cancel)) }
        }
    })
}
