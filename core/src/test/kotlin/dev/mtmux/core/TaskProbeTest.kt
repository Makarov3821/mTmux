package dev.mtmux.core

import java.util.Base64
import org.junit.Test
import kotlin.test.*

class TaskProbeTest {
    private val pane = Pane("\$1", "@2", "%3", true)
    private val identity = "123:456:\$1:@2:%3:789:0"
    private fun record(after: String = identity, ok: String = "1"): String {
        fun enc(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
        return (listOf("MTMUX_PROBE_1", identity) + listOf("测试\n别名\n", "'\t\$(touch /tmp/no)\n", "0\n", "kiro-cli\n", "title\n", "› ask a question or describe a task ↵").map(::enc) + listOf(after, ok, "MTMUX_END")).joinToString("\n") + "\n"
    }
    @Test fun `framing preserves names and verifies before and after identity`() {
        val result = TaskProbe.parse(record(), listOf(pane)).single()
        assertEquals("测试\n别名", result.sessionName)
        assertEquals("'\t\$(touch /tmp/no)", result.windowName)
        assertEquals(TaskState.COMPLETED, result.state)
        assertEquals(TaskState.UNKNOWN, TaskProbe.parse(record(after="changed"), listOf(pane)).single().state)
        assertEquals(TaskState.UNKNOWN, TaskProbe.parse(record(ok="0"), listOf(pane)).single().state)
        assertFailsWith<IllegalArgumentException> { TaskProbe.parse("shell noise\n" + record(), listOf(pane)) }
        assertFailsWith<IllegalArgumentException> { TaskProbe.parse(record().dropLast(12), listOf(pane)) }
        assertFailsWith<IllegalArgumentException> { TaskProbe.parse(record(), listOf(pane.copy(id="%4"))) }
    }
    @Test fun `batch size and shell targets are bounded`() {
        assertFailsWith<IllegalArgumentException> { TaskProbe.command(List(9) { pane }, "tmux") }
        assertFailsWith<IllegalArgumentException> { TaskProbe.command(listOf(pane.copy(id="%3;kill-server")), "tmux") }
        val command = TaskProbe.command(listOf(pane), "tmux")
        assertFalse(command.contains("send-keys"))
        assertFalse(command.contains("copy-mode"))
        assertFalse(command.contains(" -S "))
        assertTrue(command.contains("capture-pane -p"))
    }
}
