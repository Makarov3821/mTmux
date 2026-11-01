package dev.mtmux

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.*
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.mtmux.core.RemoteFilePaths
import java.io.ByteArrayInputStream
import java.util.Base64

/** Untrusted content never shares TerminalView or its NativeTerminal JavascriptInterface. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class FilePreviewWebView(context:Context, private val session:FilePreviewSession,
    private val document:PreviewDocument, colors:ColorScheme, private val onLink:(String)->Unit,
    private val onBlocked:()->Unit,private val onFailure:()->Unit) : WebView(context) {
    private val root=Uri.parse(document.url).path!!.substringBefore("/remote/")
    private val assets="https://${FilePreviewSession.HOST}$root/assets/"
    private val markdown=document.kind==RemoteFilePaths.Kind.MARKDOWN
    private val svg=document.kind==RemoteFilePaths.Kind.SVG
    private val paths=PreviewResourceUrls(document.url,session.host)
    private fun css(color:Color)="#%06x".format(java.util.Locale.ROOT,color.toArgb() and 0xffffff)
    private val policy="default-src 'none'; img-src 'self'; style-src 'self' 'unsafe-inline'; script-src ${if(markdown) "'self'" else "'none'"}; connect-src 'none'; font-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'"
    private val style="""html{color-scheme:${if(colors.background.luminanceForPreview()<.5f) "dark" else "light"};background:${css(colors.surface)};color:${css(colors.onSurface)}}body{margin:0;padding:18px;font:16px/1.7 system-ui,sans-serif;overflow-wrap:anywhere}#document{max-width:850px;margin:auto}h1,h2,h3{line-height:1.35}a{color:${css(colors.primary)}}img{max-width:100%;height:auto}pre{padding:14px;border-radius:12px;overflow:auto;background:${css(colors.surfaceVariant)};white-space:pre;overflow-wrap:normal}code{font: .9em/1.6 monospace}blockquote{margin-left:0;padding-left:14px;border-left:3px solid ${css(colors.primary)};color:${css(colors.onSurfaceVariant)}}table{border-collapse:collapse;display:block;max-width:100%;overflow:auto}td,th{padding:8px;border:1px solid ${css(colors.outlineVariant)}}"""
    private val previewContext=Base64.getEncoder().encodeToString(org.json.JSONObject().put("documentUrl",document.url)
        .put("host",session.host ?: org.json.JSONObject.NULL).toString().toByteArray(Charsets.UTF_8))
    private val body=if(markdown) """<main id="document"></main><script id="markdown-source" type="text/plain">${Base64.getEncoder().encodeToString(document.text!!.toByteArray(Charsets.UTF_8))}</script><script id="preview-context" type="text/plain">$previewContext</script><script src="${assets}marked.js"></script><script src="${assets}purify.js"></script><script src="${assets}preview-paths.js"></script><script src="${assets}preview.js"></script>"""
        else if(svg) """<title>SVG preview</title><main id="svg-preview"><img src="${assets}image.svg" alt=""></main>"""
        else paths.html(document.text!!) {android.text.Html.fromHtml(it,android.text.Html.FROM_HTML_MODE_LEGACY).toString()}
    // Anchor to the measured viewport; percentage/vh heights can resolve to zero
    // while Compose is measuring a newly created WebView (as with the terminal).
    private val vectorStyle=if(svg) "html,body{margin:0;padding:0}#svg-preview{position:fixed;inset:0;display:flex;align-items:center;justify-content:center}#svg-preview img{width:100%;height:100%;max-width:none;object-fit:contain}" else ""
    private val html="<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1${if(svg) ",maximum-scale=10" else ""}\"><style>$style$vectorStyle</style></head><body>$body</body></html>".toByteArray(Charsets.UTF_8)
    init {
        setBackgroundColor(colors.surface.toArgb())
        settings.javaScriptEnabled=markdown
        settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.domStorageEnabled=false;settings.databaseEnabled=false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode=WebSettings.LOAD_NO_CACHE
        settings.builtInZoomControls=true;settings.displayZoomControls=false
        settings.setSupportZoom(true)
        if(svg) settings.useWideViewPort=true
        isSaveEnabled=false
        setDownloadListener { _,_,_,_,_->onBlocked() }
        webViewClient=object:WebViewClient() {
            override fun onPageCommitVisible(view:WebView,url:String) {DebugLog.event(DebugLog.Event.PREVIEW_READY,session.id.hashCode())}
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                if(!request.isForMainFrame || !request.hasGesture()) return true
                val uri=request.url
                if(uri.scheme=="https" && uri.host==FilePreviewSession.HOST && uri.path==Uri.parse(document.url).path && uri.fragment!=null) return false
                if(uri.scheme in listOf("http","https") && uri.host!=FilePreviewSession.HOST) onLink(uri.toString())
                else if(paths.remotePath(uri.scheme,uri.host,uri.path)!=null)
                    onLink(paths.remotePath(uri.scheme,uri.host,uri.path)!!)
                else onBlocked()
                return true
            }
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                val uri=request.url
                if(uri.path?.endsWith("/favicon.ico")==true) return blocked(false)
                if(uri.scheme!="https" || uri.host!=FilePreviewSession.HOST || request.method!="GET") return blocked()
                return try {
                    when {
                        uri.path==Uri.parse(document.url).path -> response("text/html",html)
                        uri.path?.startsWith("$root/assets/")==true -> {
                            val name=uri.path!!.removePrefix("$root/assets/")
                            if(svg && name=="image.svg") response("image/svg+xml",document.file.readBytes())
                            else if(markdown && name in listOf("marked.js","purify.js","preview-paths.js","preview.js"))
                                response("text/javascript",context.assets.open("preview/$name").use {it.readBytes()})
                            else blocked()
                        }
                        paths.remotePath(uri.scheme,uri.host,uri.path)!=null -> {
                            val (file,kind)=session.resource(document,paths.remotePath(uri.scheme,uri.host,uri.path)!!)
                            if(kind==RemoteFilePaths.Kind.IMAGE) {
                                val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                                android.graphics.BitmapFactory.decodeFile(file.path,options)
                                if(options.outWidth<=0 || options.outHeight<=0 || options.outWidth.toLong()*options.outHeight>8_000_000L) return blocked()
                            }
                            // CSS imports share the same finite file count/byte budget; no network fallback.
                            WebResourceResponse(if(kind==RemoteFilePaths.Kind.CSS) "text/css" else imageMime(uri.path!!),
                                if(kind==RemoteFilePaths.Kind.CSS) "UTF-8" else null,200,"OK",headers(),file.inputStream())
                        }
                        else -> blocked()
                    }
                } catch(_:Exception) {blocked()}
            }
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
                post {onFailure()};return true
            }
        }
        loadUrl(document.url)
    }
    private fun headers()=mapOf("Content-Security-Policy" to policy,"Cache-Control" to "no-store","X-Content-Type-Options" to "nosniff","Referrer-Policy" to "no-referrer")
    private fun response(mime:String,bytes:ByteArray)=WebResourceResponse(mime,"UTF-8",200,"OK",headers(),ByteArrayInputStream(bytes))
    private fun blocked(notify:Boolean=true):WebResourceResponse {
        if(notify) post {onBlocked()}
        return WebResourceResponse("text/plain","UTF-8",403,"Blocked",headers(),ByteArrayInputStream(byteArrayOf()))
    }
    private var touchX=0f
    private var touchY=0f
    private var imageTap=false
    private var touchSerial=0L
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN) {touchX=event.x;touchY=event.y;imageTap=true;touchSerial++}
        if(event.actionMasked==android.view.MotionEvent.ACTION_POINTER_DOWN || event.actionMasked==android.view.MotionEvent.ACTION_CANCEL ||
            kotlin.math.hypot((event.x-touchX).toDouble(),(event.y-touchY).toDouble())>=8*resources.displayMetrics.density) imageTap=false
        val tap=imageTap && event.actionMasked==android.view.MotionEvent.ACTION_UP && event.eventTime-event.downTime<500 &&
            kotlin.math.hypot((event.x-touchX).toDouble(),(event.y-touchY).toDouble())<8*resources.displayMetrics.density
        val handled=super.onTouchEvent(event)
        if(tap) {
            val serial=touchSerial
            // HitTestResult can still describe the previous frame at ACTION_UP.
            // Ask Chromium for the last tapped node asynchronously; works with HTML JS disabled.
            val handler=android.os.Handler(android.os.Looper.getMainLooper()) {message ->
                val uri=message.data.getString("src")?.let {Uri.parse(it)}
                if(serial==touchSerial && isAttachedToWindow && message.data.getString("url").isNullOrEmpty() &&
                    uri!=null && paths.remotePath(uri.scheme,uri.host,uri.path)!=null) {
                    onLink(paths.remotePath(uri.scheme,uri.host,uri.path)!!)
                }
                true
            }
            requestFocusNodeHref(android.os.Message.obtain(handler))
        }
        return handled
    }
    private fun Color.luminanceForPreview()=.2126f*red+.7152f*green+.0722f*blue
    private fun imageMime(path:String)=when(path.substringAfterLast('.').lowercase()) {"png"->"image/png";"webp"->"image/webp";"svg"->"image/svg+xml";else->"image/jpeg"}
}
