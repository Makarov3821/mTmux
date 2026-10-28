package dev.mtmux

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd

/** Preserve the IME's selection and composition while typing; programmatic inserts move to the end. */
internal class TerminalDraft {
    val state = TextFieldState()
    var text: String
        get() = state.text.toString()
        set(value) { if (value != text) state.setTextAndPlaceCursorAtEnd(value) }
}
