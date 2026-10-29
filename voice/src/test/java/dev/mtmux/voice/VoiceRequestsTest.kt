package dev.mtmux.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class VoiceRequestsTest {
    @Test fun cancellationBeforeDecodeCannotComplete() {
        val requests = VoiceRequests()
        val id = requests.create()
        assertTrue(requests.cancel(id))
        assertFalse(requests.cancel(id))
        assertThrows(CancellationException::class.java) { requests.check(id) }
        assertThrows(CancellationException::class.java) { requests.complete(id) }
        requests.release(id)
        assertFalse(requests.cancel(id))
    }

    @Test fun completionAndCancellationHaveExactlyOneWinner() {
        val requests = VoiceRequests()
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val id = requests.create()
                val ready = CountDownLatch(1)
                val cancelled = workers.submit<Boolean> { ready.await(); requests.cancel(id) }
                val completed = workers.submit<Boolean> {
                    ready.await()
                    try { requests.complete(id); true } catch (_: CancellationException) { false }
                }
                ready.countDown()
                assertNotEquals(cancelled.get(), completed.get())
                requests.release(id)
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun cancellingOldRequestDoesNotAffectNewOne() {
        val requests = VoiceRequests()
        val old = requests.create(); requests.cancel(old); requests.release(old)
        val current = requests.create()
        assertNotEquals(old, current)
        assertFalse(requests.cancel(old))
        requests.check(current); requests.complete(current)
        assertFalse(requests.cancel(current))
        requests.release(current)
    }

    @Test fun silenceGateDoesNotSubmitEmptyOrHardwareNoise() {
        assertFalse(hasAudibleSpeech(FloatArray(16000)))
        assertFalse(hasAudibleSpeech(FloatArray(16000) { 0.0001f }))
        assertFalse(hasAudibleSpeech(FloatArray(100) { 0.2f }))
        assertTrue(hasAudibleSpeech(FloatArray(16000) { if (it % 2 == 0) 0.02f else -0.02f }))
    }
}
