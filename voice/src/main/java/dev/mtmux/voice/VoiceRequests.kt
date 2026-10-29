package dev.mtmux.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Cancellation and completion race on one atomic transition; late results cannot escape. */
internal class VoiceRequests {
    private val states = ConcurrentHashMap<Long, AtomicInteger>()
    fun create(): Long = ids.incrementAndGet().also { states[it] = AtomicInteger(ACTIVE) }
    fun cancel(id: Long): Boolean = states[id]?.compareAndSet(ACTIVE, CANCELLED) == true
    fun check(id: Long) {
        checkNotNull(states[id]) { "VOICE_INVALID_REQUEST" }.let {
            if (it.get() == CANCELLED) throw CancellationException("VOICE_CANCELLED")
            check(it.get() == ACTIVE) { "VOICE_INVALID_REQUEST" }
        }
    }
    fun complete(id: Long) {
        if (states[id]?.compareAndSet(ACTIVE, COMPLETED) != true) {
            throw CancellationException("VOICE_CANCELLED")
        }
    }
    fun release(id: Long) { states.remove(id) }
    companion object {
        private val ids = AtomicLong()
        private const val ACTIVE = 0
        private const val CANCELLED = 1
        private const val COMPLETED = 2
    }
}
