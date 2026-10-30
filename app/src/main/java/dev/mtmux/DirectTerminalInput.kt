package dev.mtmux

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.atomic.AtomicInteger

/** Native IME endpoint. Only the uncommitted composing word lives locally. */
internal class DirectTerminalInput(context: Context) : View(context) {
    var onText: (String) -> Unit = {}
    var onKey: (String, Int) -> Unit = { _, _ -> }
    var onComposition: (String) -> Unit = {}
    var onInputFocus: (Boolean) -> Unit = {}
    var modifiers: Int = 0
    var inputEnabled = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) { recovery.suspend(); retire(); clearFocus() }
            else scheduleRecovery()
        }
    private var connection: TerminalConnection? = null
    private val recovery = TerminalImeRecovery()
    private var restartPending = false
    private val ime get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun inputFlags() = (if (inputEnabled) 1 else 0) or (if (hasFocus()) 2 else 0) or
        (if (hasWindowFocus()) 4 else 0) or (if (isAttachedToWindow) 8 else 0) or (if (connection != null) 16 else 0)
    private fun readyForIme() = inputEnabled && isAttachedToWindow && hasFocus() && hasWindowFocus()
    private val recoverInput = Runnable {
        restartPending = false
        val request = recovery.take(readyForIme()) ?: return@Runnable
        if (request.restart) {
            DebugLog.event(DebugLog.Event.IME_RESTART, connection?.id ?: 0, inputFlags())
            ime.restartInput(this)
        }
        if (request.show) {
            val shown = ime.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            DebugLog.event(DebugLog.Event.IME_SHOW, if (shown) 1 else 0, inputFlags())
            // Do not spin or open a keyboard on another editor. A later focus event/tap retries.
            if (!shown) recovery.keyboard(connection != null)
        }
    }
    private fun scheduleRecovery() {
        if (restartPending || !recovery.pending || !readyForIme()) return
        restartPending = true
        post(recoverInput)
    }

    init { isFocusable = true; isFocusableInTouchMode = true; importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): TerminalConnection? {
        if (!inputEnabled) return null
        retire()
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            if (modifiers != 0) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE or if (modifiers != 0) EditorInfo.IME_FLAG_FORCE_ASCII else 0
        outAttrs.initialSelStart = 0; outAttrs.initialSelEnd = 0
        return TerminalConnection().also {
            connection = it
            recovery.connected()
            DebugLog.event(DebugLog.Event.IME_CREATED, it.id, inputFlags())
        }
    }
    fun showKeyboard() {
        if (!inputEnabled) return
        if (!requestFocus()) return
        recovery.keyboard(connection != null && ime.isActive(this))
        scheduleRecovery()
    }
    fun finishComposition() { connection?.finishComposingText() }
    fun retire() {
        connection?.retire()
        connection = null
        recovery.invalidate()
        onComposition("")
    }
    fun restart() {
        retire()
        scheduleRecovery()
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) { recovery.suspend(); retire() }
        onInputFocus(gainFocus && inputEnabled && hasWindowFocus())
        DebugLog.event(DebugLog.Event.IME_FOCUS, 1, inputFlags())
        if (gainFocus) scheduleRecovery()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) { recovery.suspend(); retire() }
        onInputFocus(hasWindowFocus && inputEnabled && hasFocus())
        DebugLog.event(DebugLog.Event.IME_FOCUS, 2, inputFlags())
        if (hasWindowFocus) scheduleRecovery()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); scheduleRecovery() }
    override fun onDetachedFromWindow() {
        removeCallbacks(recoverInput); restartPending = false
        recovery.suspend(); retire()
        super.onDetachedFromWindow()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputEnabled) return super.onKeyDown(keyCode, event)
        if (KeyEvent.isModifierKey(keyCode)) return true
        val (key, mask) = terminalHardwareKey(event) ?: return super.onKeyDown(keyCode, event)
        if (key == "Backspace" && connection?.hasPendingText() == true) connection?.deleteSurroundingText(1, 0)
        else { finishComposition(); onKey(key, mask) }
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (inputEnabled && (KeyEvent.isModifierKey(keyCode) || terminalHardwareKey(event) != null)) true
        else super.onKeyUp(keyCode, event)

    internal inner class TerminalConnection : BaseInputConnection(this@DirectTerminalInput, true) {
        val id = connectionIds.incrementAndGet()
        private val pending = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        private var retired = false
        private var loggedStale = false
        private var loggedInput = false
        private var batchDepth = 0
        override fun getEditable(): Editable = pending
        private fun active(): Boolean {
            if (!retired && inputEnabled && connection === this) return true
            if (!loggedStale) {
                loggedStale = true
                DebugLog.event(DebugLog.Event.IME_STALE, id, inputFlags() or (if (retired) 32 else 0))
            }
            // Never replay an old connection into a new target. Recover the endpoint instead.
            if (connection == null) { recovery.invalidate(); scheduleRecovery() }
            return false
        }
        fun retire() {
            if (!retired) DebugLog.event(DebugLog.Event.IME_RETIRED, id, inputFlags())
            retired = true; pending.clear()
        }
        fun hasPendingText() = active() && pending.isNotEmpty()
        private fun changed() {
            if (!active()) return
            onComposition(pending.toString())
            if (batchDepth == 0) ime.updateSelection(this@DirectTerminalInput,
                Selection.getSelectionStart(pending), Selection.getSelectionEnd(pending),
                getComposingSpanStart(pending), getComposingSpanEnd(pending))
        }
        private fun flush() {
            val text = pending.toString()
            pending.clear(); Selection.setSelection(pending, 0)
            changed()
            if (text.isNotEmpty() && !loggedInput) {
                loggedInput = true
                DebugLog.event(DebugLog.Event.IME_INPUT, id, text.length)
            }
            if (text.isNotEmpty()) when (text) {
                "\r", "\n", "\r\n" -> onKey("Enter", 0)
                else -> onText(text)
            }
        }
        // A latched key consumes one IME callback; its later commit must not replay it.
        private fun shortcut(text: CharSequence?): Boolean {
            if (!active()) return true
            if (modifiers == 0 || text.isNullOrEmpty()) return false
            val value = text.toString()
            if (value.codePointCount(0, value.length) == 1) onKey(when (value) {
                "\r", "\n" -> "Enter"; "\t" -> "Tab"; else -> value
            }, 0) else onText(value)
            restart()
            return true
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (shortcut(text)) return true
            if ((text?.length ?: 0) > 32768) return false
            return super.setComposingText(text, newCursorPosition).also { changed() }
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (shortcut(text)) return true
            if ((text?.length ?: 0) > 32768) return false
            return super.commitText(text, newCursorPosition).also { flush() }
        }
        override fun finishComposingText(): Boolean {
            if (!active()) return true
            return super.finishComposingText().also { flush() }
        }
        override fun beginBatchEdit(): Boolean { if (active()) batchDepth++; return true }
        override fun endBatchEdit(): Boolean { if (active()) { batchDepth = (batchDepth - 1).coerceAtLeast(0); changed() }; return true }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, false)
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, true)
        private fun delete(before: Int, after: Int, points: Boolean): Boolean {
            if (!active()) return true
            if (before !in 0..64 || after !in 0..64) return false
            if (pending.isNotEmpty()) {
                // Backspace experiment paused: use Android's standard composing-text deletion.
                /*
                // BaseInputConnection protects the whole composing span when deleting
                // surrounding text. Here the composing word itself is the local buffer.
                val start = minOf(Selection.getSelectionStart(pending), Selection.getSelectionEnd(pending)).coerceAtLeast(0)
                val end = maxOf(Selection.getSelectionStart(pending), Selection.getSelectionEnd(pending)).coerceAtLeast(start)
                var from = if (points) Character.offsetByCodePoints(pending,start,-minOf(before,Character.codePointCount(pending,0,start)))
                    else (start-before).coerceAtLeast(0)
                var to = if (points) Character.offsetByCodePoints(pending,end,minOf(after,Character.codePointCount(pending,end,pending.length)))
                    else (end+after).coerceAtMost(pending.length)
                if (start != end) { from=start; to=end }
                if (from>0 && from<pending.length && Character.isLowSurrogate(pending[from]) && Character.isHighSurrogate(pending[from-1])) from--
                if (to>0 && to<pending.length && Character.isLowSurrogate(pending[to]) && Character.isHighSurrogate(pending[to-1])) to++
                pending.delete(from,to); Selection.setSelection(pending,from)
                changed(); return true
                */
                val result = if (points) super.deleteSurroundingTextInCodePoints(before, after)
                    else super.deleteSurroundingText(before, after)
                changed()
                return result
            }
            repeat(before) { onKey("Backspace", 0) }; repeat(after) { onKey("Delete", 0) }
            return true
        }
        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (!active() || event == null) return true
            return this@DirectTerminalInput.dispatchKeyEvent(event)
        }
        override fun performEditorAction(actionCode: Int): Boolean {
            if (active()) { finishComposingText(); onKey("Enter", 0) }
            return true
        }
        override fun closeConnection() {
            retire()
            if (connection === this) { connection = null; recovery.invalidate(); onComposition("") }
            super.closeConnection()
        }
    }
    companion object { private val connectionIds = AtomicInteger() }
}
