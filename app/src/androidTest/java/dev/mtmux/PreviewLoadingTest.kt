package dev.mtmux

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mtmux.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PreviewLoadingTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun previewLimitsPersistValidateAndUseTheCurrentSettingsStyle() {
        val context=object:android.content.ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("preview-loading-test-$name",mode)
        }
        val prefs=context.getSharedPreferences("terminal-settings",0);prefs.edit().clear().commit()
        var showing by mutableStateOf(true)
        compose.setContent {CompositionLocalProvider(LocalContext provides context) {MaterialTheme(colorScheme=appColors(true)) {
            if(showing) Column {PreviewSettings()}
        }}}
        try {
            compose.onNodeWithTag("preview-file-limit").assertTextContains("10 MiB")
            compose.onNodeWithTag("preview-cache-limit").assertTextContains("50 MiB")
            compose.onNodeWithTag("preview-file-limit").performClick()
            compose.onNodeWithTag("preview-limit-input").performTextReplacement("20")
            compose.onNodeWithTag("preview-limit-save").performClick()
            compose.onNodeWithTag("preview-cache-limit").performClick()
            compose.onNodeWithTag("preview-limit-input").performTextReplacement("19")
            compose.onNodeWithTag("preview-limit-save").assertIsNotEnabled()
            compose.onNodeWithTag("preview-limit-input").performTextReplacement("64")
            compose.onNodeWithTag("preview-limit-save").performClick()
            assertEquals(PreviewLimits(20,64),savedPreviewLimits(context))
            compose.runOnIdle {showing=false};compose.waitForIdle()
            compose.runOnIdle {showing=true}
            compose.onNodeWithTag("preview-file-limit").assertTextContains("20 MiB")
            compose.onNodeWithTag("preview-cache-limit").assertTextContains("64 MiB")
        } finally {prefs.edit().clear().commit()}
    }

    private fun fixture():SshClient {
        val args=InstrumentationRegistry.getArguments();assumeTrue("Use the SSH fixture",args.containsKey("fixturePort"))
        return SshClient(object:PinStore {override fun get(endpoint:String)=args.getString("fixtureHostKey")}).also {
            it.connect(Login("127.0.0.1",args.getString("fixturePort")!!.toInt(),args.getString("fixtureUser")!!,"",
                Base64.getDecoder().decode(args.getString("fixtureKey"))))
        }
    }
    private fun put(connection:SshClient,path:String,bytes:ByteArray) {
        assertEquals(0,connection.exec("cat > "+Tmux.quote(path),bytes).status)
    }
    private fun reads(id:String)=DebugLog.snapshot().toString(Charsets.UTF_8).lineSequence()
        .count {it.contains(" PREVIEW_READ a=${id.hashCode()} ")}

    @Test fun largeInlineRasterImagesAreSampledWhileTheOriginalStaysCached() {
        val file=File.createTempFile("preview-large-image-",".jpg",compose.activity.cacheDir)
        try {
            val bitmap=android.graphics.Bitmap.createBitmap(3000,2000,android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            file.outputStream().use {assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it))};bitmap.recycle()
            val original=file.length()
            val response=PreviewThumbnails.open(file)
            assertEquals("image/webp",response.mime)
            response.stream.use {stream ->
                val decoded=android.graphics.BitmapFactory.decodeStream(stream)!!
                assertTrue(decoded.width.toLong()*decoded.height<=2_000_000)
                assertTrue(android.graphics.Color.green(decoded.getPixel(10,10))>180)
                decoded.recycle()
            }
            val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(file.path,bounds)
            assertEquals(3000,bounds.outWidth);assertEquals(2000,bounds.outHeight);assertEquals(original,file.length())
        } finally {file.delete()}
    }

    @Test fun sftpCacheEvictsAndRefetchesBeyondThirtyTwoFilesAndSvgUsesTheImageLimit() {
        val connection=fixture();val directory=connection.exec("mktemp -d /tmp/mtmux-cache-test-XXXXXX").output.trim()
        try {
            put(connection,"$directory/index.md","# Cache fixture".toByteArray())
            val image=("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\"><rect width=\"8\" height=\"8\" fill=\"green\"/><!--"+
                " ".repeat(64*1024)+"--></svg>").toByteArray()
            repeat(40) {put(connection,"$directory/image$it.svg",image)}
            val request=PreviewRequest("$directory/index.md","fixture",connection,null,"tmux","Fixture")
            FilePreviewSession(compose.activity,request,PreviewLimits(1,2)).use {session ->
                val document=session.open(request.target,directory)
                repeat(40) {index ->session.resource(document,"image$index.svg").stream.use {assertEquals(image.size,it.readBytes().size)}}
                val folder=File(compose.activity.cacheDir,"preview/${session.id}")
                assertTrue(folder.listFiles()!!.sumOf {it.length()}<=2L*1024*1024)
                val before=reads(session.id)
                session.resource(document,"image39.svg").stream.close()
                assertEquals(before,reads(session.id))
                session.resource(document,"image0.svg").stream.close()
                assertEquals(before+1,reads(session.id))
            }
            val large=("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\"><!--"+" ".repeat(3*1024*1024)+"--></svg>").toByteArray()
            put(connection,"$directory/large.svg",large)
            FilePreviewSession(compose.activity,request).use {session ->
                assertEquals(large.size.toLong(),session.open("$directory/large.svg",directory).file.length())
            }
            FilePreviewSession(compose.activity,request,PreviewLimits(1,2)).use {session ->
                try {session.open("$directory/large.svg",directory);fail("single file limit ignored")}
                catch(error:RemoteFileException) {assertEquals(large.size.toLong(),error.actualBytes);assertEquals(1024L*1024,error.limitBytes)}
            }
            assertEquals("alive",connection.exec("printf alive").output)
        } finally {connection.exec("rm -rf -- "+Tmux.quote(directory));connection.close()}
    }

    @Test fun realWebViewLoadsTheViewportSkipsFlingPathsAndReusesCachedImages() {
        val connection=fixture();val directory=connection.exec("mktemp -d /tmp/mtmux-window-test-XXXXXX").output.trim()
        val cacheRoot=File(compose.activity.cacheDir,"preview")
        val existing=cacheRoot.listFiles().orEmpty().map {it.name}.toSet()
        var showing by mutableStateOf(false)
        var target by mutableStateOf("")
        try {
            repeat(40) {i ->put(connection,"$directory/image$i.svg",("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"160\" height=\"180\"><metadata>$i</metadata><rect width=\"160\" height=\"180\" fill=\"green\"/></svg>").toByteArray())}
            val markdown="# Full document\n\n"+(0 until 40).joinToString("\n") {i ->
                "## Image $i\n\n<img alt=\"image$i\" src=\"image$i.svg\" width=\"160\" height=\"180\">\n\n"+"Readable text.\n\n".repeat(8)
            }+"<script>window.pwned=true</script>"
            val html="<title>Viewport HTML</title><style>section{height:520px}</style><h1>Full document</h1>"+(0 until 40).joinToString("") {i ->
                "<section><h2>Image $i</h2><img alt=\"image$i\" src=\"image$i.svg\" width=\"160\" height=\"180\"></section>"
            }+"<script>window.pwned=true</script>"
            put(connection,"$directory/index.md",markdown.toByteArray());put(connection,"$directory/index.html",html.toByteArray())
            compose.setContent {MaterialTheme(colorScheme=appColors(true)) {
                if(showing) FilePreviewDialog(PreviewRequest(target,"fixture",connection,null,"tmux","Fixture"),{showing=false},{fail("Unexpected navigation")})
            }}
            fun downloaded():Set<Int> = cacheRoot.listFiles().orEmpty().filter {it.name !in existing}.flatMap {it.listFiles().orEmpty().toList()}
                .mapNotNull {Regex("<metadata>(\\d+)</metadata>").find(it.readText())?.groupValues?.get(1)?.toInt()}.toSet()
            for(extension in listOf("md","html")) {
                compose.runOnIdle {target="$directory/index.$extension";showing=true}
                compose.waitUntil(30000) {webView()!=null && eval("document.querySelector('img[alt=image0]')?.naturalWidth")=="160"}
                assertEquals("40",eval("document.querySelectorAll('h2').length"))
                assertEquals("\"undefined\"",eval("typeof NativeTerminal"));assertEquals("\"undefined\"",eval("typeof pwned"))
                assertTrue("Offscreen images loaded on entry: ${downloaded()}",downloaded().all {it<5})
                val folder=cacheRoot.listFiles().orEmpty().single {it.name !in existing}
                val first=folder.listFiles().orEmpty().single {it.readText().contains("<metadata>0</metadata>")}.name
                eval("void (async()=>{for(let i=4;i<=36;i+=4){scrollTo(0,document.querySelectorAll('h2')[i].getBoundingClientRect().top+scrollY);await new Promise(r=>setTimeout(r,18));}})()")
                compose.waitUntil(30000) {eval("document.querySelector('img[alt=image36]')?.naturalWidth")=="160"}
                assertTrue("Fling downloaded intermediate pages: ${downloaded()}",downloaded().none {it in 5..30})
                eval("scrollTo(0,0)")
                compose.waitUntil(15000) {eval("document.querySelector('img[alt=image0]')?.naturalWidth")=="160"}
                assertTrue("Returning must reuse the same cached original",folder.listFiles().orEmpty().any {it.name==first && it.readText().contains("<metadata>0</metadata>")})
                compose.runOnIdle {showing=false}
                compose.waitUntil(10000) {cacheRoot.listFiles().orEmpty().all {it.name in existing}}
            }
            assertEquals("alive",connection.exec("printf alive").output)
        } finally {connection.exec("rm -rf -- "+Tmux.quote(directory));connection.close()}
    }
    private fun webView():WebView? {
        fun find(view:View):WebView? {
            if(view is FilePreviewWebView) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let {return it}
            return null
        }
        var result:WebView?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {result=WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {find(it)}}
        return result
    }
    private fun eval(script:String):String {
        val view=webView() ?: return ""
        val latch=CountDownLatch(1);var result=""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {view.evaluateJavascript(script) {result=it;latch.countDown()}}
        assertTrue("Preview did not respond",latch.await(10,TimeUnit.SECONDS));return result
    }
}
