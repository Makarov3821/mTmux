package dev.mtmux.voice

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WhisperTranscriber(private val context: Context) {
    private val model = BundledVoiceAsset(MODEL_NAME, MODEL_BYTES, MODEL_SHA256)

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

    /** Call after the inference block finishes, including cancellation before the block started. */
    fun releaseRequest(requestId: Long) {
        if (states.remove(requestId) != null) NativeVoice.forget(requestId)
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

    private fun ensureModel() = model.file(context)

    companion object {
        const val MODEL_NAME = BuildConfig.WHISPER_MODEL_NAME
        const val MODEL_BYTES = BuildConfig.WHISPER_MODEL_BYTES
        const val MODEL_SHA256 = BuildConfig.WHISPER_MODEL_SHA256
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
