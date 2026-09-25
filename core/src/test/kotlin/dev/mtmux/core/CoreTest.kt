package dev.mtmux.core

import com.jcraft.jsch.HostKeyRepository
import kotlin.test.*
import org.junit.Test

class CoreTest {
    @Test fun `color replies go to terminal client but OSC commands do not`() {
        assertTrue(TerminalInput.isClientReply("\u001b]10;rgb:e2e2/eeee/e8e8\u001b\\"))
        assertTrue(TerminalInput.isClientReply("\u001b]11;rgb:1111/1919/1616\u0007"))
        assertFalse(TerminalInput.isClientReply("\u001b]11;?\u0007"))
        assertFalse(TerminalInput.isClientReply("\u001b]52;c;Zm9v\u0007"))
        assertFalse(TerminalInput.isClientReply("\u001b]10;rgb:1111/2222/3333\u0007pwd\r"))
    }

    @Test fun `only complete device attributes replies bypass pane paste`() {
        listOf("\u001b[>0;276;0c", "\u001b[?1;2c", "\u001b[?1;2;6;9;15c").forEach {
            assertTrue(TerminalInput.isDeviceAttributesReply(it))
        }
        listOf("0;276;0c", "\u001b[>0;276;0cwhoami\r", "text\u001b[?1;2c", "\u001b[A", "\u001b[1;2R", "\r", "pwd", "\u001b[>c").forEach {
            assertFalse(TerminalInput.isDeviceAttributesReply(it))
        }
    }

    @Test fun `quote round trips shell metacharacters without execution`() {
        val text = "a'b\n\t\$(touch SHOULD_NOT_EXIST); `id` \\\"中文"
        val process = ProcessBuilder("sh", "-c", "printf %s ${Tmux.quote(text)}").start()
        assertEquals(text, process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
        assertFailsWith<IllegalArgumentException> { Tmux.quote("x\u0000y") }
    }

    @Test fun `attach accepts only exact session IDs and never detaches another client`() {
        assertEquals("'tmux' -u attach-session -t '\$12'", Tmux.attach("\$12"))
        assertEquals("'tmux' -u set-option -t '\$12' mouse on", Tmux.enableMouse("\$12"))
        assertEquals("'tmux' -u send-keys -X -t '\$12' cancel", Tmux.leaveCopyMode("\$12"))
        assertFailsWith<IllegalArgumentException> { Tmux.enableMouse("-g") }
        assertFailsWith<IllegalArgumentException> { Tmux.leaveCopyMode("\$1; kill-server") }
        listOf("name", "\$1;kill-server", "-d", "\$1\n", "").forEach {
            assertFailsWith<IllegalArgumentException> { Tmux.attach(it) }
        }
        assertEquals("'/opt/a'\"'\"'b/tmux' -u -V", Tmux.version("/opt/a'b/tmux"))
        assertFailsWith<IllegalArgumentException> { Tmux.version("tmux; id") }
    }

    @Test fun `discovery fails closed on corrupt or injected metadata`() {
        assertEquals(listOf(Pane("\$0", "@2", "%3", true)), Tmux.parse("\$0\t@2\t%3\t1\n"))
        assertEquals(emptyList(), Tmux.parse(""))
        listOf("banner\n\$0\t@2\t%3\t1", "\$0\t@2\t%3\t2", "\$0\t@2\t%3\t1\textra").forEach {
            assertFailsWith<IllegalArgumentException> { Tmux.parse(it) }
        }
    }

    @Test fun `unknown host requires confirmation and changed key is rejected`() {
        val pins = mutableMapOf<String, String>()
        val repository = PinnedHostKeys("[host]:22", object : PinStore {
            override fun get(endpoint: String) = pins[endpoint]
        })
        val key = byteArrayOf(1, 2, 3)
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("host", key))
        assertFalse(repository.challenge!!.changed)
        assertTrue(repository.challenge!!.fingerprint.startsWith("SHA256:"))
        pins["[host]:22"] = repository.challenge!!.key
        assertEquals(HostKeyRepository.OK, repository.check("host", key))
        assertEquals(HostKeyRepository.CHANGED, repository.check("host", byteArrayOf(4)))
        assertTrue(repository.challenge!!.changed)
        val anotherPort = PinnedHostKeys("[host]:2222", object : PinStore {
            override fun get(endpoint: String) = pins[endpoint]
        })
        assertEquals(HostKeyRepository.NOT_INCLUDED, anotherPort.check("host", key))
    }

    @Test fun `disconnect and reconnect invalidate every old input token`() {
        val epoch = ConnectionEpoch()
        assertNull(epoch.token())
        val first = epoch.open()
        assertTrue(epoch.accepts(first))
        epoch.close()
        assertFalse(epoch.accepts(first))
        val second = epoch.open()
        assertFalse(epoch.accepts(first))
        assertTrue(epoch.accepts(second))
        assertNotEquals(first, second)
    }

    @Test fun `invalid connection parameters fail before network use`() {
        assertFailsWith<IllegalArgumentException> { Login("host", 0, "user", "") }
        assertFailsWith<IllegalArgumentException> { Login("bad host", 22, "user", "") }
        assertFailsWith<IllegalArgumentException> { Login("host", 22, "", "") }
    }
}
