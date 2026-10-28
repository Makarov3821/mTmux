package dev.mtmux.audiotest

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal object NativeWhisper {
    init { System.loadLibrary("audiolab_whisper") }
    external fun transcribe(modelPath: String, pcm: FloatArray, language: String, threads: Int, requestId: Long): ByteArray
    external fun cancel(requestId: Long): Boolean
    external fun forget(requestId: Long)
}
data class Decoded(val text: String, val loadMs: Long, val decodeMs: Long, val language: String = "")

/** All recognizers are scoped to one row. No ten-model pool stays resident. */
class Engines(private val root: File, private val catalog: Catalog, private val threads: Int = 4) {
    init { require(threads in 1..8) }
    private fun path(s: Scheme, role: String): String {
        val weights = catalog.group(s.group).files
        val file = if (role == "model") weights.first { it.path.endsWith(".bin") || it.path.substringAfterLast('/') in listOf("model.int8.onnx", "model.onnx") }
            else if (role == "tokens") weights.single { it.path.endsWith("tokens.txt") }
            else weights.single { (it.path.substringAfterLast('/').startsWith("$role-") || it.path.substringAfterLast('/').startsWith("$role.")) }
        return file.file(root).absolutePath
    }
    fun decode(s: Scheme, pcm: FloatArray, language: String, stop: AtomicBoolean): Decoded {
        fun checkStop() { if (stop.get()) throw CancellationException("已停止") }
        checkStop()
        val started = SystemClock.elapsedRealtime()
        if (s.engine == "whisper") {
            val request = System.nanoTime().coerceAtLeast(1)
            try {
                val json = JSONObject(NativeWhisper.transcribe(path(s,"model"), pcm, language, threads, request).toString(Charsets.UTF_8))
                val load = json.getLong("loadMs")
                return Decoded(json.getString("text").trim(), load, json.getLong("elapsedMs") - load, json.getString("language"))
            } finally { NativeWhisper.forget(request) }
        }
        if (s.engine == "online") {
            val model = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(path(s,"encoder"), path(s,"decoder"), path(s,"joiner")),
                tokens = path(s,"tokens"), numThreads = threads, provider = "cpu",
                modelType = if (s.group == "zip-small") "zipformer" else "zipformer2")
            val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(modelConfig = model, enableEndpoint = false, decodingMethod = "greedy_search"))
            try {
                val stream = recognizer.createStream()
                try {
                    val loaded = SystemClock.elapsedRealtime()
                    // Retain the streaming protocol after recording. End-of-input and tail audio flush final tokens.
                    for (start in pcm.indices step 1600) {
                        checkStop()
                        stream.acceptWaveform(pcm.copyOfRange(start, minOf(start + 1600, pcm.size)), 16000)
                        while (recognizer.isReady(stream)) { checkStop(); recognizer.decode(stream) }
                    }
                    stream.acceptWaveform(FloatArray(16000), 16000)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) { checkStop(); recognizer.decode(stream) }
                    return Decoded(recognizer.getResult(stream).text.trim(), loaded - started, SystemClock.elapsedRealtime() - loaded)
                } finally { stream.release() }
            } finally { recognizer.release() }
        }
        val model = OfflineModelConfig(tokens = path(s,"tokens"), numThreads = threads, provider = "cpu")
        when (s.engine) {
            "paraformer" -> { model.paraformer = OfflineParaformerModelConfig(path(s,"model")); model.modelType = "paraformer" }
            "sensevoice" -> { model.senseVoice = OfflineSenseVoiceModelConfig(path(s,"model"), if (language == "auto") "auto" else language, true); model.modelType = "sense_voice" }
            "transducer" -> { model.transducer = OfflineTransducerModelConfig(path(s,"encoder"), path(s,"decoder"), path(s,"joiner")); model.modelType = "transducer" }
            else -> error("未知引擎 ${s.engine}")
        }
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = model, decodingMethod = "greedy_search"))
        try {
            val stream = recognizer.createStream()
            try {
                val loaded = SystemClock.elapsedRealtime()
                checkStop(); stream.acceptWaveform(pcm, 16000); recognizer.decode(stream); checkStop()
                val result = recognizer.getResult(stream)
                return Decoded(result.text.trim(), loaded - started, SystemClock.elapsedRealtime() - loaded, result.lang)
            } finally { stream.release() }
        } finally { recognizer.release() }
    }
}
