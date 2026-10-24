package dev.mtmux

import org.junit.Assert.*
import org.junit.Test

class VoiceHoldStateTest {
    @Test fun countdownStartsOnlyWhenMicrophoneIsReady() {
        val state = VoiceHoldState()
        val id = state.begin()!!
        assertEquals(VoicePhase.STARTING, state.phase)
        assertEquals(60_000L, state.remainingMillis(8_000))
        assertTrue(state.ready(id, 8_000))
        assertEquals(VoicePhase.RECORDING, state.phase)
        assertEquals(60_000L, state.remainingMillis(8_000))
        assertEquals(10_000L, state.remainingMillis(58_000))
        assertEquals(5_000L, state.remainingMillis(63_000))
        assertEquals(0L, state.remainingMillis(90_000))
    }

    @Test fun releaseWhilePreparingRejectsLateStartAndAllowsNextHold() {
        val state = VoiceHoldState()
        val first = state.begin()!!
        assertEquals(VoiceRelease.CANCEL, state.release(first))
        assertEquals(VoicePhase.IDLE, state.phase)
        val next = state.begin()!!
        assertFalse(state.ready(first, 1_000))
        assertTrue(state.ready(next, 2_000))
        assertEquals(VoiceRelease.NONE, state.release(first))
        assertEquals(VoicePhase.RECORDING, state.phase)
    }

    @Test fun releaseTranscribesOnceAndLateDeadlineDoesNothing() {
        val state = VoiceHoldState()
        val id = state.begin()!!
        state.ready(id, 100)
        assertEquals(VoiceRelease.TRANSCRIBE, state.release(id))
        assertEquals(VoiceRelease.NONE, state.release(id))
        assertEquals(VoiceRelease.NONE, state.finish(id))
        assertEquals(VoicePhase.TRANSCRIBING, state.phase)
    }

    @Test fun upwardCancellationDiscardsRecordingAndLateResult() {
        val state = VoiceHoldState()
        val id = state.begin()!!
        state.ready(id, 100)
        state.move(id, true)
        assertTrue(state.cancelArmed)
        assertEquals(VoiceRelease.CANCEL, state.release(id))
        state.result(id)
        assertEquals(VoicePhase.IDLE, state.phase)
    }

    @Test fun movingBackResumesNormalReleaseAndDeadlineStopsExactlyOnce() {
        val state = VoiceHoldState()
        val id = state.begin()!!
        state.ready(id, 100)
        state.move(id, true)
        state.move(id, false)
        assertFalse(state.cancelArmed)
        assertEquals(VoiceRelease.TRANSCRIBE, state.finish(id))
        assertEquals(VoiceRelease.NONE, state.release(id))
        assertNull(state.begin())
    }

    @Test fun cancellationAndTargetChangeInvalidateOldCompletions() {
        val state = VoiceHoldState()
        val old = state.begin()!!
        state.ready(old, 100)
        state.release(old)
        state.cancel()
        val current = state.begin()!!
        state.result(old)
        state.error(old)
        state.complete(old)
        assertEquals(VoicePhase.STARTING, state.phase)
        assertTrue(state.ready(current, 200))
    }

    @Test fun accessibilityCanStartAndStopWithoutKeepingFingerDown() {
        val state = VoiceHoldState()
        val id = state.begin(accessibility = true)!!
        assertTrue(state.accessibilityHold)
        assertTrue(state.ready(id, 100))
        assertEquals(VoiceRelease.NONE, state.release(id))
        assertEquals(VoiceRelease.TRANSCRIBE, state.finish(id))
        state.complete(id)
        assertEquals(VoicePhase.IDLE, state.phase)
        assertFalse(state.accessibilityHold)
    }
}
