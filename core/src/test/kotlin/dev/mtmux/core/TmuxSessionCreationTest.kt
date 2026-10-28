package dev.mtmux.core

import org.junit.Test
import kotlin.test.*

class TmuxSessionCreationTest {
    @Test fun `session input validates tmux target delimiters without limiting Unicode names`() {
        listOf("", " ", " a", "a ", "a.b", "a:b", "a\nb", "a\u0000b", "中".repeat(43)).forEach { assertFalse(TmuxSessionCreation.validName(it), it) }
        listOf("开发环境", "a b", "abc", "a*", "quote'$(printf x);", "hash#{pid}").forEach { assertTrue(TmuxSessionCreation.validName(it), it) }
    }
    @Test fun `new session names are shell quoted tmux format escaped and never attach existing sessions`() {
        val name = "hash#{pid}'$(printf x)"
        val command = TmuxSessionCreation.command(name, "/opt/tmux")
        assertTrue(command.contains("has-session -t " + Tmux.quote("=$name")))
        assertTrue(command.contains("hash##{pid}"))
        assertTrue(command.contains("new-session -d -s"))
        assertFalse(command.contains("new-session -A")); assertFalse(command.contains("attach-session"))
    }
    @Test fun `created pane identity and explicit window index are validated`() {
        val created = TmuxSessionCreation.parse(ExecResult(0, "123:456:\$2:@3:%4:567\t1\n", ""))
        assertEquals("\$2", created.binding.session); assertEquals("%4", created.binding.pane); assertEquals("1", created.windowIndex)
        assertFailsWith<InvalidInput> { TmuxSessionCreation.parse(ExecResult(0, "banner\n123:456:\$2:@3:%4:567\t1\n", "")) }
        assertFailsWith<InvalidInput> { TmuxSessionCreation.parse(ExecResult(0, "123:456:\$2:@3:%4:567\tbad\n", "")) }
    }
    @Test fun `duplicate missing tmux and creation failures have distinct safe codes`() {
        for ((status, code) in listOf(42 to ErrorCode.TMUX_SESSION_EXISTS, 127 to ErrorCode.TMUX_UNAVAILABLE, 43 to ErrorCode.TMUX_CREATE_FAILED)) {
            assertEquals(code, assertFailsWith<MtmuxException> { TmuxSessionCreation.parse(ExecResult(status,"","private server details")) }.code)
        }
    }
}
