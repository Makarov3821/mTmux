package dev.mtmux.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VoiceRecorder : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var future: Future<ByteArray>? = null

    @SuppressLint("MissingPermission") // voice flavor UI requests RECORD_AUDIO before calling.
    @Synchronized fun start(onLimit: () -> Unit) {
        if (future?.isDone == true) future = null
        check(!running.get() && future == null && recorder == null) { "VOICE_ALREADY_RECORDING" }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        check(minimum > 0) { "VOICE_AUDIO_UNAVAILABLE" }
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minimum * 2, 8192))
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "VOICE_AUDIO_UNAVAILABLE" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "VOICE_AUDIO_UNAVAILABLE" }
            running.set(true)
            recorder = audio
            future = executor.submit<ByteArray> {
                try {
                    val result = ByteArrayOutputStream(MAX_BYTES)
                    val buffer = ByteArray(8192)
                    while (running.get() && result.size() < MAX_BYTES) {
                        val wanted = minOf(buffer.size, MAX_BYTES - result.size())
                        val count = audio.read(buffer, 0, wanted, AudioRecord.READ_BLOCKING)
                        when {
                            count > 0 -> result.write(buffer, 0, count)
                            count == 0 || !running.get() -> Unit
                            else -> error("VOICE_AUDIO_READ_$count")
                        }
                    }
                    if (result.size() >= MAX_BYTES && running.getAndSet(false)) Handler(Looper.getMainLooper()).post(onLimit)
                    result.toByteArray()
                } finally {
                    running.set(false)
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { audio.stop() }
                    audio.release()
                    synchronized(this) { if (recorder === audio) recorder = null }
                }
            }
        } catch (error: Throwable) {
            running.set(false); recorder = null; future = null
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { audio.stop() }
            audio.release()
            throw error
        }
    }

    fun stop(): FloatArray {
        running.set(false)
        val audio = recorder
        if (audio?.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { audio.stop() }
        val job = future ?: return FloatArray(0)
        val bytes = try { job.get(3, TimeUnit.SECONDS) }
        finally { if (job.isDone) synchronized(this) { if (future === job) future = null } }
        require(bytes.size >= SAMPLE_RATE / 10 * 2) { "VOICE_TOO_SHORT" }
        val even = bytes.size - bytes.size % 2
        return FloatArray(even / 2) { index ->
            val low = bytes[index * 2].toInt() and 255
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8 or low).toShort() / 32768.0f)
        }
    }

    /** Non-blocking: stop() unblocks read; the worker owns and releases AudioRecord. */
    fun cancel() {
        running.set(false)
        recorder?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }?.let { runCatching { it.stop() } }
    }

    fun isRecording() = running.get()
    override fun close() { cancel(); future?.cancel(true); executor.shutdownNow() }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 60
        private const val MAX_BYTES = SAMPLE_RATE * MAX_SECONDS * 2
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
