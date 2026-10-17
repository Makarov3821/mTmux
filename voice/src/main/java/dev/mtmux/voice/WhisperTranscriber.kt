package dev.mtmux.voice

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WhisperTranscriber(private val context: Context) {
    @Volatile private var verifiedModel: File? = null

    fun prepareModel() { ensureModel() }
    fun probeModel():String = NativeVoice.probe(ensureModel().absolutePath).toString(Charsets.UTF_8)
    fun newRequestId(): Long {
        val id=requestIds.incrementAndGet()
        check(states.putIfAbsent(id,AtomicInteger(ACTIVE))==null)
        return id
    }
    /** True only when cancellation wins before completion; false means the result already completed/unknown. */
    fun cancel(requestId: Long): Boolean {
        val state=states[requestId] ?: return false
        if(!state.compareAndSet(ACTIVE,CANCELLED)) return false
        NativeVoice.cancel(requestId)
        return true
    }

    fun transcribe(pcm: FloatArray, language: String, threads: Int, requestId: Long): VoiceResult {
        require(language == "auto" || language.matches(Regex("[a-z]{2,3}")))
        val state=states[requestId] ?: error("VOICE_INVALID_REQUEST")
        try {
            if (state.get()!=ACTIVE) error("VOICE_CANCELLED")
            val model = ensureModel()
            if (state.get()!=ACTIVE) error("VOICE_CANCELLED")
            val json = JSONObject(NativeVoice.transcribe(model.absolutePath, pcm, language, threads.coerceIn(1, 8), requestId)
                .toString(Charsets.UTF_8))
            val result = VoiceResult(json.getString("text").trim(), json.getString("language"), json.getLong("elapsedMs"),
                json.optDouble("noSpeechProbability", 0.0).toFloat())
            if (!state.compareAndSet(ACTIVE, COMPLETED)) error("VOICE_CANCELLED")
            return result
        } finally {
            states.remove(requestId,state)
            NativeVoice.forget(requestId)
        }
    }

    private fun ensureModel(): File = synchronized(this) {
        verifiedModel?.takeIf { it.isFile }?.let { return it }
        val directory = File(context.noBackupFilesDir, "voice").apply { mkdirs() }
        val target = File(directory, MODEL_NAME)
        if (!isValid(target)) {
            target.delete()
            val temporary = File(directory, "$MODEL_NAME.part").apply { delete() }
            try {
                context.assets.open("models/$MODEL_NAME").use { source ->
                    temporary.outputStream().buffered().use { output -> source.copyTo(output, 1024 * 1024) }
                }
                if (!isValid(temporary)) error("VOICE_MODEL_CHECKSUM")
                check(temporary.renameTo(target)) { "VOICE_MODEL_INSTALL" }
            } finally { temporary.delete() }
        }
        verifiedModel = target
        target
    }

    private fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() != MODEL_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val block = ByteArray(1024 * 1024)
            while (true) { val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == MODEL_SHA256
    }

    companion object {
        const val MODEL_NAME = "ggml-base-q5_1.bin"
        const val MODEL_BYTES = 59_707_625L
        const val MODEL_SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        private val requestIds = AtomicLong(System.currentTimeMillis().coerceAtLeast(1))
        private val states = ConcurrentHashMap<Long,AtomicInteger>()
        private const val ACTIVE=0
        private const val CANCELLED=1
        private const val COMPLETED=2
    }
}

data class VoiceResult(val text: String, val language: String, val elapsedMs: Long, val noSpeechProbability: Float)

internal object NativeVoice {
    init { System.loadLibrary("mtmux_voice") }
    external fun transcribe(modelPath: String, pcm: FloatArray, language: String, threads: Int, requestId: Long): ByteArray
    external fun probe(modelPath:String):ByteArray
    external fun cancel(requestId: Long): Boolean
    external fun forget(requestId: Long)
}

fun recognitionLanguage(automatic: Boolean, interfaceLanguage: String): String =
    if (automatic) "auto" else if (interfaceLanguage.lowercase().startsWith("zh")) "zh" else "en"

fun hasAudibleSpeech(pcm:FloatArray):Boolean {
    if(pcm.size<VoiceRecorder.SAMPLE_RATE/10) return false
    var sum=0.0
    for(sample in pcm){val value=sample.toDouble();sum+=value*value}
    return kotlin.math.sqrt(sum/pcm.size)>=0.001 // conservative -60 dBFS pure-silence/hardware-noise gate
}
