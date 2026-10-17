package dev.mtmux.core

import com.jcraft.jsch.Logger
import com.jcraft.jsch.SocketFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Own sockets before connect(), so cancellation can interrupt TCP and SSH reads immediately. */
internal class ConnectingSocketFactory(
    private val trace: TraceSource, private val hop: Int,
    private val checkActive: () -> Unit,
    private val ownSocket: (Socket) -> Unit,
    private val ownLookup: (Future<*>) -> Unit,
    private val releaseLookup: (Future<*>) -> Unit
) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val dns = trace.span(ConnectionPhase.DNS, hop)
        val addresses = try {
            checkActive()
            val lookup = resolver.submit<Array<InetAddress>> { InetAddress.getAllByName(host) }
            try {
                ownLookup(lookup)
                resolve(lookup, deadline)
            }
            finally { lookup.cancel(true); (lookup as? Runnable)?.let { resolver.remove(it) }; releaseLookup(lookup) }
        } catch (error: Exception) {
            dns.end(result(error), error = error); throw error
        }
        dns.end()
        val tcp = trace.span(ConnectionPhase.TCP_CONNECT, hop)
        try {
            var failure: Exception? = null
            for (address in addresses) {
                checkActive()
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) throw SocketTimeoutException()
                val socket = Socket()
                ownSocket(socket)
                try {
                    socket.connect(InetSocketAddress(address, port), remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1))
                    checkActive()
                    tcp.end()
                    return socket
                } catch (error: Exception) { socket.close(); failure = error; checkActive() }
            }
            throw failure ?: java.net.UnknownHostException()
        } catch (error: Exception) { tcp.end(result(error), error = error); throw error }
    }

    private fun result(error: Exception): TraceResult {
        try { checkActive() } catch (_: Exception) { return TraceResult.CANCELLED }
        return if (error is CodedError && error.code == ErrorCode.CANCELLED) TraceResult.CANCELLED else TraceResult.FAILED
    }
    override fun getInputStream(socket: Socket) = socket.getInputStream()
    override fun getOutputStream(socket: Socket) = socket.getOutputStream()

    // Keep DNS workers bounded even when a platform resolver cannot interrupt an in-flight lookup.
    companion object {
        private val resolver = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
            { task -> Thread(task, "mtmux-dns").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    }
    private fun resolve(lookup: Future<Array<InetAddress>>, deadline: Long): Array<InetAddress> {
        while (true) {
            checkActive()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException()
            try { return lookup.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS) }
            catch (_: TimeoutException) { continue }
        }
    }
}

/** Interpret only known library milestones; discard every raw library message. */
internal class HandshakeTrace(private val trace: TraceSource, private val hop: Int) : Logger {
    private var active: TraceSpan? = null
    private var ready = false
    override fun isEnabled(level: Int) = !ready && level == Logger.INFO
    @Synchronized override fun log(level: Int, message: String) {
        if (ready) return
        val next = when {
            message == "Connection established" -> ConnectionPhase.SERVER_BANNER
            message.startsWith("Remote version string: ") -> ConnectionPhase.KEY_EXCHANGE
            message == "SSH_MSG_NEWKEYS received" -> ConnectionPhase.AUTH
            else -> return
        }
        active?.end()
        active = trace.span(next, hop)
    }
    @Synchronized fun finish(result: TraceResult, error: Throwable? = null) {
        ready = true
        active?.end(result, error = error)
    }
}
