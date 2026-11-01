package dev.mtmux

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mtmux.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PreviewInteractionTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun leftSwipeMovesTheSelectedDotRightAndWrapsWithoutChangingKeyGroups() {
        compose.setContent {MaterialTheme(colorScheme=appColors(true)) {Surface {TerminalKeys(true,0,{}, {})}}}
        fun selectedX():Float {
            val colors=appColors(true)
            val active=colors.onSurfaceVariant
            val image=compose.onNodeWithTag("terminal-key-dots").captureToImage().toPixelMap()
            val xs=mutableListOf<Int>()
            for(x in 0 until image.width) for(y in 0 until image.height) {
                val c=image[x,y]
                if(kotlin.math.abs(c.red-active.red)+kotlin.math.abs(c.green-active.green)+kotlin.math.abs(c.blue-active.blue)<.12f) xs.add(x)
            }
            assertTrue("No active dot pixels",xs.isNotEmpty())
            return xs.average().toFloat()
        }
        val first=selectedX()
        val pages=compose.onNodeWithTag("terminal-key-pages")
        pages.performTouchInput {swipeLeft()}
        compose.onNodeWithTag("terminal-key-Ctrl").assertIsDisplayed()
        val second=selectedX()
        assertTrue("Leftward swipe must move dot right",second>first)
        pages.performTouchInput {swipeRight()}
        assertEquals(first,selectedX(),1.5f)
        repeat(4) {pages.performTouchInput {swipeLeft()}}
        compose.onNodeWithTag("terminal-key-Esc").assertIsDisplayed()
        assertEquals(first,selectedX(),1.5f)
    }

    @Test fun downwardDragOnlyRefreshesWhenTheListIsAtTheTop() {
        var calls=0
        lateinit var scroll:androidx.compose.foundation.ScrollState
        compose.setContent {MaterialTheme {
            scroll=rememberScrollState()
            HomePullRefresh(false,true,{calls++},Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("refresh-list")) {
                    repeat(80) {Text("Server $it",Modifier.height(48.dp))}
                }
            }
        }}
        compose.runOnIdle {kotlinx.coroutines.runBlocking {scroll.scrollTo(1600)}}
        compose.onNodeWithTag("refresh-list").performTouchInput {swipeDown()}
        compose.runOnIdle {assertEquals(0,calls)}
        compose.runOnIdle {kotlinx.coroutines.runBlocking {scroll.scrollTo(0)}}
        compose.onNodeWithTag("refresh-list").performTouchInput {swipeDown()}
        compose.waitUntil(5000) {calls==1}
    }

    @Test fun homePullBypassesTheIntervalButNeverRefreshesCollapsedFolders() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Use scripts/test_ssh.py --android",args.containsKey("fixturePort"))
        val isolated=object:android.content.ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("preview-refresh-test-$name",mode)
        }
        val store=ServerProfiles(isolated)
        store.all().forEach {store.delete(it.id)}
        val organization=ServerOrganization(isolated)
        organization.folders().forEach {organization.deleteFolder(it.id)}
        val folder=organization.saveFolder(name="Folded fixture")
        organization.collapse(folder.id,true)
        fun profile(name:String)=ServerProfile(name=name,host="127.0.0.1",port=args.getString("fixturePort")!!.toInt(),
            user=args.getString("fixtureUser")!!,path=args.getString("fixtureTmux")!!,keyAuthentication=true)
        val visible=profile("Visible fixture");val folded=profile("Hidden fixture")
        val pool=WarmConnectionPool<SshClient>(isReusable={it.isReusable()},onIdle={it.setIdle(true)},onTake={it.setIdle(false)})
        val cache=ServerCache(isolated)
        try {
            listOf(visible,folded).forEach {p ->
                store.save(p,SavedCredentials(privateKey=Base64.getDecoder().decode(args.getString("fixtureKey"))),
                    p.trustEndpoints().associateWith {args.getString("fixtureHostKey")!!})
                cache.markAttempt(p)
            }
            organization.move(folded.id,folder.id)
            val prefs=isolated.getSharedPreferences("server-profiles",0)
            val before=prefs.getString("probe:${folded.id}",null)
            compose.setContent {CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides isolated) {
                MaterialTheme(colorScheme=appColors(true)) {
                    ServerHome(pool,privacyDisplay=true,onPrivacyDisplay={},refreshOnEntry=false,onEntryHandled={},onOpen={_,_,_->},
                        onPreferences={_,_,_->},appearance=AppAppearance.DARK,onAppearance={},palette=AppPalette.TOKYO,onPalette={})
                }
            }}
            compose.onNodeWithTag("home-pull-refresh").performTouchInput {swipeDown()}
            compose.waitUntil(30000) {cache.read(visible)?.tasks?.isNotEmpty()==true}
            assertNull(cache.read(folded))
            assertEquals(before,prefs.getString("probe:${folded.id}",null))
            assertFalse(cache.shouldRefresh(visible))
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("home-pull-refresh").fetchSemanticsNodes().single().config[SemanticsProperties.StateDescription]!=
                    isolated.getString(R.string.home_pull_refreshing)
            }
            compose.onNodeWithText("Refresh finished. Check server cards for results.").assertDoesNotExist()
            compose.onNodeWithText("刷新结束，请查看各服务器卡片的结果。").assertDoesNotExist()
        } finally {
            pool.close();store.delete(visible.id);store.delete(folded.id);organization.deleteFolder(folder.id)
        }
    }

    @Test fun remoteMarkdownAndRelativeImageRenderInAnIsolatedAndroidWebView() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Use scripts/test_ssh.py --android",args.containsKey("fixturePort"))
        val connection=SshClient(object:PinStore {override fun get(endpoint:String)=args.getString("fixtureHostKey")})
        connection.connect(Login("127.0.0.1",args.getString("fixturePort")!!.toInt(),args.getString("fixtureUser")!!,"",
            Base64.getDecoder().decode(args.getString("fixtureKey"))))
        val directory=connection.exec("mktemp -d /tmp/mtmux-preview-test-XXXXXX").output.trim()
        val cacheRoot=java.io.File(compose.activity.cacheDir,"preview")
        val existingCache=cacheRoot.listFiles()?.map {it.name}?.toSet().orEmpty()
        try {
            fun put(name:String,bytes:ByteArray) {assertEquals(0,connection.exec("cat > "+Tmux.quote("$directory/$name"),bytes).status)}
            put("readme.md","# Remote preview\n\n![image](pic.png)\n\n<script>document.title='UNSAFE'</script>\n\n[child](child.md)".toByteArray())
            put("child.md","# Child document".toByteArray())
            put("static.html","<title>Static HTML</title><h1>Static HTML</h1><script>document.title='UNSAFE'</script><p>Static CSS and local content.</p><img src='pic.png'>".toByteArray())
            val image=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.GREEN)
            val bytes=java.io.ByteArrayOutputStream().also {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
            image.recycle();put("pic.png",bytes)
            var showing by mutableStateOf(true)
            var requestPath by mutableStateOf("$directory/readme.md")
            compose.setContent {MaterialTheme(colorScheme=appColors(true,AppPalette.TOKYO)) {
                if(showing) key(requestPath) {FilePreviewDialog(PreviewRequest(requestPath,"fixture",connection,null,"tmux","Fixture"),{showing=false},{fail("Unexpected external navigation")})}
            }}
            compose.waitUntil(30000) {webView()!=null}
            compose.waitUntil(30000) {eval("document.querySelector('h1')?.textContent")=="\"Remote preview\""}
            assertEquals("\"undefined\"",eval("typeof NativeTerminal"))
            assertEquals("false",eval("document.title==='UNSAFE'"))
            compose.waitUntil(15000) {eval("document.querySelector('img')?.naturalWidth")=="8"}
            saveImage("markdown-preview")
            val imagePosition=org.json.JSONObject(org.json.JSONTokener(eval("JSON.stringify((()=>{const r=document.querySelector('img').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,scale:devicePixelRatio}})())")).nextValue().toString())
            compose.onNodeWithTag("preview-webview").performTouchInput {click(Offset((imagePosition.getDouble("x")*imagePosition.getDouble("scale")).toFloat(),(imagePosition.getDouble("y")*imagePosition.getDouble("scale")).toFloat()))}
            compose.waitUntil(15000) {compose.onAllNodesWithTag("preview-image").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_back)).performClick()
            compose.waitUntil(20000) {webView()!=null && eval("document.querySelector('h1')?.textContent")=="\"Remote preview\""}
            val position=org.json.JSONObject(org.json.JSONTokener(eval("JSON.stringify((()=>{const r=[...document.querySelectorAll('a')].find(a=>a.textContent==='child').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,scale:devicePixelRatio}})())")).nextValue().toString())
            compose.onNodeWithTag("preview-webview").performTouchInput {click(Offset((position.getDouble("x")*position.getDouble("scale")).toFloat(),(position.getDouble("y")*position.getDouble("scale")).toFloat()))}
            compose.waitUntil(20000) {webView()!=null && eval("document.querySelector('h1')?.textContent")=="\"Child document\""}
            compose.runOnIdle {requestPath="$directory/static.html"}
            // JS is intentionally disabled for HTML; do not use evaluateJavascript as a readiness test.
            compose.waitUntil(20000) {
                val view=webView()
                var title:String?=null
                if(view!=null) compose.runOnUiThread {title=view.title}
                title=="Static HTML"
            }
            val staticView=webView()!!
            compose.runOnUiThread {assertFalse(staticView.settings.javaScriptEnabled)}
            val painted=CountDownLatch(1)
            compose.runOnUiThread {staticView.postVisualStateCallback(1,object:WebView.VisualStateCallback() {
                override fun onComplete(requestId:Long) {painted.countDown()}
            })}
            assertTrue(painted.await(10,TimeUnit.SECONDS))
            saveImage("html-preview")
            compose.runOnIdle {requestPath="$directory/pic.png"}
            compose.waitUntil(15000) {compose.onAllNodesWithTag("preview-image").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("preview-image").assertIsDisplayed()
            val originalPixels=saveImage("image-preview")
            compose.onNodeWithTag("preview-image").performTouchInput {doubleClick(center)}
            assertTrue("Double tap must enlarge the image",saveImage("image-preview-zoomed")>originalPixels)
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_close)).performClick()
            compose.waitUntil(5000) {!showing}
            compose.waitUntil(5000) {cacheRoot.listFiles().orEmpty().all {it.name in existingCache}}
            assertEquals("still-alive",connection.exec("printf still-alive").output)
        } finally {connection.exec("rm -rf -- "+Tmux.quote(directory));connection.close()}
    }
    @Test fun absoluteAndFileUriImagesRenderFromTheSameRemoteInMarkdownAndStaticHtml() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Use scripts/test_ssh.py --android",args.containsKey("fixturePort"))
        val connection=SshClient(object:PinStore {override fun get(endpoint:String)=args.getString("fixtureHostKey")})
        var handshakes=0
        connection.observe {if(it.phase==ConnectionPhase.TCP_CONNECT && it.result==TraceResult.OK) handshakes++}
        connection.connect(Login("127.0.0.1",args.getString("fixturePort")!!.toInt(),args.getString("fixtureUser")!!,"",
            Base64.getDecoder().decode(args.getString("fixtureKey"))))
        val directory=connection.exec("mktemp -d /tmp/mtmux-absolute-test-XXXXXX").output.trim()
        val outside=connection.exec("mktemp -d /tmp/mtmux-absolute-outside-XXXXXX").output.trim()
        try {
            fun put(path:String,bytes:ByteArray) {assertEquals(0,connection.exec("cat > "+Tmux.quote(path),bytes).status)}
            fun image(color:Int):ByteArray {
                val bitmap=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(color)}
                return java.io.ByteArrayOutputStream().also {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it);bitmap.recycle()}.toByteArray()
            }
            val imagePath="$directory/绝对 path #.png"
            val encoded=java.net.URI(null,null,imagePath,null).toASCIIString()
            val blueEncoded=java.net.URI(null,null,"$directory/blue.png",null).toASCIIString()
            put(imagePath,image(android.graphics.Color.GREEN))
            put("$directory/blue.png",image(android.graphics.Color.BLUE))
            put("$outside/outside.png",image(android.graphics.Color.GREEN))
            assertEquals(0,connection.exec("ln -s "+Tmux.quote("$outside/outside.png")+" "+Tmux.quote("$directory/escape.png")).status)
            put("$directory/readme.md",("# Absolute Markdown\n\n"+
                "![absolute]($encoded)\n\n![file](file://$encoded)\n\n![localhost](file://localhost$encoded)\n\n"+
                "![same-host](file://127.0.0.1$encoded)\n\n<img alt='entity' src='file&#58;//$encoded'>\n\n"+
                "![foreign](file://different.example$encoded)\n\n![outside]($outside/outside.png)\n\n![escape]($directory/escape.png)").toByteArray())
            put("$directory/static.html",("<title>Absolute HTML</title><h1>Absolute HTML</h1>"+
                "<img src='$encoded'><img src='file&#58;//$blueEncoded'>"+
                "<script>document.title='UNSAFE'</script>").toByteArray())
            var target by mutableStateOf("$directory/readme.md")
            var showing by mutableStateOf(true)
            compose.setContent {MaterialTheme(colorScheme=appColors(true,AppPalette.TOKYO)) {
                if(showing) key(target) {FilePreviewDialog(PreviewRequest(target,"absolute-fixture",connection,null,"tmux","Fixture",host="127.0.0.1"),{showing=false},{fail("Unexpected browser launch")})}
            }}
            compose.waitUntil(30000) {webView()!=null && eval("document.querySelector('h1')?.textContent")=="\"Absolute Markdown\""}
            compose.waitUntil(15000) {eval("['absolute','file','localhost','same-host','entity'].every(alt=>document.querySelector('img[alt=\"'+alt+'\"]').naturalWidth===8)")=="true"}
            assertEquals("true",eval("['absolute','file','localhost','same-host','entity'].every(alt=>document.querySelector('img[alt=\"'+alt+'\"]').src.startsWith('https://preview.mtmux.invalid/'))"))
            assertEquals("null",eval("document.querySelector('img[alt=foreign]').getAttribute('src')"))
            assertEquals("true",eval("['outside','escape'].every(alt=>document.querySelector('img[alt=\"'+alt+'\"]').naturalWidth===0)"))
            saveImage("absolute-markdown")
            compose.runOnIdle {target="$directory/static.html"}
            compose.waitUntil(20000) {
                val view=webView();var title:String?=null
                if(view!=null) compose.runOnUiThread {title=view.title}
                title=="Absolute HTML"
            }
            val htmlView=webView()!!
            compose.runOnUiThread {assertFalse(htmlView.settings.javaScriptEnabled)}
            val painted=CountDownLatch(1)
            compose.runOnUiThread {htmlView.postVisualStateCallback(2,object:WebView.VisualStateCallback() {
                override fun onComplete(requestId:Long) {painted.countDown()}
            })}
            assertTrue(painted.await(10,TimeUnit.SECONDS))
            saveImage("absolute-html",intArrayOf(android.graphics.Color.BLUE))
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_close)).performClick()
            compose.waitUntil(5000) {!showing}
            assertEquals("alive",connection.exec("printf alive").output)
            assertEquals(1,handshakes)
        } finally {connection.exec("rm -rf -- "+Tmux.quote(directory)+" "+Tmux.quote(outside));connection.close()}
    }

    @Test fun failedPathCanBeReplacedWithACompleteFilePathAndTheEditButtonRecovers() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Use scripts/test_ssh.py --android",args.containsKey("fixturePort"))
        val connection=SshClient(object:PinStore {override fun get(endpoint:String)=args.getString("fixtureHostKey")})
        connection.connect(Login("127.0.0.1",args.getString("fixturePort")!!.toInt(),args.getString("fixtureUser")!!,"",Base64.getDecoder().decode(args.getString("fixtureKey"))))
        val directory=connection.exec("mktemp -d /tmp/mtmux-preview-path-XXXXXX").output.trim()
        val correct="$directory/report.md"
        val missing="$directory/wrong/out-test/README.md"
        try {
            assertEquals(0,connection.exec("cat > "+Tmux.quote(correct),"# Corrected file path".toByteArray()).status)
            compose.setContent {MaterialTheme(colorScheme=appColors(true,AppPalette.TOKYO)) {
                FilePreviewDialog(PreviewRequest(missing,"path-fixture",connection,null,"tmux","Fixture"),{},{fail("Unexpected external link")})
            }}
            compose.waitUntil(20000) {compose.onAllNodesWithText(compose.activity.getString(R.string.preview_not_found)).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("preview-edit-path").assertDoesNotExist()
            compose.onNodeWithTag("preview-path").assertTextContains(missing).assertHeightIsAtLeast(72.dp)
            compose.onAllNodesWithText(missing).assertCountEquals(1) // Only the editor, no duplicate standalone path label.
            saveImage("preview-file-path-error",requireGreen=false)
            compose.onNodeWithTag("preview-path").performTextReplacement(correct)
            compose.onNodeWithTag("preview-open-path").performClick()
            compose.waitUntil(20000) {webView()!=null && eval("document.querySelector('h1')?.textContent")=="\"Corrected file path\""}
            compose.onNodeWithTag("preview-path").assertDoesNotExist()
            compose.onNodeWithTag("preview-edit-path").performClick()
            compose.onNodeWithTag("preview-path").assertTextContains(correct)
            saveImage("preview-file-path-corrected",requireGreen=false)
            compose.onNodeWithTag("preview-edit-path").performClick()
            compose.onNodeWithTag("preview-path").assertDoesNotExist()
            assertEquals("alive",connection.exec("printf alive").output)
        } finally {connection.exec("rm -rf -- "+Tmux.quote(directory));connection.close()}
    }

    @Test fun jpegAndSvgRenderAndSvgUsesAnIsolatedZoomableImageSurface() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Use scripts/test_ssh.py --android",args.containsKey("fixturePort"))
        val connection=SshClient(object:PinStore {override fun get(endpoint:String)=args.getString("fixtureHostKey")})
        connection.connect(Login("127.0.0.1",args.getString("fixturePort")!!.toInt(),args.getString("fixtureUser")!!,"",Base64.getDecoder().decode(args.getString("fixtureKey"))))
        val directory=connection.exec("mktemp -d /tmp/mtmux-preview-svg-XXXXXX").output.trim()
        try {
            fun put(name:String,bytes:ByteArray) {assertEquals(0,connection.exec("cat > "+Tmux.quote("$directory/$name"),bytes).status)}
            fun image(color:Int,format:android.graphics.Bitmap.CompressFormat):ByteArray {
                val b=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(color)}
                return java.io.ByteArrayOutputStream().also {b.compress(format,100,it);b.recycle()}.toByteArray()
            }
            val jpeg=image(android.graphics.Color.GREEN,android.graphics.Bitmap.CompressFormat.JPEG)
            put("photo.jpeg",jpeg);put("photo.jpg",jpeg)
            put("blue.png",image(android.graphics.Color.BLUE,android.graphics.Bitmap.CompressFormat.PNG))
            put("drawing.svg",("""<svg xmlns="http://www.w3.org/2000/svg" width="160" height="120" viewBox="0 0 160 120">
                <rect id="green" width="80" height="80" fill="#00ff00"/>
                <script>document.getElementById('green').setAttribute('fill','#ff00ff');</script>
                <image href="blue.png" x="90" width="40" height="40"/>
                </svg>""").toByteArray())
            put("readme.md","# Vector image\n\n![vector](drawing.svg)".toByteArray())
            var target by mutableStateOf("$directory/photo.jpeg")
            compose.setContent {MaterialTheme(colorScheme=appColors(true,AppPalette.TOKYO)) {
                key(target) {FilePreviewDialog(PreviewRequest(target,"image-fixture",connection,null,"tmux","Fixture"),{},{fail("Unexpected external link")})}
            }}
            for(name in listOf("photo.jpeg","photo.jpg")) {
                compose.runOnIdle {target="$directory/$name"}
                compose.waitUntil(15000) {compose.onAllNodesWithTag("preview-image").fetchSemanticsNodes().isNotEmpty()}
                saveImage("preview-$name")
            }
            compose.runOnIdle {target="$directory/drawing.svg"}
            compose.waitUntil(15000) {compose.onAllNodesWithTag("preview-svg").fetchSemanticsNodes().isNotEmpty() && webView()!=null}
            val svg=webView()!!
            compose.waitUntil(20000) {
                var ready=false
                compose.runOnUiThread {ready=svg.title=="SVG preview" && svg.progress==100}
                ready
            }
            compose.runOnUiThread {assertFalse(svg.settings.javaScriptEnabled);assertTrue(svg.settings.builtInZoomControls)}
            val painted=CountDownLatch(1)
            compose.runOnUiThread {svg.postVisualStateCallback(10,object:WebView.VisualStateCallback() {override fun onComplete(requestId:Long) {painted.countDown()}})}
            assertTrue(painted.await(10,TimeUnit.SECONDS))
            saveImage("svg-preview",forbiddenColors=intArrayOf(android.graphics.Color.BLUE,android.graphics.Color.MAGENTA))
            var scale=0f
            @Suppress("DEPRECATION")
            compose.runOnUiThread {scale=svg.scale;assertTrue(svg.zoomIn())}
            compose.waitUntil(5000) {
                var zoomed=false
                @Suppress("DEPRECATION")
                compose.runOnUiThread {zoomed=svg.scale>scale*1.1f}
                zoomed
            }
            compose.runOnIdle {target="$directory/readme.md"}
            compose.waitUntil(20000) {webView()!=null && eval("document.querySelector('img')?.naturalWidth")=="160"}
            saveImage("markdown-svg",forbiddenColors=intArrayOf(android.graphics.Color.BLUE,android.graphics.Color.MAGENTA))
            assertEquals("alive",connection.exec("printf alive").output)
        } finally {connection.exec("rm -rf -- "+Tmux.quote(directory));connection.close()}
    }

    private fun saveImage(name:String,extraColors:IntArray=intArrayOf(),forbiddenColors:IntArray=intArrayOf(),requireGreen:Boolean=true):Int {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(750) // SurfaceFlinger and Dialog transitions are not driven by Compose's clock.
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val output=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val folder=java.io.File(output?.let {java.io.File(it)} ?: compose.activity.getExternalFilesDir(null),"preview-evidence").apply {mkdirs()}
        java.io.File(folder,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val green=pixels.count {android.graphics.Color.green(it)>180 && android.graphics.Color.red(it)<40 && android.graphics.Color.blue(it)<40}
        for(color in extraColors) {
            val count=pixels.count {kotlin.math.abs(android.graphics.Color.red(it)-android.graphics.Color.red(color))<20 &&
                kotlin.math.abs(android.graphics.Color.green(it)-android.graphics.Color.green(color))<20 &&
                kotlin.math.abs(android.graphics.Color.blue(it)-android.graphics.Color.blue(color))<20}
            assertTrue("$name did not paint expected color $color (pixels=$count)",count>8)
        }
        for(color in forbiddenColors) {
            val count=pixels.count {kotlin.math.abs(android.graphics.Color.red(it)-android.graphics.Color.red(color))<20 &&
                kotlin.math.abs(android.graphics.Color.green(it)-android.graphics.Color.green(color))<20 &&
                kotlin.math.abs(android.graphics.Color.blue(it)-android.graphics.Color.blue(color))<20}
            assertTrue("$name painted a forbidden script/external-resource color $color (pixels=$count)",count<8)
        }
        bitmap.recycle()
        if(requireGreen) assertTrue("$name did not paint its green fixture image (pixels=$green)",green>8)
        return green
    }
    private fun webView():WebView? {
        fun find(v:View):WebView? {
            if(v is FilePreviewWebView) return v
            if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i))?.let {return it}
            return null
        }
        var result:WebView?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {result=WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {find(it)}}
        return result
    }
    private fun eval(script:String):String {
        val latch=CountDownLatch(1);var result=""
        val view=webView() ?: return ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {view.evaluateJavascript(script) {result=it;latch.countDown()}}
        assertTrue("Preview JS did not respond",latch.await(10,TimeUnit.SECONDS))
        return result
    }
}
