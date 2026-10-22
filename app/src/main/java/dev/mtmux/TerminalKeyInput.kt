package dev.mtmux

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.TextAttribute
import android.text.InputType
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** Retiring a consumed IME connection prevents a composing-text update from replaying the key. */
class TerminalImeSession {
    internal var connection: StickyInputConnection? = null
    private var restartPending=false
    fun finishComposition() { connection?.finishComposingText() }
    fun restart(view: View) {
        connection?.retire()
        if(restartPending) return
        restartPending=true
        view.post {
            restartPending=false
            (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(view)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable fun TerminalKeyInput(session: TerminalImeSession, modifiers: () -> Int,
    onTextKey: (String) -> Boolean, onBackspace: () -> Boolean, content: @Composable () -> Unit) {
    val view=LocalView.current
    val currentModifiers by rememberUpdatedState(modifiers)
    val currentText by rememberUpdatedState(onTextKey)
    val currentBackspace by rememberUpdatedState(onBackspace)
    val interceptor=remember(session,view) {
        PlatformTextInputInterceptor { request,next ->
            next.startInputMethod(PlatformTextInputMethodRequest { attributes ->
                val original=request.createInputConnection(attributes)
                if(currentModifiers()!=0) {
                    attributes.imeOptions=attributes.imeOptions or EditorInfo.IME_FLAG_FORCE_ASCII
                    attributes.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                StickyInputConnection(original,session,view,{currentText(it)},{currentBackspace()}).also {session.connection=it}
            })
        }
    }
    InterceptPlatformTextInput(interceptor,content)
}

internal class StickyInputConnection(target: InputConnection, private val session: TerminalImeSession,
    private val view: View, private val onTextKey: (String) -> Boolean, private val onBackspace: () -> Boolean
) : InputConnectionWrapper(target,false) {
    private val gate=OneShotInputGate(onTextKey,onBackspace) {session.restart(view)}
    fun retire() { gate.retire();super.finishComposingText() }
    private fun text(text: CharSequence?)=gate.text(text)
    private fun delete(before: Int, after: Int)=gate.delete(before,after)
    override fun commitText(text: CharSequence?, newCursorPosition: Int) =
        if(text(text)) true else super.commitText(text,newCursorPosition)
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
        if(text(text)) true else super.setComposingText(text,newCursorPosition)
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
        if(delete(beforeLength,afterLength)) true else super.deleteSurroundingText(beforeLength,afterLength)
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
        if(delete(beforeLength,afterLength)) true else super.deleteSurroundingTextInCodePoints(beforeLength,afterLength)
    override fun sendKeyEvent(event: KeyEvent?) = if(gate.retired) true else super.sendKeyEvent(event)
    @RequiresApi(33)
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
        if(text(text)) true else super.commitText(text,newCursorPosition,textAttribute)
    @RequiresApi(33)
    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
        if(text(text)) true else super.setComposingText(text,newCursorPosition,textAttribute)
    @RequiresApi(34)
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
        if(text(text)) true else super.replaceText(start,end,text,newCursorPosition,textAttribute)
    override fun performEditorAction(editorAction: Int) = if(text("\r")) true else super.performEditorAction(editorAction)
    override fun closeConnection() {
        gate.retire()
        if(session.connection===this) session.connection=null
        super.closeConnection()
    }
}

internal fun terminalHardwareKey(event: KeyEvent): Pair<String,Int>? {
    val key=when(event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${event.keyCode-KeyEvent.KEYCODE_F1+1}"
        else -> {
            val point=event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_ALT_MASK.inv())
            if(point==0 || point and android.view.KeyCharacterMap.COMBINING_ACCENT != 0) return null
            String(Character.toChars(point))
        }
    }
    val modifiers=(if(event.isShiftPressed) TerminalKeyStroke.SHIFT else 0) or
        (if(event.isAltPressed) TerminalKeyStroke.ALT else 0) or (if(event.isCtrlPressed) TerminalKeyStroke.CTRL else 0)
    return key to modifiers
}

@Composable internal fun androidx.compose.ui.Modifier.captureTerminalKey(sticky: StickyKeys, session: TerminalImeSession,
    onKey: (String,Int) -> Unit): androidx.compose.ui.Modifier {
    val captured=remember(sticky) {mutableSetOf<Int>()}
    val currentKey by rememberUpdatedState(onKey)
    val view=LocalView.current
    return this.then(androidx.compose.ui.Modifier.onPreviewKeyEvent { event ->
        val native=event.nativeKeyEvent
        if(native.action==KeyEvent.ACTION_UP) captured.remove(native.keyCode)
        else if(native.action!=KeyEvent.ACTION_DOWN) false
        else if(native.keyCode in captured && native.repeatCount>0) true
        else if(sticky.modifiers==0 || KeyEvent.isModifierKey(native.keyCode)) false
        else terminalHardwareKey(native)?.let { (key,modifiers) ->
            captured.add(native.keyCode);currentKey(key,modifiers);session.restart(view);true
        } ?: false
    })
}
