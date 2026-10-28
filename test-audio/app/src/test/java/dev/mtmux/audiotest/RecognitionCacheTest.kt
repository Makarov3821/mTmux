package dev.mtmux.audiotest

import org.junit.Assert.*
import org.junit.Test

class RecognitionCacheTest {
    @Test fun punctuationVariantsGetExactlyTheSameRecognition() {
        val run = RecognitionCache<String>(); var calls = 0
        val ct = run.getOrRun("paraformer:zh", "paraformer-ct") { calls++; "不要删除原有配置" }
        val srf = run.getOrRun("paraformer:zh", "paraformer-srf") { calls++; "另一次识别的不同结果" }
        assertEquals(1, calls); assertEquals(ct.value, srf.value)
        assertFalse(ct.reused); assertTrue(srf.reused); assertEquals("paraformer-ct", srf.origin)
    }
    @Test fun eachRecordingAndReplayStartsAFreshRecognition() {
        var calls = 0
        repeat(2) { RecognitionCache<String>().getOrRun("paraformer:zh", "ct") { calls++; "录音 $it" } }
        assertEquals(2, calls)
    }
    @Test fun anotherModelOrLanguageCannotReuseRecognition() {
        val run = RecognitionCache<String>(); var calls = 0
        for (key in listOf("paraformer:zh", "x-asr:zh", "paraformer:en")) run.getOrRun(key, key) { calls++; key }
        assertEquals(3, calls)
    }
    @Test fun failedRecognitionDoesNotPoisonOtherVariants() {
        val run = RecognitionCache<String>()
        assertThrows(IllegalStateException::class.java) { run.getOrRun("paraformer:zh", "ct") { error("model failure") } }
        val retry = run.getOrRun("paraformer:zh", "srf") { "识别成功" }
        assertFalse(retry.reused); assertEquals("识别成功", retry.value); assertEquals("srf", retry.origin)
    }
}
