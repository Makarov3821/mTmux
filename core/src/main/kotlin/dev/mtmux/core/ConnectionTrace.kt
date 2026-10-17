package dev.mtmux.core

import java.util.concurrent.atomic.AtomicLong

/** Fixed vocabulary only. No hosts, commands, credentials or terminal output. */
enum class ConnectionPhase {
    SSH_CONNECT, KEY_LOAD, JUMP_FORWARD, DNS, TCP_CONNECT, SERVER_BANNER, KEY_EXCHANGE, AUTH,
    SSH_READY, SSH_REUSED, TMUX_CATALOG, TMUX_PREPARE, TMUX_STATUS, TMUX_VERIFY,
    REMOTE_EXEC, CHANNEL_OPEN, COMMAND_WAIT, TERMINAL_OPEN, FIRST_BYTE, CANCEL_REQUEST
}
enum class TraceResult { START, OK, FAILED, CANCELLED }
data class ConnectionTrace(val operation: Long, val phase: ConnectionPhase, val result: TraceResult,
                           val elapsedMs: Long, val hop: Int, val bytes: Int = 0,
                           val reason: FailureReason? = null, val parent: Long = 0, val exitStatus: Int? = null)

internal class TraceSpan(val operation: Long, private val phase: ConnectionPhase,
                         private val hop: Int, private val report: (ConnectionTrace) -> Unit,
                         private val clock: () -> Long = System::nanoTime, private val parent: Long = 0) {
    private val started = clock()
    private var ended = false
    init { report(ConnectionTrace(operation, phase, TraceResult.START, 0, hop, parent = parent)) }
    @Synchronized fun end(result: TraceResult = TraceResult.OK, bytes: Int = 0, error: Throwable? = null, exitStatus: Int? = null) {
        if (ended) return
        ended = true
        report(ConnectionTrace(operation, phase, result, (clock() - started).coerceAtLeast(0) / 1_000_000,
            hop, bytes, error?.let(::connectionFailureReason), parent, exitStatus))
    }
}

internal class TraceSource {
    @Volatile var observer: (ConnectionTrace) -> Unit = {}
    companion object { private val sequence = AtomicLong() }
    private val parent = ThreadLocal<Long>()
    fun report(event: ConnectionTrace) { runCatching { observer(event) } }
    fun span(phase: ConnectionPhase, hop: Int = 0) = TraceSpan(sequence.incrementAndGet(), phase, hop, ::report, parent = parent.get() ?: 0)
    fun <T> within(span: TraceSpan, block: () -> T): T {
        val previous = parent.get()
        parent.set(span.operation)
        try { return block() } finally { if (previous == null) parent.remove() else parent.set(previous) }
    }
    fun instant(phase: ConnectionPhase) = report(ConnectionTrace(sequence.incrementAndGet(), phase, TraceResult.OK, 0, 0))
}
