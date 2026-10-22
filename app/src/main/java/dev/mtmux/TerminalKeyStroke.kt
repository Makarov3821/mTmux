package dev.mtmux

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** One-shot latches belong to one connection and reply target, never to preferences. */
@Stable class StickyKeys {
    var modifiers by mutableIntStateOf(0)
        private set
    fun toggle(modifier: Int) { modifiers = modifiers xor (modifier and TerminalKeyStroke.ALL) }
    fun clear() { modifiers = 0 }
    fun take(): Int = modifiers.also { clear() }
}

/** Traditional terminal encoding; printable Shift changes the character, Alt prefixes ESC. */
object TerminalKeyStroke {
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4
    const val ALL = SHIFT or ALT or CTRL
    private const val ESC = "\u001b"
    private val shifted = "`1234567890-=[]\\;',./".zip("~!@#$%^&*()_+{}|:\"<>?").toMap()
    private val tildeKeys = mapOf("Insert" to 2, "Delete" to 3, "PageUp" to 5, "PageDown" to 6,
        "F5" to 15, "F6" to 17, "F7" to 18, "F8" to 19, "F9" to 20, "F10" to 21, "F11" to 23, "F12" to 24)
    private val finalKeys = mapOf("ArrowUp" to 'A', "ArrowDown" to 'B', "ArrowRight" to 'C', "ArrowLeft" to 'D',
        "Home" to 'H', "End" to 'F', "F1" to 'P', "F2" to 'Q', "F3" to 'R', "F4" to 'S')

    fun label(modifiers: Int) = listOf(CTRL to "Ctrl", SHIFT to "Shift", ALT to "Alt")
        .filter { modifiers and it.first != 0 }.joinToString("+") { it.second }

    fun encode(key: String, modifiers: Int): String? {
        val mask = modifiers and ALL
        val parameter = mask + 1
        finalKeys[key]?.let { final ->
            return if (mask != 0) "$ESC[1;$parameter$final"
                else if (key.startsWith("F")) "${ESC}O$final" else "$ESC[$final"
        }
        tildeKeys[key]?.let { number -> return "$ESC[$number${if(mask == 0) "" else ";$parameter"}~" }
        val alt = if(mask and ALT != 0) ESC else ""
        when (key) {
            "Escape" -> return alt + ESC
            "Enter" -> return alt + "\r"
            "Tab" -> return if(mask and SHIFT != 0) "$ESC[Z" else alt + "\t"
            "Backspace" -> return alt + if(mask and CTRL != 0) "\b" else "\u007f"
        }
        if (key.codePointCount(0,key.length) != 1) return null
        val point = key.codePointAt(0)
        if (Character.isISOControl(point) || point in 0xD800..0xDFFF) return null
        val value = if(mask and CTRL != 0) {
            val upper = Character.toUpperCase(point)
            when {
                upper in 'A'.code..'Z'.code -> (upper - 64).toChar().toString()
                point in listOf(' '.code,'@'.code,'2'.code,'`'.code) -> "\u0000"
                point == '['.code || point == '3'.code -> ESC
                point == '\\'.code || point == '4'.code -> "\u001c"
                point == ']'.code || point == '5'.code -> "\u001d"
                point == '^'.code || point == '6'.code -> "\u001e"
                point in listOf('_'.code,'/'.code,'-'.code,'7'.code) -> "\u001f"
                point == '?'.code || point == '8'.code -> "\u007f"
                else -> return null
            }
        } else if(mask and SHIFT != 0) {
            if(point <= 0x7f && shifted.containsKey(point.toChar())) shifted.getValue(point.toChar()).toString()
            else String(Character.toChars(Character.toUpperCase(point)))
        } else key
        return alt + value
    }
}

internal const val TERMINAL_KEY_PAGE_COUNT = 4
internal const val TERMINAL_KEY_PAGER_START = 1_073_741_820 // Middle of Int range, aligned to page zero.
internal fun terminalKeyPage(page: Int) = Math.floorMod(page, TERMINAL_KEY_PAGE_COUNT)

/** An obsolete IME connection must not replay the composing key after its modifier was consumed. */
internal class OneShotInputGate(private val onText: (String) -> Boolean, private val onBackspace: () -> Boolean,
    private val onConsumed: () -> Unit) {
    var retired=false
        private set
    fun retire() {retired=true}
    fun text(value: CharSequence?): Boolean {
        if(retired) return true
        if(value.isNullOrEmpty() || !onText(value.toString())) return false
        retire();onConsumed();return true
    }
    fun delete(before: Int, after: Int): Boolean {
        if(retired) return true
        if(before!=1 || after!=0 || !onBackspace()) return false
        retire();onConsumed();return true
    }
}
