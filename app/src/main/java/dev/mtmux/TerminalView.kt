package dev.mtmux

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// Constructed only by Compose AndroidView; never inflated by XML layout tools.
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class TerminalView(
    context: Context,
    private val onReady: (Int, Int) -> Unit,
    private val onResize: (Int, Int) -> Unit,
    private val onInput: (String, String) -> Unit,
    private val onDraftInput: (String, String, String) -> Unit,
    private val onRendered: (Int) -> Unit,
    private val onFailure: (UiText) -> Unit,
    private val onPasteFinished: (String) -> Unit,
    private val onNotice: (TerminalStatus) -> Unit,
    private val onReading: (Boolean) -> Unit = {},
    private val onBinaryInput: (String, ByteArray) -> Unit = { _, _ -> }
) : WebView(context) {
    private val acknowledgements = ConcurrentHashMap<String, CountDownLatch>()
    private val sequence = AtomicLong()
    @Volatile private var disposed = false
    @Volatile private var activeToken = ""
    private var pageReady = false
    private val pendingScripts = mutableListOf<String>()

    init {
        setBackgroundColor(0xFF111916.toInt())
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportMultipleWindows(false)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                DebugLog.event(DebugLog.Event.WEB_LOAD_FAILED,error.errorCode)
                if (request.isForMainFrame) onFailure(uiText(R.string.web_load_failed))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                return loader.shouldInterceptRequest(request.url)
                    ?: WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    DebugLog.event(DebugLog.Event.WEB_SCRIPT_FAILED,message.lineNumber())
                    onFailure(uiText(R.string.web_script_failed))
                }
                return true // Never log terminal output or JavaScript payloads.
            }
        }
        addJavascriptInterface(Bridge(), "NativeTerminal")
        loadUrl("https://appassets.androidplatform.net/assets/terminal/index.html")
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Keep Compose's outer workspace scroll from stealing terminal gestures.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }

    fun connection(token: String) {
        activeToken = token
        js("setConnection(${JSONObject.quote(token)})")
    }
    fun resetScreen() = js("resetTerminal()")
    fun paste(text: String, token: String) = js("pasteDraft(${JSONObject.quote(text)},${JSONObject.quote(token)})")
    fun sendDraft(text: String, token: String, id: String, enter: Boolean) =
        js("sendDraft(${JSONObject.quote(text)},${JSONObject.quote(token)},${JSONObject.quote(id)},$enter)")
    private var darkAppearance: Boolean? = null
    fun appearance(dark: Boolean, force: Boolean = false) {
        if (!force && darkAppearance == dark) return
        darkAppearance=dark
        setBackgroundColor(if(dark) 0xFF111916.toInt() else 0xFFF7FAF8.toInt())
        js("if(typeof setTerminalAppearance==='function') setTerminalAppearance($dark)")
    }
    fun font(size: Int) = js("setFont($size)")
    fun copySelection() = js("copySelection()")
    fun snapshot(onResult: (TerminalSnapshot) -> Unit) {
        if (disposed) return
        val token = activeToken
        evaluateJavascript("terminalSnapshot()") { result ->
            if (disposed || activeToken != token) return@evaluateJavascript
            runCatching {
                val value = JSONObject(result)
                TerminalSnapshot(value.getString("text"),value.getBoolean("clipped"),value.getInt("viewportOffset"))
            }.onSuccess(onResult).onFailure { onNotice(TerminalStatus(uiText(R.string.web_snapshot_failed), alert = true)) }
        }
    }
    private var loggedViewport = -1
    fun viewport(height: Float) {
        if(height.toInt()!=loggedViewport) { loggedViewport=height.toInt();DebugLog.event(DebugLog.Event.VIEWPORT,loggedViewport) }
        js("if(typeof setVisibleHeight === 'function') setVisibleHeight($height)")
    }
    fun latest() = js("scrollLatest()")
    fun remoteTouch(enabled: Boolean) = js("setRemoteTouch($enabled)")
    fun scrollSensitivity(value: Float) = js("setScrollSensitivity(${value.coerceIn(0.5f, 2f)})")
    private fun js(code: String) {
        if(disposed) return
        if(pageReady) evaluateJavascript(code,null)
        else if(pendingScripts.size<128) pendingScripts.add(code)
        else {pendingScripts.clear();onFailure(uiText(R.string.web_init_incomplete))}
    }

    /** One SSH chunk is outstanding at a time; xterm acknowledges after parsing it. */
    fun render(bytes: ByteArray, token: String) {
        if (disposed) throw dev.mtmux.core.MtmuxException(dev.mtmux.core.ErrorCode.TERMINAL_CLOSED)
        if (activeToken != token) return
        val id = sequence.incrementAndGet().toString()
        val latch = CountDownLatch(1)
        acknowledgements[id] = latch
        val encoded = Base64.getEncoder().encodeToString(bytes)
        post {
            if (activeToken == token) js("receive(${JSONObject.quote(encoded)},${JSONObject.quote(id)},${JSONObject.quote(token)})")
            else latch.countDown()
        }
        try {
            if (!latch.await(10, TimeUnit.SECONDS) || disposed) throw AppError(R.string.err_render_timeout)
            post { if (activeToken == token) onRendered(bytes.size) }
        }
        finally { acknowledgements.remove(id) }
    }

    fun dispose() {
        disposed = true
        acknowledgements.values.forEach { it.countDown() }
        acknowledgements.clear()
        pendingScripts.clear()
        removeJavascriptInterface("NativeTerminal")
        destroy()
    }

    private inner class Bridge {
        @JavascriptInterface fun binaryInput(token: String, encoded: String) {
            if (encoded.length > 131072) return
            val data = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return
            post { onBinaryInput(token, data) }
        }
        @JavascriptInterface fun reading(reading: Boolean) { post { onReading(reading) } }
        @JavascriptInterface fun ready(cols: Int, rows: Int) { post {
            if(!disposed) {
                DebugLog.event(DebugLog.Event.WEB_READY,cols,rows)
                pageReady=true
                pendingScripts.toList().also {pendingScripts.clear()}.forEach {evaluateJavascript(it,null)}
                onReady(cols,rows)
            }
        } }
        @JavascriptInterface fun resize(cols: Int, rows: Int) { post { DebugLog.event(DebugLog.Event.WEB_RESIZE,cols,rows); onResize(cols, rows) } }
        @JavascriptInterface fun input(token: String, data: String) { post { onInput(token, data) } }
        @JavascriptInterface fun draftInput(token: String, id: String, data: String) { post { onDraftInput(token, id, data) } }
        @JavascriptInterface fun pasteFinished(token: String) { post { onPasteFinished(token) } }
        @JavascriptInterface fun notice(code: String) { post { onNotice(webNotice(code)) } }
        @JavascriptInterface fun ack(id: String) { acknowledgements[id]?.countDown() }
        @JavascriptInterface fun copy(text: String) {
            if (text.isNotEmpty()) post {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clip_terminal_selection), text))
                onNotice(TerminalStatus(uiText(R.string.web_selection_copied)))
            }
        }
    }
}

/** The web page reports fixed codes only; unknown codes never pass page text through. */
internal fun webNotice(code: String): TerminalStatus = TerminalStatus(uiText(when (code) {
    "CONNECTION_CHANGED" -> R.string.status_text_stale
    "PASTE_TOO_LONG" -> R.string.web_paste_too_long
    "PASTE_CONTROL" -> R.string.web_paste_control
    "PASTE_MULTILINE_UNSUPPORTED" -> R.string.web_paste_multiline
    else -> R.string.web_paste_rejected
}), alert = true)
