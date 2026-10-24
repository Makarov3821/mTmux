package dev.mtmux

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class VoicePhase { IDLE, STARTING, RECORDING, TRANSCRIBING, RESULT, ERROR }
internal enum class VoiceRelease { NONE, CANCEL, TRANSCRIBE }

/** One recording owns its press, microphone-ready callback, deadline and transcription result. */
@Stable internal class VoiceHoldState {
    var phase by mutableStateOf(VoicePhase.IDLE)
        private set
    var generation by mutableLongStateOf(0L)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    var cancelArmed by mutableStateOf(false)
        private set
    var accessibilityHold by mutableStateOf(false)
        private set
    private var pointerDown = false

    fun owns(id: Long) = id == generation

    fun begin(accessibility: Boolean = false): Long? {
        if (phase != VoicePhase.IDLE) return null
        generation++
        phase = VoicePhase.STARTING
        pointerDown = !accessibility
        accessibilityHold = accessibility
        cancelArmed = false
        startedAt = 0L
        return generation
    }

    fun ready(id: Long, now: Long): Boolean {
        if (!owns(id) || phase != VoicePhase.STARTING || !(pointerDown || accessibilityHold)) return false
        startedAt = now
        phase = VoicePhase.RECORDING
        return true
    }

    fun move(id: Long, cancel: Boolean) {
        if (owns(id) && pointerDown && phase in listOf(VoicePhase.STARTING, VoicePhase.RECORDING)) cancelArmed = cancel
    }

    fun release(id: Long): VoiceRelease {
        if (!owns(id) || !pointerDown) return VoiceRelease.NONE
        pointerDown = false
        return finish(id)
    }

    /** Also used by the time limit and the accessible tap-to-start/tap-to-stop action. */
    fun finish(id: Long): VoiceRelease {
        if (!owns(id) || phase !in listOf(VoicePhase.STARTING, VoicePhase.RECORDING)) return VoiceRelease.NONE
        if (phase == VoicePhase.STARTING || cancelArmed) {
            cancel()
            return VoiceRelease.CANCEL
        }
        pointerDown = false
        accessibilityHold = false
        cancelArmed = false
        phase = VoicePhase.TRANSCRIBING
        return VoiceRelease.TRANSCRIBE
    }

    fun cancel() {
        generation++
        pointerDown = false
        accessibilityHold = false
        cancelArmed = false
        startedAt = 0L
        phase = VoicePhase.IDLE
    }

    fun complete(id: Long) = transition(id, VoicePhase.IDLE)
    fun result(id: Long) = transition(id, VoicePhase.RESULT)
    fun error(id: Long) = transition(id, VoicePhase.ERROR)

    private fun transition(id: Long, next: VoicePhase) {
        if (!owns(id)) return
        pointerDown = false
        accessibilityHold = false
        cancelArmed = false
        phase = next
    }

    fun remainingMillis(now: Long): Long =
        if (phase == VoicePhase.RECORDING) (LIMIT_MILLIS - (now - startedAt).coerceAtLeast(0)).coerceAtLeast(0)
        else LIMIT_MILLIS

    companion object { const val LIMIT_MILLIS = 60_000L }
}
