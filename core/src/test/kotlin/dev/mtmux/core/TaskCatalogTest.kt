package dev.mtmux.core

import org.junit.Test
import kotlin.test.*

class TaskCatalogTest {
    private val binding = PaneBinding.parse("\$1", "123:456:\$1:@2:%3:789")
    private fun record(identity: String = binding.identity + ":0", name: String = "中文😀\n\t:12\n") =
        listOf(identity, name, "'\$(no) #{pane_id}\n\n", "0", "1").joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" } + "\n"
    @Test fun `catalog preserves labels identifies linked sessions and skips dead panes`() {
        val output = record() + record("123:456:\$4:@2:%3:789:0", "linked") + record(binding.identity + ":1")
        val entries = TaskCatalog.parse(output)
        assertEquals(2, entries.size)
        assertEquals("中文😀\n\t:12\n", entries[0].pane.sessionName)
        assertEquals("'\$(no) #{pane_id}\n\n", entries[0].pane.windowName)
        assertEquals(listOf("\$1", "\$4"), entries.map { it.binding.session })
        assertTrue(entries.all { it.pane.id == "%3" && it.snapshot().state == TaskState.UNKNOWN })
        assertFailsWith<IllegalArgumentException> { TaskCatalog.parse(output.dropLast(2)) }
        assertFailsWith<IllegalArgumentException> { TaskCatalog.parse(record().repeat(257)) }
    }
    @Test fun `preparation verifies identities around metadata and quotes only validated targets`() {
        val output = "MTMUX_PREPARE_1\n${binding.identity}:0\n${record()}${binding.identity}:0\nMTMUX_END\n"
        val prepared = TmuxPreparation.parse(output, "\$1", binding)
        assertEquals(binding, prepared.binding)
        assertEquals("中文😀\n\t:12\n", prepared.pane.sessionName)
        assertFailsWith<IllegalStateException> { TmuxPreparation.parse(output.replaceAfterLast("789:0", "oops"), "\$1", binding) }
        val changed = PaneBinding.parse("\$1", "123:456:\$1:@2:%3:999")
        assertFailsWith<IllegalStateException> { TmuxPreparation.parse(output, "\$1", changed) }
        assertFailsWith<IllegalArgumentException> { TmuxPreparation.command("\$1;kill-server", null, true, "tmux") }
        assertFalse(TmuxPreparation.command("\$1", binding, false, "tmux").contains("mouse on"))
    }
}
