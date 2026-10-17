package dev.mtmux.core

import java.util.Base64
import org.junit.Test
import kotlin.test.*

class TmuxFieldsTest {
    private fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    @Test fun `byte framing preserves unicode delimiters empty fields and trailing newlines`() {
        val fields = listOf("中文😀\t:123\n\n", "", "'\\\"\$(touch /tmp/no); #{pane_id}\n")
        val framed = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" } + "\n"
        assertEquals(fields, TmuxFields.decode(encode(framed), 3))
        val pane = Pane("\$1", "@2", "%3", true)
        val output = "MTMUX_FIELDS_1\n${encode(framed)}\nMTMUX_END\n"
        val named = TmuxFields.parseMetadata(output, listOf(pane)).single()
        assertEquals(fields[0], named.sessionName)
        assertEquals(fields[2], named.windowName)
    }

    @Test fun `truncated oversized or ambiguous fields are rejected`() {
        for (raw in listOf("", "-1:x\n", "2:x\n", "99999:x\n", "100000:x\n", "1:x\nnoise", "1:x")) {
            assertFailsWith<IllegalArgumentException>(raw) { TmuxFields.decode(encode(raw), 1) }
        }
        assertFailsWith<IllegalArgumentException> { TmuxFields.decode(encode("0:".repeat(9000) + "\n"), 9000) }
        val pane = Pane("\$1", "@2", "%3", true)
        assertFailsWith<IllegalArgumentException> { TmuxFields.metadataCommand(List(9) { pane }, "tmux") }
        assertFailsWith<IllegalArgumentException> { TmuxFields.metadataCommand(listOf(pane.copy(session = "\$1; touch /tmp/no")), "tmux") }
    }
}
