package dev.mtmux.core

import com.jcraft.jsch.Logger
import org.junit.Test
import kotlin.test.*

class ConnectionTraceTest {
    @Test fun `elapsed durations are monotonic end once and preserve parent operation`() {
        var clock = 1_000_000L
        val events = mutableListOf<ConnectionTrace>()
        val span = TraceSpan(7, ConnectionPhase.AUTH, 2, { events += it }, { clock }, parent = 3)
        clock += 23_000_000
        span.end(); span.end(TraceResult.FAILED)
        assertEquals(listOf(TraceResult.START, TraceResult.OK), events.map { it.result })
        assertEquals(23L, events.last().elapsedMs)
        assertTrue(events.all { it.parent == 3L && it.hop == 2 })
    }
    @Test fun `handshake observations expose fixed phases without retaining library text`() {
        val events = mutableListOf<ConnectionTrace>()
        val source = TraceSource().apply { observer = { events += it } }
        val logger = HandshakeTrace(source, 1)
        logger.log(Logger.INFO, "Connecting to SECRET_HOST port 22")
        logger.log(Logger.INFO, "Connection established")
        logger.log(Logger.INFO, "Remote version string: SECRET_SERVER_TEXT")
        logger.log(Logger.INFO, "SSH_MSG_NEWKEYS received")
        logger.log(Logger.INFO, "Authentication succeeded (SECRET_AUTH_TEXT).")
        logger.finish(TraceResult.OK)
        logger.log(Logger.INFO, "Connection established")
        assertEquals(listOf(ConnectionPhase.SERVER_BANNER, ConnectionPhase.KEY_EXCHANGE, ConnectionPhase.AUTH),
            events.filter { it.result == TraceResult.START }.map { it.phase })
        assertEquals(6, events.size)
        assertFalse(events.toString().contains("SECRET"))
    }
}
