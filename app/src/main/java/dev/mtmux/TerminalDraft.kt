package dev.mtmux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Plain draft value while the Backspace-specific editor experiment is paused. */
internal class TerminalDraft {
    var text by mutableStateOf("")
}

/* Backspace experiment paused; retain the state/selection implementation for later comparison.
Imports for this implementation:
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd

internal class TerminalDraft {
    val state = TextFieldState()
    var text: String
        get() = state.text.toString()
        set(value) { if (value != text) state.setTextAndPlaceCursorAtEnd(value) }
}
*/
