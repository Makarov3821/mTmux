package dev.mtmux

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** First input starts immediately. Input queued during an in-flight write is coalesced by target. */
internal class TerminalWriteQueue<T>(private val executor: Executor,
    private val write: (T, ByteArray, (() -> Unit)?) -> Unit) {
    private data class Item<T>(val target: T, val bytes: ByteArray, val done: (() -> Unit)?)
    private val pending = ArrayDeque<Item<T>>()
    private var scheduled = false
    private var bytes = 0
    @Synchronized fun clear() { pending.clear(); bytes = 0 }
    @Synchronized fun clearMatching(matches: (T) -> Boolean) {
        pending.removeAll { matches(it.target) }
        bytes = pending.sumOf { it.bytes.size }
    }
    @Synchronized fun offer(target: T, data: ByteArray, done: (() -> Unit)? = null) {
        if (bytes + data.size > 65536) throw RejectedExecutionException()
        val last = pending.lastOrNull()
        if (done == null && last?.target == target && last.done == null && last.bytes.size + data.size <= 8192) {
            pending.removeLast(); pending.addLast(Item(target, last.bytes + data, null))
        } else {
            if (pending.size >= 64) throw RejectedExecutionException()
            pending.addLast(Item(target, data.copyOf(), done))
        }
        bytes += data.size
        if (!scheduled) {
            scheduled = true
            try { executor.execute(::drain) }
            catch (error: RejectedExecutionException) { scheduled = false; clear(); throw error }
        }
    }
    private fun drain() {
        while (true) {
            val next = synchronized(this) {
                if (pending.isEmpty()) { scheduled = false; return }
                val first = pending.removeFirst(); bytes -= first.bytes.size
                if (first.done != null) first else {
                    val payload = ByteArrayOutputStream().also { it.write(first.bytes) }
                    while (pending.firstOrNull()?.let { it.target == first.target && it.done == null && payload.size() + it.bytes.size <= 8192 } == true) {
                        val item = pending.removeFirst(); bytes -= item.bytes.size; payload.write(item.bytes)
                    }
                    Item(first.target, payload.toByteArray(), null)
                }
            }
            try { write(next.target, next.bytes, next.done) }
            catch (error: Exception) {
                synchronized(this) { clear(); scheduled = false }
                throw error
            }
        }
    }
}
