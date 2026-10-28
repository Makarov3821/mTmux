package dev.mtmux

import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class TerminalDraftTest {
    @Test fun externalVoiceTextPlacesCursorAfterThePunctuationAndSameTextKeepsSelection() {
        val draft=TerminalDraft();draft.text="这是一段语音。"
        assertEquals(TextRange(draft.text.length),draft.state.selection)
        draft.state.edit { selection=TextRange(2,3) }
        draft.text=draft.text
        assertEquals(TextRange(2,3),draft.state.selection)
    }
    @Test fun repeatedEditsDeletePunctuationAndThenTheWholeUtterance() {
        val draft=TerminalDraft();draft.text="测试中文和 English。"
        repeat(draft.text.length) {
            draft.state.edit { replace(length-1,length,"") }
        }
        assertEquals("",draft.text);assertEquals(TextRange.Zero,draft.state.selection)
    }
}
