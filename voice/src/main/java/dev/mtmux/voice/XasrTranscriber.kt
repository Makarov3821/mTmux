package dev.mtmux.voice

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** X-ASR 480ms INT8: bilingual recognition and punctuation in a single model. */
class XasrTranscriber(private val context: Context) {
    private val requests = VoiceRequests()
    private val assets by lazy {
        val files = JSONObject(context.assets.open("voice-model.json").bufferedReader().use { it.readText() })
            .getJSONArray("files")
        (0 until files.length()).map { i ->
            val spec = files.getJSONObject(i)
            BundledVoiceAsset(spec.getString("path"), spec.getLong("bytes"), spec.getString("sha256"))
        }
    }

    fun prepareModel(): List<File> = try { assets.map { it.file(context) } }
    catch (error: java.io.IOException) { throw IllegalStateException("VOICE_MODEL_INSTALL", error) }
    fun newRequestId(): Long = requests.create()
    fun cancel(requestId: Long): Boolean = requests.cancel(requestId)
    fun releaseRequest(requestId: Long) = requests.release(requestId)

    /** The caller runs this on a worker. Native objects are released even when cancelled. */
    fun transcribe(pcm: FloatArray, threads: Int, requestId: Long): VoiceResult {
        try {
            requests.check(requestId)
            require(pcm.size in 1600..VoiceRecorder.SAMPLE_RATE * VoiceRecorder.MAX_SECONDS && pcm.all { it.isFinite() }) {
                "VOICE_AUDIO_INVALID"
            }
            // A cancelled older request can still be inside one native decode. Never load a second model alongside it.
            while (!inferenceLock.tryLock(50, TimeUnit.MILLISECONDS)) requests.check(requestId)
            try {
                requests.check(requestId)
                val started = SystemClock.elapsedRealtime()
                val paths = prepareModel().associateBy { it.name }
                requests.check(requestId)
                val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = paths.getValue("encoder.int8.onnx").absolutePath,
                            decoder = paths.getValue("decoder.onnx").absolutePath,
                            joiner = paths.getValue("joiner.int8.onnx").absolutePath),
                        tokens = paths.getValue("tokens.txt").absolutePath,
                        numThreads = threads.coerceIn(1, 4), provider = "cpu", modelType = "zipformer2"),
                    enableEndpoint = false, decodingMethod = "greedy_search"))
                try {
                    requests.check(requestId)
                    val stream = recognizer.createStream()
                    try {
                        fun drain() {
                            while (recognizer.isReady(stream)) {
                                requests.check(requestId)
                                recognizer.decode(stream)
                            }
                        }
                        // Match Audio Lab: feed 100ms chunks, then flush one second of silence and end-of-input.
                        for (start in pcm.indices step 1600) {
                            requests.check(requestId)
                            stream.acceptWaveform(pcm.copyOfRange(start, minOf(start + 1600, pcm.size)), 16000)
                            drain()
                        }
                        requests.check(requestId)
                        stream.acceptWaveform(FloatArray(16000), 16000)
                        stream.inputFinished()
                        drain()
                        val text = recognizer.getResult(stream).text.trim()
                        requests.complete(requestId)
                        return VoiceResult(text, SystemClock.elapsedRealtime() - started)
                    } finally { stream.release() }
                } finally { recognizer.release() }
            } finally { inferenceLock.unlock() }
        } finally { requests.release(requestId) }
    }

    companion object {
        private val inferenceLock = ReentrantLock()

        /** Only remove the previous engine's known files; do not touch drafts or recordings. */
        fun removeLegacyModels(context: Context) {
            val directory = File(context.noBackupFilesDir, "voice")
            for (name in listOf("ggml-base-q5_1.bin", "ggml-base-q8_0.bin", "ct-punct-int8.onnx", "srf-punct-int8.onnx", "srf-vocab.json")) {
                File(directory, name).delete()
                File(directory, "$name.part").delete()
            }
            context.getSharedPreferences("voice-settings", 0).edit()
                .remove("automatic-language").remove("restore-punctuation").apply()
        }
    }
}

data class VoiceResult(val text: String, val elapsedMs: Long)

fun hasAudibleSpeech(pcm: FloatArray): Boolean {
    if (pcm.size < VoiceRecorder.SAMPLE_RATE / 10) return false
    var sum = 0.0
    for (sample in pcm) { val value = sample.toDouble(); sum += value * value }
    return kotlin.math.sqrt(sum / pcm.size) >= 0.001
}
