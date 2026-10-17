package dev.mtmux

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mtmux.voice.VoiceRecorder
import dev.mtmux.voice.WhisperTranscriber
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VoiceProductTest {
    @Test fun bundledBaseModelExtractsAndTranscribesPinnedWav() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        File(context.noBackupFilesDir,"voice").deleteRecursively()
        val pcm=wav(instrumentation.context.assets.open("jfk.wav").readBytes())
        val engine=WhisperTranscriber(context)
        val result=engine.transcribe(pcm,"en",4,engine.newRequestId())
        assertTrue(result.text,result.text.contains("ask not what your country can do for you",ignoreCase=true))
        assertEquals("en",result.language)
        val model=File(context.noBackupFilesDir,"voice/${WhisperTranscriber.MODEL_NAME}")
        assertEquals(WhisperTranscriber.MODEL_BYTES,model.length())
        assertEquals(WhisperTranscriber.MODEL_SHA256,sha256(model))
        assertFalse(File(model.parentFile,"${WhisperTranscriber.MODEL_NAME}.part").exists())
    }

    @Test fun cancellationBeforeInferenceNeverReturnsText() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val pcm=wav(instrumentation.context.assets.open("jfk.wav").readBytes())
        val engine=WhisperTranscriber(instrumentation.targetContext)
        val id=engine.newRequestId();engine.cancel(id)
        val error=runCatching {engine.transcribe(pcm,"en",2,id)}.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty(),error.message.orEmpty().contains("VOICE_CANCELLED"))
    }

    @Test fun cancellationDuringInferenceStopsNativeWork() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val pcm=wav(instrumentation.context.assets.open("jfk.wav").readBytes())
        val engine=WhisperTranscriber(instrumentation.targetContext)
        engine.prepareModel()
        val id=engine.newRequestId();val worker=Executors.newSingleThreadExecutor()
        try {
            val result=worker.submit<Throwable?> {runCatching {engine.transcribe(pcm,"en",4,id)}.exceptionOrNull()}
            Thread.sleep(1000)
            val started=System.nanoTime();assertTrue("native request was not registered",engine.cancel(id))
            val error=result.get(20,TimeUnit.SECONDS)
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty(),error.message.orEmpty().contains("VOICE_CANCELLED"))
            assertTrue("native cancellation took too long",System.nanoTime()-started<TimeUnit.SECONDS.toNanos(8))
        } finally {worker.shutdownNow()}
    }

    @Test fun audioRecordCapturesOrFailsCleanlyAndReleases() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.RECORD_AUDIO").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        instrumentation.waitForIdleSync()
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(instrumentation.targetContext,Manifest.permission.RECORD_AUDIO))
        val recorder=VoiceRecorder()
        try {
            val started=runCatching {recorder.start { }}
            if(started.isFailure) {
                assertTrue(started.exceptionOrNull()!!.message.orEmpty().contains("VOICE_AUDIO_UNAVAILABLE"))
                assertFalse(recorder.isRecording())
                val retry=runCatching {recorder.start { }}
                assertTrue("failed start must not wedge recorder",retry.isFailure)
                assertFalse(recorder.isRecording())
            } else {
                Thread.sleep(350)
                val pcm=recorder.stop()
                assertTrue("expected at least 0.1 seconds, got ${pcm.size}",pcm.size>=1600)
                assertTrue(pcm.size<=VoiceRecorder.SAMPLE_RATE*VoiceRecorder.MAX_SECONDS)
                assertFalse(recorder.isRecording())
            }
        } finally {recorder.close()}
    }

    private fun wav(bytes:ByteArray):FloatArray {
        fun u32(at:Int)= (bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8) or
            ((bytes[at+2].toInt() and 255) shl 16) or ((bytes[at+3].toInt() and 255) shl 24)
        var at=12
        while(at+8<=bytes.size) {
            val name=String(bytes,at,4,Charsets.US_ASCII);val size=u32(at+4);val data=at+8
            if(name=="data") return FloatArray(size/2) {i ->
                val low=bytes[data+i*2].toInt() and 255;val high=bytes[data+i*2+1].toInt()
                ((high shl 8 or low).toShort()/32768f)
            }
            at=data+size+(size and 1)
        }
        error("test WAV has no data")
    }
    private fun sha256(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input ->
            val block=ByteArray(1024*1024);while(true){val n=input.read(block);if(n<0)break;digest.update(block,0,n)}
        }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
