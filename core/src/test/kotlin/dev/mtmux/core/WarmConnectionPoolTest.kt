package dev.mtmux.core

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WarmConnectionPoolTest {
    private class Connection : AutoCloseable {
        var closed = false
        val done = CountDownLatch(1)
        override fun close() { closed = true; done.countDown() }
    }
    @Test fun `ownership expiry capacity and background invalidation are enforced`() {
        var now = 0L
        WarmConnectionPool<Connection>(maxSize = 2, ttlMs = 1_000, isReusable = { !it.closed }, clock = { now }).use { pool ->
            val first = Connection(); val second = Connection(); val third = Connection()
            pool.put("a", first); pool.put("b", second); pool.put("c", third)
            assertTrue(first.closed)
            assertNull(pool.take("changed-credentials-or-pin"))
            assertSame(second, pool.take("b")); assertNull(pool.take("b")); assertFalse(second.closed)
            now = TimeUnit.SECONDS.toNanos(1)
            assertNull(pool.take("c")); assertTrue(third.closed)
            pool.put("b", second); pool.setActive(false); assertTrue(second.closed)
            val late = Connection(); pool.put("late-refresh-completion", late); assertTrue(late.closed)
            pool.setActive(true)
            val current = Connection(); pool.put("current", current); assertSame(current, pool.take("current")); current.close()
        }
    }
    @Test fun `idle timer closes unused connections without a later borrow`() {
        WarmConnectionPool<Connection>(ttlMs = 30, isReusable = { !it.closed }).use { pool ->
            val connection = Connection(); pool.put("a", connection)
            assertTrue(connection.done.await(2, TimeUnit.SECONDS))
            assertNull(pool.take("a"))
        }
    }
    @Test fun `retention levels shrink immediately and none rejects late completions`() {
        assertEquals(listOf(0, 1, 2, 3, 5), SshRetention.entries.map { it.capacity })
        assertEquals(SshRetention.MEDIUM, SshRetention.fromCapacity(-1))
        WarmConnectionPool<Connection>(maxSize = 5, isReusable = { !it.closed }).use { pool ->
            val connections = List(5) { Connection().also { value -> pool.put("$it", value) } }
            pool.setCapacity(2)
            assertTrue(connections.take(3).all { it.closed })
            assertFalse(connections[3].closed)
            pool.setCapacity(0)
            assertTrue(connections.all { it.closed })
            val late = Connection(); pool.put("late", late)
            assertTrue(late.closed); assertNull(pool.take("late"))
            pool.setCapacity(1)
            val next = Connection(); pool.put("next", next)
            assertSame(next, pool.take("next")); next.close()
        }
    }
    @Test fun `default retention expires at one minute and borrowing restores active mode`() {
        var now = 0L
        val modes = mutableListOf<String>()
        WarmConnectionPool<Connection>(isReusable = { !it.closed }, clock = { now },
            onIdle = { modes += "idle" }, onTake = { modes += "active" }).use { pool ->
            val connection = Connection(); pool.put("a", connection)
            now = TimeUnit.SECONDS.toNanos(59)
            assertSame(connection, pool.take("a"))
            assertEquals(listOf("idle", "active"), modes)
            pool.put("a", connection)
            now += TimeUnit.SECONDS.toNanos(60)
            assertNull(pool.take("a")); assertTrue(connection.closed)
            assertEquals(listOf("idle", "active", "idle"), modes)
        }
    }
}
