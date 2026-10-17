package dev.mtmux.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Exclusive ownership transfer, bounded foreground lifetime, and automatic idle expiry. */
class WarmConnectionPool<T : AutoCloseable>(private val maxSize: Int = 2, private val ttlMs: Long = 20_000,
    private val isReusable: (T) -> Boolean, private val clock: () -> Long = System::nanoTime) : AutoCloseable {
    private data class Entry<T>(val value: T, val expires: Long, var timer: ScheduledFuture<*>? = null)
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry<T>>()
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "mtmux-idle").apply { isDaemon = true } }
    private var closed = false
    private var active = true
    init { require(maxSize > 0 && ttlMs > 0) }

    fun take(key: String): T? {
        val entry = synchronized(lock) {
            if (closed || !active) null else entries.remove(key)?.also { it.timer?.cancel(false) }
        } ?: return null
        if (clock() >= entry.expires || !isReusable(entry.value)) { dispose(entry.value); return null }
        return entry.value
    }
    fun put(key: String, value: T) {
        val discarded = mutableListOf<T>()
        synchronized(lock) {
            if (closed || !active || !isReusable(value)) discarded += value
            else {
                entries.remove(key)?.let { it.timer?.cancel(false); discarded += it.value }
                while (entries.size >= maxSize) {
                    val oldest = entries.entries.first()
                    entries.remove(oldest.key); oldest.value.timer?.cancel(false); discarded += oldest.value.value
                }
                val entry = Entry(value, clock() + TimeUnit.MILLISECONDS.toNanos(ttlMs))
                entries[key] = entry
                entry.timer = timer.schedule({
                    val expired = synchronized(lock) {
                        if (entries[key] === entry) entries.remove(key)?.value else null
                    }
                    expired?.let(::dispose)
                }, ttlMs, TimeUnit.MILLISECONDS)
            }
        }
        discarded.forEach(::dispose)
    }
    fun clear() {
        val removed = synchronized(lock) {
            entries.values.toList().also { entries.clear(); it.forEach { entry -> entry.timer?.cancel(false) } }
        }
        removed.forEach { dispose(it.value) }
    }
    fun setActive(enabled: Boolean) {
        synchronized(lock) { active = enabled && !closed }
        if (!enabled) clear()
    }
    private fun dispose(value: T) { runCatching { value.close() } }
    override fun close() { synchronized(lock) { closed = true }; clear(); timer.shutdownNow() }
}
