package dev.mtmux.core

import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.Test

class ConnectionCancellationTest {
    @Test fun `cancelling a peer stalled before its banner interrupts connect promptly`() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val accepted = AtomicReference<java.net.Socket>()
            val peerClosed = CountDownLatch(1)
            val peer = thread(isDaemon = true) {
                runCatching { server.accept().use { socket ->
                    accepted.set(socket)
                    while (socket.getInputStream().read() >= 0) { }
                } }
                peerClosed.countDown()
            }
            val traces = CopyOnWriteArrayList<ConnectionTrace>()
            val banner = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val client = SshClient(object : PinStore { override fun get(endpoint: String): String? = null })
            client.observe { traces += it; if (it.phase == ConnectionPhase.SERVER_BANNER && it.result == TraceResult.START) banner.countDown() }
            val connecting = thread(isDaemon = true) { runCatching { client.connect(Login("127.0.0.1", server.localPort, "fixture", "")) }.onFailure { failure.set(it) } }
            try {
                assertTrue(banner.await(3, TimeUnit.SECONDS))
                val start = System.nanoTime()
                client.cancel()
                connecting.join(2_000)
                assertFalse(connecting.isAlive, "cancel must not wait for the 15-second SSH timeout")
                assertTrue((System.nanoTime() - start) < TimeUnit.SECONDS.toNanos(2))
                println("CANCEL_STALLED_BANNER elapsedMs=${(System.nanoTime() - start)/1_000_000}")
                assertEquals(ErrorCode.CANCELLED, (failure.get() as CodedError).code)
                assertTrue(peerClosed.await(2, TimeUnit.SECONDS))
                assertTrue(traces.any { it.phase == ConnectionPhase.SERVER_BANNER && it.result == TraceResult.CANCELLED })
                assertTrue(traces.any { it.phase == ConnectionPhase.SSH_CONNECT && it.result == TraceResult.CANCELLED })
            } finally { client.close(); accepted.get()?.close(); server.close(); connecting.join(1000); peer.join(1000) }
        }
    }

    @Test fun `cancel before connect cannot be undone by starting the attempt`() {
        SshClient(object : PinStore { override fun get(endpoint: String): String? = null }).use { client ->
            client.cancel()
            assertEquals(ErrorCode.CANCELLED, assertFailsWith<MtmuxException> { client.connect(Login("127.0.0.1", 1, "fixture", "")) }.code)
        }
    }
}
