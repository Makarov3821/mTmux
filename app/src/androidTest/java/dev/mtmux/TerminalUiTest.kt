package dev.mtmux

import android.view.View
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.WindowManager
import android.webkit.WebView
import java.io.File
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Rule
import dev.mtmux.core.discoverTaskSnapshots
import org.junit.Test
import org.junit.Assume.assumeTrue
import androidx.test.platform.app.InstrumentationRegistry
import dev.mtmux.core.Login
import java.util.Base64
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TerminalUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Component-only rendering tests expose the terminal without a real SSH login. */
    private fun showTerminalForFixture() {
        compose.runOnUiThread {
            val field = MainActivity::class.java.getDeclaredField("showConfig\$delegate")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(compose.activity) as androidx.compose.runtime.MutableState<Boolean>).value = false
            val status=MainActivity::class.java.getDeclaredField("status\$delegate").apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST")
            (status.get(compose.activity) as androidx.compose.runtime.MutableState<String>).value=""
        }
        compose.waitForIdle()
    }

    private fun findTerminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findTerminal(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun terminal(): TerminalView {
        var result: TerminalView? = null
        compose.runOnUiThread { result = findTerminal(compose.activity.window.decorView) }
        return requireNotNull(result)
    }

    private fun evaluate(view: TerminalView, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread { view.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("JavaScript did not respond", done.await(10, TimeUnit.SECONDS))
        return result
    }

    /** Verify actual window pixels, not just xterm's internal text buffer. */
    private fun assertPaintedGreenText(view: TerminalView, name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Compose's test clock does not advance SurfaceFlinger/dialog transitions.
        Thread.sleep(750)
        assertEquals("Terminal container must fill the WebView viewport", "true", evaluate(view,
            "terminal.rows > 1 && Math.abs(document.getElementById('terminal').getBoundingClientRect().height-innerHeight) < 2"))
        println("TERMINAL_GEOMETRY native=${view.width}x${view.height} web=" + evaluate(view,
            "JSON.stringify({cols:terminal.cols,rows:terminal.rows,viewport:[innerWidth,innerHeight],container:(()=>{const r=document.getElementById('terminal').getBoundingClientRect();return [r.width,r.height]})(),screen:(()=>{const r=document.querySelector('.xterm-screen').getBoundingClientRect();return [r.width,r.height]})()})"))
        val ready = CountDownLatch(1)
        val rect = Rect()
        compose.runOnUiThread {
            view.getGlobalVisibleRect(rect)
            view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { view.invalidate(); ready.countDown() }
            })
        }
        assertTrue("WebView did not produce a visual frame", ready.await(20, TimeUnit.SECONDS))
        assertTrue("Terminal is outside the visible window: $rect", rect.width() > 40 && rect.height() > 40)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // AGP pulls this directory before uninstalling the test app.
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val folder = File(output?.let { File(it) } ?: compose.activity.getExternalFilesDir(null), "ui-evidence").apply { mkdirs() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var greenPixels = 0
        do {
            Thread.sleep(250)
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: continue
            greenPixels = 0
            // Inspect only the two fixture text rows, not green buttons/overlays elsewhere.
            val density = view.resources.displayMetrics.density
            for (y in maxOf(0, rect.top + 3) until minOf(bitmap.height, rect.bottom - 3, rect.top + (32 * density).toInt())) {
                for (x in maxOf(0, rect.left + 3) until minOf(bitmap.width, rect.right - 3, rect.left + (250 * density).toInt())) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.green(pixel) > 70 && Color.green(pixel) > Color.red(pixel) + 25 &&
                        Color.green(pixel) > Color.blue(pixel) + 20) greenPixels++
                }
            }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            if (greenPixels >= 40) return
        } while (System.nanoTime() < deadline)
        fail("Buffer contains text but Android terminal pixels are blank ($greenPixels green pixels); screenshot: $folder/$name.png")
    }

    private fun disconnectFixture() {
        // Disconnect is no longer a product button; still exercise stale-output protection.
        compose.runOnUiThread {
            val method=MainActivity::class.java.getDeclaredMethod("disconnect",String::class.java,Boolean::class.javaPrimitiveType)
            method.isAccessible=true
            method.invoke(compose.activity,"已断开；不会自动重发输入",true)
        }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(750)
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val folder = File(output?.let { File(it) } ?: compose.activity.getExternalFilesDir(null), "ui-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun lightDarkAndSystemThemesPersistAndKeepTerminalContent() {
        val prefs=compose.activity.getSharedPreferences("terminal-settings",0)
        val mode=compose.activity.getSystemService(android.app.UiModeManager::class.java)
        try {
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("theme-LIGHT").performClick().assertIsSelected()
            saveScreenshot("settings-light")
            compose.onNodeWithText("‹ 返回").performClick()
            saveScreenshot("home-light")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("theme-LIGHT").assertIsSelected()
            val view=terminal()
            compose.waitUntil(30000) { evaluate(view,"typeof setTerminalAppearance")=="\"function\"" }
            assertEquals("\"#f7faf8\"",evaluate(view,"terminal.options.theme.background"))
            compose.onNodeWithTag("theme-SYSTEM").performClick()
            val activity=compose.activity
            compose.runOnUiThread { mode.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_YES) }
            compose.waitUntil(10000) { evaluate(view,"terminal.options.theme.background")=="\"#111916\"" }
            assertSame(activity,compose.activity)
            compose.runOnUiThread { mode.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_NO) }
            compose.waitUntil(10000) { evaluate(view,"terminal.options.theme.background")=="\"#f7faf8\"" }
            compose.onNodeWithTag("theme-DARK").performClick()
            compose.waitUntil(10000) { evaluate(view,"terminal.options.theme.foreground")=="\"#e2eee8\"" }
            compose.onNodeWithTag("theme-LIGHT").performClick()
            compose.onNodeWithText("‹ 返回").performClick()
            showTerminalForFixture()
            compose.runOnUiThread { view.connection("theme-test") }
            view.render("浅色终端 Light terminal\r\n\u001b[31mError red\u001b[0m / \u001b[32mSuccess green\u001b[0m".toByteArray(),"theme-test")
            saveScreenshot("terminal-light")
            compose.onNodeWithText("工具").performClick()
            compose.onNodeWithTag("select-terminal-text").performScrollTo().performClick()
            compose.onNodeWithText("选择复制").assertExists()
            compose.runOnUiThread {
                val text=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOf {it.findViewWithTag<android.widget.TextView>("terminal-copy-text")}
                assertEquals(0xFF18251E.toInt(),text.currentTextColor)
            }
            saveScreenshot("copy-light")
            compose.onNodeWithTag("close-terminal-copy").performClick()
            assertTrue(evaluate(view,"terminalSnapshot().text").contains("浅色终端"))
        } finally {
            prefs.edit().remove("appearance").commit()
            compose.runOnUiThread { mode.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_AUTO) }
            compose.activityRule.scenario.recreate()
        }
    }

    @Test fun pastedPrivateKeySupportsClipboardCancellationAndPersistence() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureKey"))
        val store=ServerProfiles(compose.activity)
        store.all().forEach {store.delete(it.id)}
        val key=Base64.getDecoder().decode(args.getString("fixtureKey"))
        val profile=ServerProfile(name="paste-key-test",host="127.0.0.1",port=9,user="test",path="tmux",keyAuthentication=true)
        store.save(profile,SavedCredentials("",key,"original-passphrase"))
        ServerCache(compose.activity).markAttempt(profile)
        try {
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("more-${profile.id}").performClick()
            compose.onNodeWithText("编辑服务器").performClick()
            compose.onNodeWithTag("server-key-file").performScrollTo().assertExists()
            compose.onNodeWithTag("server-key-paste").performScrollTo().performClick()
            compose.onNodeWithTag("private-key-text").performTextReplacement("ssh-ed25519 AAAA public")
            compose.onNodeWithTag("confirm-key-paste").performClick()
            compose.onNodeWithText("请粘贴完整私钥",substring=true).assertExists()
            compose.onAllNodesWithText("取消").onLast().performClick()
            compose.onNodeWithTag("server-key-paste").performScrollTo().performClick()
            compose.runOnUiThread {
                (compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("fixture",key.toString(Charsets.UTF_8)))
            }
            compose.onNodeWithTag("paste-key-clipboard").performClick()
            compose.onNodeWithTag("confirm-key-paste").performClick()
            compose.onNodeWithTag("server-secret").performScrollTo().performTextReplacement("paste-passphrase-marker")
            compose.runOnUiThread {
                (compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0)
            }
            Thread.sleep(1000)
            saveScreenshot("private-key-auth")
            compose.onNodeWithTag("save-server").performClick()
            assertArrayEquals(key,store.credentials(profile)!!.privateKey)
            assertEquals("paste-passphrase-marker",store.credentials(profile)!!.passphrase)
            val log=DebugLog.snapshot().toString(Charsets.UTF_8)
            assertTrue(log.contains("KEY_PASTE"))
            assertFalse(log.contains("paste-passphrase-marker"))
            assertFalse(log.contains("BEGIN OPENSSH"))
        } finally {store.delete(profile.id)}
    }

    @Test fun debugLogsAreRedactedBoundedAndExportable() {
        DebugLog.clear()
        DebugLog.event(DebugLog.Event.CONNECTION_FAILED,7,error=IllegalStateException("SECRET_MARKER password=abc key=PRIVATE command=echo-private"))
        DebugLog.stage("SECRET_PROGRESS host=example.invalid",7)
        val snapshot=DebugLog.snapshot().toString(Charsets.UTF_8)
        assertTrue(snapshot.contains("CONNECTION_FAILED"));assertTrue(snapshot.contains("processStartedAt="))
        assertFalse(snapshot.contains("SECRET_MARKER"));assertFalse(snapshot.contains("SECRET_PROGRESS"))
        assertFalse(snapshot.contains("echo-private"));assertFalse(snapshot.contains("example.invalid"))
        assertTrue(snapshot.toByteArray().size<10*1024*1024+1024)
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithText("首页任务状态灯").assertDoesNotExist()
        compose.onNodeWithTag("export-debug-log").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("最多保留最近 10 MiB",substring=true).assertExists()
        saveScreenshot("debug-log-settings")
        compose.onNodeWithTag("export-debug-log").performClick()
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun saveNode(): android.view.accessibility.AccessibilityNodeInfo? {
            val root=automation.rootInActiveWindow ?: return null
            val queue=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>();queue.add(root)
            while(queue.isNotEmpty()) {
                val node=queue.removeFirst()
                if(node.text?.toString()?.equals("save",true)==true && node.isEnabled && node.isClickable) return node
                for(j in 0 until node.childCount) node.getChild(j)?.let {queue.add(it)}
            }
            return null
        }
        compose.waitUntil(10000) {saveNode()!=null}
        assertTrue(saveNode()!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(10000) {compose.onAllNodesWithText("日志已保存，可作为附件反馈问题").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("clear-debug-log").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("历史日志已清空，后续事件继续记录").fetchSemanticsNodes().isNotEmpty()}
        assertFalse(DebugLog.snapshot().toString(Charsets.UTF_8).contains("CONNECTION_FAILED"))
    }

    @Test fun homeTaskLightsPersistExpireAndAggregateWithoutStoringText() {
        val store=ServerProfiles(compose.activity)
        store.all().forEach {store.delete(it.id)}
        val profile=ServerProfile(name="状态灯示例",host="127.0.0.1",port=9,user="test",path="tmux",keyAuthentication=false)
        store.save(profile,SavedCredentials("test"))
        val tasks=(0..4).map {i -> RecentTask(profile.id,profile.host,9,"test","tmux","pin","1:2:${'$'}0:@$i:%$i:${100+i}","Agent","任务 $i")}
        val states=listOf(dev.mtmux.core.TaskState.ATTENTION,dev.mtmux.core.TaskState.RUNNING,dev.mtmux.core.TaskState.COMPLETED,dev.mtmux.core.TaskState.UNKNOWN,dev.mtmux.core.TaskState.COMPLETED)
        val sibling=tasks[4].copy(identity="1:2:${'$'}0:@4:%5:105")
        val cache=ServerCache(compose.activity)
        try {
            val written=cache.write(profile,tasks+ sibling,tasks.zip(states).associate {it.first.identity!! to it.second}+mapOf(sibling.identity!! to dev.mtmux.core.TaskState.UNKNOWN))
            cache.markAttempt(profile)
            compose.activityRule.scenario.recreate()
            tasks.forEachIndexed {i,task ->
                val expected=if(i==4) dev.mtmux.core.TaskState.UNKNOWN else states[i]
                compose.onNodeWithTag("task-status-${profile.id}-${task.binding!!.pane}",useUnmergedTree=true).performScrollTo().assertContentDescriptionEquals("上次刷新：${expected.label}")
                assertEquals(dev.mtmux.core.TaskState.UNKNOWN,written.taskState(task,written.updatedAt+30*60*1000L))
            }
            assertEquals(dev.mtmux.core.TaskState.COMPLETED,ServerCache(compose.activity).read(profile)!!.taskState(tasks[2]))
            saveScreenshot("home-task-lights")
            compose.onNodeWithTag("task-${profile.id}-%4").performScrollTo().performClick()
            compose.onNodeWithTag("pane-status-${profile.id}-%5",useUnmergedTree=true).performScrollTo().assertContentDescriptionEquals("上次刷新：未知或已过期")
            // A failed refresh must not continue displaying a green cached success.
            compose.onNodeWithTag("refresh-${profile.id}").performScrollTo().performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("显示上次缓存",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("task-status-${profile.id}-%2",useUnmergedTree=true).assertContentDescriptionEquals("上次刷新：未知或已过期")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("task-status-${profile.id}-%2",useUnmergedTree=true).assertContentDescriptionEquals("上次刷新：未知或已过期")
        } finally {store.delete(profile.id)}
    }

    @Test fun taskStatusDiscoveryReadsExactPaneWithoutChangingFocus() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureTmux"))
        val key=Base64.getDecoder().decode(args.getString("fixtureKey"))
        val profile=ServerProfile(name="status-probe",host="127.0.0.1",port=args.getString("fixturePort")!!.toInt(),user=args.getString("fixtureUser")!!,path=args.getString("fixtureTmux")!!,keyAuthentication=true)
        val client=dev.mtmux.core.SshClient(object:dev.mtmux.core.PinStore {override fun get(endpoint:String)=args.getString("fixtureHostKey")})
        val tmux=dev.mtmux.core.Tmux.quote(profile.path)
        var session=""
        try {
            client.connect(dev.mtmux.core.Login(profile.host,profile.port,profile.user,"",key))
            session=client.exec("$tmux new-session -d -P -F '#{session_id}' -s phone-status-fixture 'printf \"Reply complete\\n› Ask Codex to do anything\\nGPT-6-Astra medium · ~/project  ⚠ 5 · f2\\n\"; sleep 120'").output.trim()
            assertTrue(session.startsWith("$"))
            Thread.sleep(250)
            val tasks=client.discoverTasks(profile,args.getString("fixtureHostKey")!!).filter {it.binding?.session==session}
            assertEquals(1,tasks.size)
            val batch = client.discoverTaskSnapshots(profile.path).filter { it.binding.session == session }
            assertEquals(tasks.map { it.copy(usedAt=0) }, batch.recentTasks(profile, args.getString("fixtureHostKey")!!).map { it.copy(usedAt=0) })
            assertEquals(dev.mtmux.core.TaskState.COMPLETED, batch.single().state)
            val bound=client.bindPane(session,profile.path)
            val before=client.exec("$tmux display-message -p -t ${dev.mtmux.core.Tmux.quote(session)} '#{window_id}:#{pane_id}:#{pane_in_mode}'").output
            assertEquals(dev.mtmux.core.TaskState.COMPLETED,client.discoverTaskStates(tasks,profile.path)[tasks.single().identity])
            assertEquals(before,client.exec("$tmux display-message -p -t ${dev.mtmux.core.Tmux.quote(session)} '#{window_id}:#{pane_id}:#{pane_in_mode}'").output)
            val dead=tasks.single().copy(identity=bound.identity.substringBeforeLast(':')+":999999")
            assertEquals(dev.mtmux.core.TaskState.UNKNOWN,client.discoverTaskStates(listOf(dead),profile.path)[dead.identity])
            val cache=ServerCache(compose.activity)
            cache.write(profile,tasks,client.discoverTaskStates(tasks,profile.path))
            val raw=compose.activity.getSharedPreferences("server-profiles",0).getString("cache:${profile.id}","")!!
            assertFalse(raw.contains("Ask Codex to do anything"))
            // A refresh newer than the last minute tick must light immediately, without recreation.
            val store = ServerProfiles(compose.activity)
            store.all().forEach { store.delete(it.id) }
            store.save(profile, SavedCredentials("",key,""))
            compose.activity.getSharedPreferences("host-pins",0).edit().putString(profile.trustEndpoint(),args.getString("fixtureHostKey")).commit()
            cache.write(profile,tasks)
            cache.markAttempt(profile)
            compose.activityRule.scenario.recreate()
            val tag = "task-status-${profile.id}-${bound.pane}"
            compose.onNodeWithTag(tag,useUnmergedTree=true).assertContentDescriptionEquals("上次刷新：未知或已过期")
            compose.onNodeWithTag("refresh-${profile.id}").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose.onAllNodes(hasTestTag(tag) and hasContentDescription("上次刷新：${dev.mtmux.core.TaskState.COMPLETED.label}"),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
            }
            saveScreenshot("home-status-immediate")
        } finally {
            if(session.startsWith("$")) client.exec("$tmux kill-session -t ${dev.mtmux.core.Tmux.quote(session)}")
            client.close();ServerProfiles(compose.activity).delete(profile.id)
        }
    }

    @Test fun bottomQuickRepliesNeverSendWithoutConfirmation() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureTmux"))
        val store=ServerProfiles(compose.activity)
        store.all().forEach {store.delete(it.id)}
        val profile=ServerProfile(name="Agent fixture",host="127.0.0.1",port=args.getString("fixturePort")!!.toInt(),user=args.getString("fixtureUser")!!,path=args.getString("fixtureTmux")!!,keyAuthentication=true)
        store.save(profile,SavedCredentials("",Base64.getDecoder().decode(args.getString("fixtureKey")),""))
        ServerCache(compose.activity).markAttempt(profile)
        compose.activity.getSharedPreferences("host-pins",0).edit().putString(profile.trustEndpoint(),args.getString("fixtureHostKey")).commit()
        val prefs=compose.activity.getSharedPreferences("terminal-settings",0)
        prefs.edit().remove("quick-replies").commit()
        try {
            compose.activityRule.scenario.recreate()
            val view=terminal()
            val scriptErrors=java.util.concurrent.CopyOnWriteArrayList<String>()
            compose.runOnUiThread {
                val previous=view.webChromeClient
                view.webChromeClient=object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        if(message.messageLevel()==android.webkit.ConsoleMessage.MessageLevel.ERROR) scriptErrors.add(message.message())
                        return previous?.onConsoleMessage(message) ?: true
                    }
                }
            }
            compose.onNodeWithTag("ssh-${profile.id}").performClick()
            try { compose.waitUntil(30000) { evaluate(view,"typeof connected !== 'undefined' && connected")=="true" && evaluate(view,"terminalSnapshot().text").contains("MTMUX_SSH_WELCOME") } }
            catch(error: Throwable) {saveScreenshot("failure-agent-status");throw AssertionError("Synthetic terminal script errors: $scriptErrors",error)}
            val token=evaluate(view,"connectionToken").trim('"')
            evaluate(view,"window.replyInputs=[];terminal.onData(d=>replyInputs.push(d));")
            compose.onNodeWithTag("agent-card").assertDoesNotExist()
            val keyboardButton=compose.onNodeWithTag("hide-keyboard").fetchSemanticsNode().boundsInRoot
            val repliesButton=compose.onNodeWithTag("quick-replies").fetchSemanticsNode().boundsInRoot
            val sendButton=compose.onNodeWithTag("send-enter").fetchSemanticsNode().boundsInRoot
            assertTrue(keyboardButton.right<=repliesButton.left && repliesButton.right<=sendButton.left)
            assertEquals(keyboardButton.center.y,repliesButton.center.y,1f)
            saveScreenshot("terminal-bottom-replies")
            compose.onNodeWithTag("quick-replies").performClick()
            compose.onNodeWithTag("quick-reply-0").performClick()
            compose.onNodeWithTag("command-input").assertTextContains("继续")
            compose.onNodeWithTag("quick-replies").performClick()
            compose.onNodeWithTag("quick-reply-1").performClick()
            compose.onNodeWithText("保留已有草稿？").assertExists()
            compose.onNodeWithTag("append-quick-reply").performClick()
            compose.onNodeWithTag("command-input").assertTextContains("继续\n请总结当前进展和剩余问题。")
            compose.onNodeWithTag("quick-replies").performClick()
            compose.onNodeWithTag("edit-quick-reply-0").performClick()
            compose.onNodeWithTag("quick-reply-editor").performTextReplacement("我的常用回复")
            compose.onNodeWithTag("save-quick-reply").performClick()
            assertEquals("我的常用回复",QuickReplies(compose.activity).all().first())
            saveScreenshot("quick-replies")
            compose.onNodeWithTag("quick-reply-0").performClick()
            compose.onNodeWithTag("replace-quick-reply").performClick()
            compose.onNodeWithTag("command-input").assertTextContains("我的常用回复")
            assertEquals("[]",evaluate(view,"replyInputs"))
            compose.onNodeWithTag("quick-replies").performClick()
            disconnectFixture()
            compose.onNodeWithTag("quick-reply-0").performClick()
            compose.onNodeWithTag("command-input").assertTextContains("我的常用回复")
            compose.onNodeWithText("连接已改变；快捷回复未填入").assertExists()
        } finally {
            store.delete(profile.id)
            prefs.edit().remove("quick-replies").commit()
        }
    }

    @Test fun connectionErrorsIdentifyTargetAndJumpWithoutCredentialText() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureTmux"))
        val port=args.getString("fixturePort")!!.toInt()
        val user=args.getString("fixtureUser")!!
        val store=ServerProfiles(compose.activity)
        store.all().forEach { store.delete(it.id) }
        val target=ServerProfile(name="认证失败测试",host="127.0.0.1",port=port,user=user,path="tmux",keyAuthentication=false)
        val jump=JumpHost(host="127.0.0.1",port=port,user=user,keyAuthentication=true)
        val via=target.copy(id="error-jump-fixture",name="跳板错误测试",jumps=listOf(jump))
        store.save(target,SavedCredentials("DO_NOT_DISPLAY_PASSWORD"))
        store.save(via,SavedCredentials("DO_NOT_DISPLAY_PASSWORD",jumps=mapOf(jump.id to SavedCredentials("","PRIVATE_KEY_MARKER".toByteArray(),""))))
        val cache=ServerCache(compose.activity)
        listOf(target,via).forEach { cache.markAttempt(it) }
        compose.activity.getSharedPreferences("host-pins",0).edit().putString(target.trustEndpoint(),args.getString("fixtureHostKey")).commit()
        try {
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("ssh-${target.id}").performClick()
            try { compose.waitUntil(20000) { compose.onAllNodesWithText("目标服务器：认证失败",substring=true).fetchSemanticsNodes().isNotEmpty() } }
            catch(error: Throwable) {saveScreenshot("failure-auth-status");throw error}
            compose.onNodeWithText("DO_NOT_DISPLAY_PASSWORD",substring=true).assertDoesNotExist()
            saveScreenshot("connection-auth-error")
            compose.onNodeWithTag("terminal-home").performClick()
            compose.onNodeWithTag("ssh-${via.id}").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("跳板 1：私钥无法读取",substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("PRIVATE_KEY_MARKER",substring=true).assertDoesNotExist()
            saveScreenshot("connection-jump-error")
            compose.onNodeWithTag("terminal-home").performClick()
        } finally { store.delete(target.id);store.delete(via.id) }
    }

    @Test fun nativeSelectionCopiesImmutableUnicodeTextWithoutRemoteInput() {
        showTerminalForFixture()
        val view=terminal()
        compose.waitUntil(30000) { evaluate(view,"typeof terminalSnapshot")=="\"function\"" }
        compose.runOnUiThread { view.connection("copy-test") }
        view.render("中文选择 👋 copy words\r\n第二行 command --help".toByteArray(),"copy-test")
        evaluate(view,"window.copyInputs=[]; terminal.onData(d=>copyInputs.push(d));")
        compose.onNodeWithText("工具").performClick()
        compose.onNodeWithTag("select-terminal-text").performScrollTo().performClick()
        compose.onNodeWithText("选择复制").assertExists()
        var nativeText: android.widget.TextView? = null
        compose.runOnUiThread {
            nativeText=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
                it.findViewWithTag<android.widget.TextView>("terminal-copy-text")
            }
        }
        val text=requireNotNull(nativeText)
        val location=IntArray(2)
        var pressX=0f; var pressY=0f
        compose.runOnUiThread {
            text.getLocationOnScreen(location)
            pressX=location[0]+text.totalPaddingLeft+text.layout.getPrimaryHorizontal(1)
            pressY=(location[1]+text.totalPaddingTop+(text.layout.getLineTop(0)+text.layout.getLineBottom(0))/2).toFloat()
        }
        val down=android.os.SystemClock.uptimeMillis()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,pressX,pressY,0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN })
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong()+250)
        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,pressX,pressY,0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN })
        fun handles(view: View): List<View> = (if(view.javaClass.simpleName.contains("Selection") && view.javaClass.simpleName.endsWith("HandleView")) listOf(view) else emptyList()) +
            (if(view is ViewGroup) (0 until view.childCount).flatMap { handles(view.getChildAt(it)) } else emptyList())
        compose.waitUntil(10000) {
            var count=0
            compose.runOnUiThread { count=android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap { handles(it) }.size }
            count>=2
        }
        saveScreenshot("terminal-copy-selection")
        var selected=""
        compose.waitUntil(10000) {
            var hasSelection=false
            compose.runOnUiThread { hasSelection=text.selectionStart >= 0 && text.selectionEnd>text.selectionStart }
            hasSelection
        }
        compose.runOnUiThread {
            assertTrue(text.isTextSelectable)
            selected=text.text.substring(text.selectionStart,text.selectionEnd)
            assertTrue(text.onTextContextMenuItem(android.R.id.copy))
        }
        compose.runOnUiThread {
            val clipboard=compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(selected,clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        view.render("\r\nLATER_OUTPUT".toByteArray(),"copy-test")
        compose.onNodeWithTag("copy-all-terminal").performClick()
        compose.runOnUiThread {
            val clipboard=compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("中文选择 👋 copy words\n第二行 command --help",clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        assertEquals("[]",evaluate(view,"copyInputs"))
        saveScreenshot("terminal-native-copy")
        compose.onNodeWithTag("close-terminal-copy").performClick()
        assertTrue(evaluate(view,"terminalSnapshot().text").contains("LATER_OUTPUT"))
    }

    @Test fun privacyDisplayPersistsAndProtectsCredentialScreens() {
        val prefs=compose.activity.getSharedPreferences("privacy-display",0)
        prefs.edit().clear().commit()
        val store=ServerProfiles(compose.activity)
        val profile=ServerProfile(name="private-user@192.0.2.67",host="192.0.2.67",port=22,user="private-user",path="tmux",keyAuthentication=false)
        store.save(profile,SavedCredentials("fixture",null,""))
        ServerCache(compose.activity).markAttempt(profile)
        compose.activityRule.scenario.recreate()
        fun assertSecure(expected:Boolean) {
            compose.waitUntil(10000) {
                var secure=false
                compose.runOnUiThread {secure=compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0}
                secure==expected
            }
        }
        try {
            val label=PrivacyDisplay(compose.activity).label(profile,true)
            assertEquals(label,PrivacyDisplay(compose.activity).label(profile.copy(name=""),true))
            assertEquals("工作机器",PrivacyDisplay(compose.activity).label(profile.copy(name="工作机器"),true))
            compose.onNodeWithTag("server-title-${profile.id}",useUnmergedTree=true).assertTextContains(label,substring=true)
            compose.onAllNodesWithText(profile.host,substring=true).assertCountEquals(0)
            assertSecure(false)
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("privacy-display").performScrollTo().assertIsOn().performClick()
            assertSecure(true)
            compose.onNodeWithText("‹ 返回").performScrollTo().performClick()
            compose.onNodeWithTag("server-title-${profile.id}",useUnmergedTree=true).assertTextContains("private-user@192.0.2.67",substring=true)
            compose.activityRule.scenario.recreate()
            assertSecure(true)
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("privacy-display").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithText("‹ 返回").performScrollTo().performClick()
            assertSecure(false)
            compose.onNodeWithTag("server-title-${profile.id}",useUnmergedTree=true).assertTextContains(label,substring=true)
            compose.onNodeWithTag("add-server").performClick()
            assertSecure(true)
            compose.onNodeWithTag("server-key-paste").performScrollTo().performClick()
            assertSecure(true)
            compose.onNodeWithTag("private-key-text").assertIsDisplayed()
            if (android.os.Build.VERSION.SDK_INT >= 29) compose.runOnUiThread {
                val windows=android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown }
                assertTrue(windows.size >= 2)
                assertTrue(windows.all { (it.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE != 0 })
            }
            // Close nested private-key dialog, then leave the protected editor.
            compose.onAllNodesWithText("取消").onLast().performClick()
            assertSecure(true)
            compose.onNodeWithText("取消").performClick()
            assertSecure(false)
            compose.onNodeWithTag("more-${profile.id}").performClick()
            compose.onNodeWithText("编辑服务器").performClick()
            assertSecure(true)
            compose.onNodeWithTag("profile-name").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            compose.onNodeWithTag("save-server").performClick()
            assertSecure(false)
            assertEquals("",store.all().first {it.id==profile.id}.name)
            compose.activityRule.scenario.recreate()
            assertSecure(false)
            assertEquals(label,PrivacyDisplay(compose.activity).label(profile,true))
            saveScreenshot("privacy-home")
            showTerminalForFixture()
            compose.onNodeWithTag("terminal-title").performClick()
            assertSecure(true)
            compose.onNodeWithText("关闭").performClick()
            assertSecure(false)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertSecure(true)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertSecure(false)
        } finally {
            store.delete(profile.id)
            prefs.edit().putBoolean("enabled",true).commit()
        }
    }

    @Test fun terminalRightEdgeFitsChineseAndAscii() {
        showTerminalForFixture()
        val view=terminal()
        compose.waitUntil(30000) { evaluate(view,"typeof window.receive") == "\"function\"" }
        compose.runOnUiThread { view.connection("edge-test") }
        for (size in listOf(14, 13, 17, 20)) {
            compose.runOnUiThread { view.font(size) }
            Thread.sleep(300)
            val cols=evaluate(view,"terminal.cols").toInt()
            val chinese="中文终端，右侧文字。测试：完成！".repeat(cols).take((cols-2)/2) + "A".repeat((cols-2)%2) + "末"
            view.render(("\u001b[2J\u001b[H"+chinese+"\r\n"+"W".repeat(cols)+"\r\n\u001b[1m"+chinese+"\u001b[0m").toByteArray(),"edge-test")
            Thread.sleep(400)
            val geometry=evaluate(view,"""JSON.stringify({
                cols:terminal.cols, viewport:innerWidth, dpr:devicePixelRatio,
                screen:document.querySelector('.xterm-screen').getBoundingClientRect().width,
                rows:[...document.querySelectorAll('.xterm-rows > div')].slice(0,3).map(row=>{
                    const range=document.createRange();range.selectNodeContents(row);
                    return {text:row.textContent,right:range.getBoundingClientRect().right};
                })
            })""")
            println("RIGHT_EDGE size=$size nativeWidth=${view.width} geometry=$geometry")
            saveScreenshot("right-edge-$size")
            assertEquals("Rendered row clipped: $geometry","true",evaluate(view,"""[...document.querySelectorAll('.xterm-rows > div')].slice(0,3).every(row=>{
                const range=document.createRange();range.selectNodeContents(row);
                return range.getBoundingClientRect().right <= document.querySelector('.xterm-screen').getBoundingClientRect().right + 1;
            })"""))
        }
    }

    @Test fun terminalRendersBytesAndSurvivesConfigurationPanelAndKeyboard() {
        showTerminalForFixture()
        val view = terminal()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (evaluate(view, "typeof window.receive") != "\"function\"") {
            assertTrue("Terminal asset page did not load", System.nanoTime() < deadline)
            Thread.sleep(100)
        }
        compose.runOnUiThread { view.connection("ui-test") }
        view.render("\u001b[32m中文 terminal ready\u001b[0m\r\n$ ".toByteArray(), "ui-test")
        assertTrue(evaluate(view, "terminal.buffer.active.getLine(0).translateToString(true)").contains("中文 terminal ready"))
        assertPaintedGreenText(view, "local-terminal-output")
        compose.runOnUiThread { assertTrue("WebView has no visible height", view.height >= 80 * view.resources.displayMetrics.density) }
        compose.onNodeWithTag("terminal-home").performClick()
        showTerminalForFixture()
        assertSame("Opening settings must not recreate the terminal", view, terminal())
        assertPaintedGreenText(view, "after-settings")
        compose.onNodeWithTag("command-input").performClick().performTextInput("pwd")
        compose.waitUntil(15000) {
            var visible = false
            compose.runOnUiThread {
                visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
        compose.waitForIdle()
        compose.onNodeWithTag("command-input").assertTextContains("pwd")
        compose.runOnUiThread { assertTrue("IME must not collapse WebView to zero", view.height >= 80 * view.resources.displayMetrics.density) }
        compose.onNodeWithText("收起键盘").performClick()
        compose.onNodeWithTag("terminal-panel").assertIsDisplayed()
        compose.waitUntil(10000) { evaluate(view, "terminal.rows > 1") == "true" }
        assertPaintedGreenText(view, "after-keyboard")
    }
    @Test fun sendButtonExecutesVisibleDraftAndClearsItOnlyAfterWrite() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use scripts/test_ssh.py --android", args.containsKey("fixturePort"))
        val port = args.getString("fixturePort")!!.toInt()
        showTerminalForFixture()
        val view = terminal()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (evaluate(view, "typeof window.sendDraft") != "\"function\"") {
            assertTrue(System.nanoTime() < deadline)
            Thread.sleep(100)
        }
        compose.runOnUiThread {
            compose.activity.getSharedPreferences("host-pins", 0).edit()
                .putString("[127.0.0.1]:$port", args.getString("fixtureHostKey")).commit()
            val login = Login("127.0.0.1", port, args.getString("fixtureUser")!!, "",
                Base64.getDecoder().decode(args.getString("fixtureKey")))
            // Test-only reflection avoids shipping any credential injection/debug network entry point.
            val method = MainActivity::class.java.getDeclaredMethod("connect", Login::class.java,
                String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(compose.activity, login, "tmux", null, false)
        }
        compose.waitUntil(30000) {
            evaluate(view,"connected") == "true"
        }
        val outputScript = "Array.from({length:terminal.buffer.active.length},(_,i)=>terminal.buffer.active.getLine(i).translateToString(true)).join('\\n')"
        compose.waitUntil(30000) { evaluate(view, outputScript).contains("MTMUX_SSH_WELCOME") }
        assertPaintedGreenText(view, "ssh-welcome-before-input")
        compose.onNodeWithTag("command-input").performTextInput("printf '\\115\\124\\115\\125\\130_READY\\n'")
        compose.onNodeWithText("发送回车").performClick()
        compose.waitUntil(30000) {
            compose.onNodeWithTag("command-input").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty()
        }
        compose.waitUntil(30000) { evaluate(view, outputScript).contains("MTMUX_READY") }
        assertPaintedGreenText(view, "ssh-command-result")
        compose.onNodeWithTag("command-input").performTextInput("keep this draft")
        disconnectFixture()
        compose.onNodeWithTag("command-input").assertTextContains("keep this draft")
        compose.onNodeWithText("发送回车").assertIsNotEnabled()
        compose.waitUntil(10000) { evaluate(view, outputScript).trim('"', '\n', ' ') == "" || !evaluate(view, outputScript).contains("MTMUX_READY") }
        compose.onNodeWithText("尚未连接 · 请查看上方提示\n可返回首页检查配置或重新连接。").assertIsDisplayed()
        view.render("LATE_STALE_OUTPUT".toByteArray(), "old-token")
        assertFalse(evaluate(view, outputScript).contains("LATE_STALE_OUTPUT"))
        saveScreenshot("manual-disconnect-cleared")
    }

    @Test fun functionKeyPagesFillWidthAndDeliverAllTwelveKeysToBoundPane() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureTmux"))
        val path=args.getString("fixtureTmux")!!
        val port=args.getString("fixturePort")!!.toInt()
        val login=Login("127.0.0.1",port,args.getString("fixtureUser")!!,"",Base64.getDecoder().decode(args.getString("fixtureKey")))
        val controller=dev.mtmux.core.SshClient(object:dev.mtmux.core.PinStore {override fun get(endpoint:String)=args.getString("fixtureHostKey")})
        controller.connect(login)
        val tmux=dev.mtmux.core.Tmux.quote(path)
        val script="import os,tty; tty.setraw(0); data=b''\nwhile len(data)<52: data+=os.read(0,52-len(data))\nprint('KEY_BYTES='+data.hex(),flush=True)"
        val command="python3 -c " + dev.mtmux.core.Tmux.quote(script) + "; sleep 300"
        val session=controller.exec("$tmux new-session -d -P -F '#{session_id}' -s function-keys -x 80 -y 24 " + dev.mtmux.core.Tmux.quote(command)).output.trim()
        try {
            val binding=controller.bindPane(session,path)
            showTerminalForFixture()
            val view=terminal()
            compose.waitUntil(30000) {evaluate(view,"typeof window.receive")=="\"function\""}
            compose.runOnUiThread {
                compose.activity.getSharedPreferences("host-pins",0).edit().putString(login.endpoint,args.getString("fixtureHostKey")).commit()
                MainActivity::class.java.getDeclaredMethod("connect",Login::class.java,String::class.java,String::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(compose.activity,login,path,session,false)
            }
            compose.waitUntil(30000) {evaluate(view,"connected")=="true"}
            compose.onNodeWithTag("command-input").performTextInput("UNSENT_FUNCTION_DRAFT")
            compose.onNodeWithText("收起键盘").performClick()
            fun verifyWidth(labels:List<String>) {
                val bounds=labels.map {compose.onNodeWithTag("terminal-key-$it").fetchSemanticsNode().boundsInRoot}
                val bar=compose.onNodeWithTag("terminal-key-pages").fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(bounds.first().left-bar.left)<2)
                assertTrue(kotlin.math.abs(bounds.last().right-bar.right)<2)
                assertTrue(bounds.all {kotlin.math.abs(it.width-bar.width/6)<2})
            }
            verifyWidth(listOf("Esc","Tab","Ctrl-C","↑","↓","Enter"))
            compose.onNodeWithTag("terminal-key-pages").performTouchInput {swipeLeft()}
            verifyWidth((1..6).map {"F$it"})
            saveScreenshot("terminal-keys-f1-f6")
            (1..6).forEach {compose.onNodeWithTag("terminal-key-F$it").performClick()}
            compose.onNodeWithTag("terminal-key-pages").performTouchInput {swipeLeft()}
            verifyWidth((7..12).map {"F$it"})
            saveScreenshot("terminal-keys-f7-f12")
            (7..12).forEach {compose.onNodeWithTag("terminal-key-F$it").performClick()}
            val expected="1b4f501b4f511b4f521b4f531b5b31357e1b5b31377e1b5b31387e1b5b31397e1b5b32307e1b5b32317e1b5b32337e1b5b32347e"
            compose.waitUntil(15000) {controller.exec(dev.mtmux.core.Tmux.history(binding.pane,path)).output.replace("\n","").replace("\r","").contains("KEY_BYTES="+expected)}
            compose.onNodeWithTag("command-input").assertTextContains("UNSENT_FUNCTION_DRAFT")
            compose.onNodeWithTag("terminal-key-pages").performTouchInput {swipeRight()}
            compose.onNodeWithTag("terminal-key-F1").assertIsDisplayed()
            disconnectFixture()
            compose.onNodeWithTag("terminal-key-F1").assertIsNotEnabled()
        } finally {controller.exec("$tmux kill-session -t '$session'");controller.close()}
    }

    @Test fun keyboardPreservesTopAnchorAndGridForHistoryAndAlternateScreen() {
        showTerminalForFixture()
        val view=terminal()
        compose.waitUntil(30000) { evaluate(view,"typeof setVisibleHeight")=="\"function\"" }
        compose.runOnUiThread { view.connection("anchor-test") }
        val anchor="JSON.stringify([terminal.cols,terminal.rows,terminal.buffer.active.viewportY,terminal.buffer.active.getLine(terminal.buffer.active.viewportY).translateToString(true),document.querySelector('.xterm-screen').getBoundingClientRect().top])"
        for(alternate in listOf(false,true)) {
            if(!alternate) {
                view.render((0..200).joinToString("\r\n") { "ANCHOR-$it" }.toByteArray(),"anchor-test")
                evaluate(view,"terminal.scrollToLine(70);true")
            } else {
                view.render("\u001b[?1049h\u001b[H\u001b[32mALTERNATE_ANCHOR\u001b[0m".toByteArray(),"anchor-test")
            }
            evaluate(view,"window.resizeCount=0;terminal.onResize(()=>window.resizeCount++);true")
            val before=evaluate(view,anchor)
            val top=IntArray(2);compose.runOnUiThread { view.getLocationOnScreen(top) }
            repeat(10) {
                compose.onNodeWithTag("command-input").performClick()
                compose.waitUntil(10000) { var open=false;compose.runOnUiThread {open=ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==true};open }
                Thread.sleep(250)
                assertEquals(before,evaluate(view,anchor))
                val location=IntArray(2);compose.runOnUiThread {view.getLocationOnScreen(location)}
                assertEquals("Native WebView must not move",top[1],location[1])
                if(it==0) saveScreenshot(if(alternate) "terminal-alternate-keyboard" else "terminal-history-keyboard")
                compose.onNodeWithText("收起键盘").performClick()
                compose.waitUntil(10000) { var closed=false;compose.runOnUiThread {closed=ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==false};closed }
                Thread.sleep(250)
                assertEquals(before,evaluate(view,anchor))
            }
            assertEquals("IME must not resize PTY/grid","0",evaluate(view,"window.resizeCount"))
        }
        saveScreenshot("terminal-keyboard-hidden")
    }

    @Test fun shellHistoryRespondsToTouchWithoutSendingInput() {
        showTerminalForFixture()
        val view = terminal()
        compose.waitUntil(30000) { evaluate(view, "typeof window.receive") == "\"function\"" }
        compose.runOnUiThread { view.connection("scroll-test") }
        view.render((0 until 200).joinToString("\r\n") { "line-%03d".format(it) }.toByteArray(), "scroll-test")
        compose.waitUntil(10000) { evaluate(view, "terminal.buffer.active.baseY > 100") == "true" }
        evaluate(view, "window.touchWrites=0;terminal.onData(()=>window.touchWrites++);true")
        compose.runOnUiThread { view.remoteTouch(false) }
        view.render("\u001b[?1000h\u001b[?1006h".toByteArray(), "scroll-test")
        val before = evaluate(view, "terminal.buffer.active.viewportY").toInt()
        compose.onNodeWithTag("terminal-webview").performTouchInput { swipeDown() }
        compose.waitUntil(10000) { evaluate(view, "terminal.buffer.active.viewportY").toInt() < before }
        assertEquals("Touch reading must not send mouse/key input", "0", evaluate(view, "window.touchWrites"))
        val readingPosition = evaluate(view, "terminal.buffer.active.viewportY").toInt()
        saveScreenshot("shell-touch-history")
        view.render("\r\nnew-output".toByteArray(), "scroll-test")
        assertTrue(evaluate(view, "terminal.buffer.active.viewportY").toInt() <= readingPosition)
        compose.onNodeWithTag("terminal-latest").performClick()
        compose.waitUntil(10000) { evaluate(view, "terminal.buffer.active.viewportY === terminal.buffer.active.baseY") == "true" }
    }

    @Test fun foldersSearchAndSortSurviveRecreationWithoutChangingConnections() {
        val store=ServerProfiles(compose.activity)
        val organization=ServerOrganization(compose.activity)
        val cache=ServerCache(compose.activity)
        store.all().forEach {store.delete(it.id)}
        organization.folders().forEach {organization.deleteFolder(it.id)}
        organization.sort(ServerSort.ADDED)
        val z=ServerProfile(name="Zulu",host="z.example.invalid",port=22,user="dev",path="tmux",keyAuthentication=false)
        val a=z.copy(id="alpha-folder-test",name="Alpha",host="a.example.invalid")
        val c=z.copy(id="chinese-folder-test",name="开发服务器",host="hidden.example.invalid")
        val profiles=listOf(z,a,c)
        profiles.forEach {store.save(it,SavedCredentials("FOLDER_SECRET"));cache.markAttempt(it)}
        val task=RecentTask(z.id,z.host,z.port,z.user,z.path,"pin","1:2:$0:@0:%0:3","Agent 会话","opencode 工作区",usedAt=2000)
        cache.write(z,listOf(task));store.rememberTask(task)
        store.rememberTask(RecentTask(a.id,a.host,a.port,a.user,a.path,"pin",null,"","",usedAt=1000))
        val prefs=compose.activity.getSharedPreferences("server-profiles",0)
        val before=profiles.associate {it.id to prefs.getString("credential:${it.id}",null)}
        val probe=prefs.getString("probe:${z.id}",null)
        try {
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("add-folder").performClick()
            compose.onNodeWithTag("folder-name").performTextInput("工作")
            compose.onNodeWithTag("save-folder").performClick()
            val folder=organization.folders().single()
            for(profile in listOf(z,a)) {
                compose.onNodeWithTag("more-${profile.id}").performScrollTo().performClick()
                compose.onNodeWithText("移动到文件夹").performClick()
                compose.onNodeWithTag("move-folder-${folder.id}").performClick()
            }
            compose.onNodeWithTag("server-sort").performClick()
            compose.onNodeWithTag("sort-NAME").performClick()
            fun y(id:String)=compose.onNodeWithTag("profile-$id").fetchSemanticsNode().boundsInRoot.top
            assertTrue(y(a.id)<y(z.id))
            compose.onNodeWithTag("server-sort").performClick()
            compose.onNodeWithTag("sort-RECENT").performClick()
            assertTrue(y(z.id)<y(a.id))
            compose.onNodeWithTag("folder-${folder.id}").performScrollTo().performClick()
            compose.onNodeWithTag("profile-${z.id}").assertDoesNotExist()
            compose.onNodeWithTag("server-search").performTextInput("OPENCODE")
            compose.onNodeWithTag("task-${z.id}-%0").assertExists()
            compose.onNodeWithTag("profile-${a.id}").assertDoesNotExist()
            compose.onNodeWithTag("profile-${c.id}").assertDoesNotExist()
            saveScreenshot("home-folder-search")
            compose.onNodeWithTag("server-search").performTextClearance()
            compose.onNodeWithTag("profile-${z.id}").assertDoesNotExist()
            compose.onNodeWithTag("server-search").performTextInput("hidden.example")
            compose.onNodeWithTag("profile-${c.id}").assertExists()
            compose.onNodeWithTag("profile-${z.id}").assertDoesNotExist()
            compose.onNodeWithTag("server-search").performTextReplacement("no-such-result")
            compose.onNodeWithText("没有匹配的服务器或缓存任务").assertExists()
            compose.onNodeWithTag("server-search").performTextClearance()
            compose.activityRule.scenario.recreate()
            assertEquals(ServerSort.RECENT,ServerOrganization(compose.activity).sort())
            assertEquals(folder.id,ServerOrganization(compose.activity).folderOf(z.id))
            compose.onNodeWithTag("profile-${z.id}").assertDoesNotExist()
            compose.onNodeWithTag("folder-${folder.id}").performClick()
            assertTrue(y(z.id)<y(a.id))
            compose.onNodeWithTag("folder-menu-${folder.id}").performClick()
            compose.onNodeWithText("重命名文件夹").performClick()
            compose.onNodeWithTag("folder-name").performTextReplacement("生产环境")
            compose.onNodeWithTag("save-folder").performClick()
            assertEquals("生产环境",organization.folders().single().name)
            saveScreenshot("home-folders-sort")
            compose.onNodeWithTag("more-${z.id}").performScrollTo().performClick()
            compose.onNodeWithText("移动到文件夹").performClick()
            compose.onNodeWithTag("move-folder-ungrouped").performClick()
            assertEquals(null,organization.folderOf(z.id))
            compose.onNodeWithTag("folder-menu-${folder.id}").performScrollTo().performClick()
            compose.onNodeWithText("删除文件夹").performClick()
            compose.onNodeWithTag("confirm-delete-folder").performClick()
            assertTrue(organization.folders().isEmpty())
            assertEquals(null,organization.folderOf(a.id))
            assertEquals(3,store.all().size)
            assertEquals(task.identity,cache.read(z)!!.tasks.single().identity)
            assertTrue(store.recent().any {it.profileId==z.id})
            assertEquals(probe,prefs.getString("probe:${z.id}",null))
            profiles.forEach {assertEquals(before[it.id],prefs.getString("credential:${it.id}",null));assertEquals("FOLDER_SECRET",store.credentials(it)!!.password)}
        } finally {
            profiles.forEach {store.delete(it.id)}
            organization.folders().forEach {organization.deleteFolder(it.id)}
            organization.sort(ServerSort.ADDED)
        }
    }

    @Test fun folderValidationAndProfileDeletionDoNotLeaveMemberships() {
        val store=ServerProfiles(compose.activity)
        val organization=ServerOrganization(compose.activity)
        val profile=ServerProfile(name="folder-data",host="folder.invalid",port=22,user="dev",path="tmux",keyAuthentication=false)
        val folder=organization.saveFolder(name="folder-data-test")
        try {
            assertTrue(runCatching {organization.saveFolder(name="   ")}.isFailure)
            assertTrue(runCatching {organization.saveFolder(name="FOLDER-DATA-TEST")}.isFailure)
            store.save(profile,SavedCredentials("local-only"))
            organization.move(profile.id,folder.id)
            assertTrue(runCatching {organization.move(profile.id,"missing")}.isFailure)
            store.delete(profile.id)
            assertEquals(null,organization.folderOf(profile.id))
            assertTrue(runCatching {organization.move(profile.id,folder.id)}.isFailure)
            assertTrue(organization.folders().any {it.id==folder.id})
        } finally {store.delete(profile.id);organization.deleteFolder(folder.id)}
    }

    @Test fun folderRefreshRespectsExternalEntryAndSavedCollapse() {
        val store=ServerProfiles(compose.activity)
        store.all().forEach { store.delete(it.id) }
        val organization=ServerOrganization(compose.activity)
        organization.folders().forEach { organization.deleteFolder(it.id) }
        val folder=organization.saveFolder(null,"休眠组")
        val first=ServerProfile(name="第一台",host="127.0.0.1",port=9,user="test",path="tmux",keyAuthentication=false)
        val second=first.copy(id="folder-second-fixture",name="第二台")
        val loose=first.copy(id="folder-loose-fixture",name="未分组测试")
        val cache=ServerCache(compose.activity)
        listOf(first,second,loose).forEach { store.save(it,SavedCredentials("test")) }
        organization.move(first.id,folder.id); organization.move(second.id,folder.id)
        organization.collapse(folder.id,true)
        fun expire() { listOf(first,second,loose).forEach { cache.markAttempt(it,System.currentTimeMillis()-31*60*1000L) } }
        fun foreground() {
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitForIdle()
        }
        try {
            expire()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) { !cache.shouldRefresh(loose) }
            assertTrue(cache.shouldRefresh(first)); assertTrue(cache.shouldRefresh(second))
            compose.onNodeWithTag("profile-${first.id}").assertDoesNotExist()
            // Searching reveals cached children without changing the saved collapse or refresh policy.
            compose.onNodeWithTag("server-search").performTextInput("第一台")
            foreground()
            assertTrue(cache.shouldRefresh(first)); assertTrue(cache.shouldRefresh(second))
            compose.onNodeWithTag("refresh-folder-${folder.id}").performClick()
            compose.waitUntil(10000) { !cache.shouldRefresh(first) && !cache.shouldRefresh(second) }
            compose.onNodeWithText("清除").performClick()
            compose.onNodeWithTag("profile-${first.id}").assertDoesNotExist()
            // Manual refresh also bypasses the interval while folded.
            compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("refresh-folder-${folder.id}").assertIsEnabled() }.isSuccess }
            val prefs=compose.activity.getSharedPreferences("server-profiles",0)
            val before=prefs.getString("probe:${second.id}",null)
            compose.onNodeWithTag("refresh-folder-${folder.id}").performClick()
            compose.waitUntil(10000) { prefs.getString("probe:${second.id}",null)!=before }
            compose.waitForIdle()
            expire()
            compose.onNodeWithTag("folder-${folder.id}").performClick()
            compose.waitForIdle()
            assertTrue(cache.shouldRefresh(first)) // Expanding alone is local.
            foreground()
            compose.waitUntil(10000) { !cache.shouldRefresh(first) && !cache.shouldRefresh(second) && !cache.shouldRefresh(loose) }
            assertFalse(ServerOrganization(compose.activity).collapsed(folder.id))
            saveScreenshot("home-folder-refresh")
        } finally {
            listOf(first,second,loose).forEach { store.delete(it.id) }
            organization.deleteFolder(folder.id)
        }
    }

    @Test fun discoveryIntervalPersistsAndRespectsBoundaryAndTargetChanges() {
        val profile=ServerProfile(name="throttle",host="example.invalid",port=22,user="test",path="tmux",keyAuthentication=false)
        val cache=ServerCache(compose.activity)
        val now=System.currentTimeMillis()
        try {
            assertTrue(cache.shouldRefresh(profile,now))
            cache.markAttempt(profile,now) // No successful snapshot: failures must also be throttled.
            val restored=ServerCache(compose.activity)
            assertFalse(restored.shouldRefresh(profile,now+30*60*1000L-1))
            assertTrue(restored.shouldRefresh(profile,now+30*60*1000L))
            assertTrue(restored.shouldRefresh(profile,now-1)) // Wall clock rollback must not block forever.
            assertFalse(restored.shouldRefresh(profile.copy(name="renamed"),now+1))
            assertTrue(restored.shouldRefresh(profile.copy(host="another.invalid"),now+1))
            assertTrue(restored.shouldRefresh(profile.copy(jumps=listOf(JumpHost(host="jump.invalid",port=22,user="test",keyAuthentication=false))),now+1))
        } finally { ServerProfiles(compose.activity).delete(profile.id) }
    }

    @Test fun homeCacheSettingsEditorAndTwoJumpConnectionWorkTogether() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureTmux"))
        val port=args.getString("fixturePort")!!.toInt()
        val hostKey=args.getString("fixtureHostKey")!!
        val user=args.getString("fixtureUser")!!
        val key=Base64.getDecoder().decode(args.getString("fixtureKey"))
        val encrypted=Base64.getDecoder().decode(args.getString("fixtureEncryptedKey"))
        val hop1=JumpHost(host="127.0.0.1",port=port,user=user,keyAuthentication=true)
        val hop2=JumpHost(host="127.0.0.1",port=port,user=user,keyAuthentication=true)
        val profile=ServerProfile(name="开发服务器",host="127.0.0.1",port=port,user=user,path=args.getString("fixtureTmux")!!,keyAuthentication=true,jumps=listOf(hop1,hop2))
        val offline=ServerProfile(name="离线服务器",host="127.0.0.1",port=9,user="test",path="tmux",keyAuthentication=false)
        val store=ServerProfiles(compose.activity)
        store.all().forEach { store.delete(it.id) }
        store.save(profile,SavedCredentials("",key,"",mapOf(hop1.id to SavedCredentials("",key,""),hop2.id to SavedCredentials("",encrypted,"p0-test-only"))))
        store.save(offline,SavedCredentials("fixture-password"))
        val offlineTask=RecentTask(offline.id,offline.host,9,offline.user,"tmux","fixture-pin","1:2:$0:@0:%0:3","缓存会话","缓存窗口")
        ServerCache(compose.activity).write(offline,listOf(offlineTask))
        val first=Login(profile.host,port,user,"",key)
        val second=Login(profile.host,port,user,"",encrypted,"p0-test-only",listOf(first))
        val pins=compose.activity.getSharedPreferences("host-pins",0)
        pins.edit().putString(first.trustEndpoint,hostKey).putString(second.trustEndpoint,hostKey).putString(store.login(profile).trustEndpoint,hostKey).commit()
        assertTrue(runCatching { store.credentials(profile.copy(jumps=listOf(hop2,hop1))) }.isFailure)
        try {
            compose.activityRule.scenario.recreate()
            compose.waitUntil(45000) { ServerCache(compose.activity).read(profile)?.tasks?.isNotEmpty()==true }
            compose.onNodeWithTag("ssh-${profile.id}").assertExists()
            compose.onNodeWithText("服务器",substring=false).assertExists()
            compose.onNodeWithText("缓存会话 / 缓存窗口").assertExists()
            compose.waitUntil(15000) { compose.onAllNodesWithText("显示上次缓存",substring=true).fetchSemanticsNodes().isNotEmpty() }
            val before=ServerCache(compose.activity).read(profile)!!.updatedAt
            compose.onNodeWithTag("refresh-${profile.id}").performClick()
            compose.waitUntil(45000) { (ServerCache(compose.activity).read(profile)?.updatedAt ?: 0)>before }
            compose.onNodeWithText("$user@127.0.0.1:$port",substring=true).assertDoesNotExist()
            compose.onNodeWithTag("server-updated-${profile.id}",useUnmergedTree=true).assertIsDisplayed()
            saveScreenshot("ui-server-home")
            compose.onNodeWithTag("settings").performClick()
            saveScreenshot("ui-settings")
            compose.onNodeWithTag("settings-speed").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*0.9f,height*0.5f)) }
            val updated=ServerCache(compose.activity).read(profile)!!.updatedAt
            compose.onNodeWithText("‹ 返回").performClick()
            compose.waitForIdle()
            assertEquals(updated,ServerCache(compose.activity).read(profile)!!.updatedAt)
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            assertEquals(updated,ServerCache(compose.activity).read(profile)!!.updatedAt)
            // Internal navigation never refreshes, even when the interval has expired.
            ServerCache(compose.activity).markAttempt(profile,System.currentTimeMillis()-30*60*1000L)
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithText("‹ 返回").performClick()
            compose.waitForIdle()
            assertEquals(updated,ServerCache(compose.activity).read(profile)!!.updatedAt)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(45000) { ServerCache(compose.activity).read(profile)!!.updatedAt>updated }
            assertTrue(compose.activity.getSharedPreferences("terminal-settings",0).getFloat("scrollSensitivity",1f)>1f)
            compose.onNodeWithTag("more-${profile.id}").performClick()
            compose.onNodeWithText("编辑服务器").performClick()
            compose.onNodeWithText("Jump Host").assertDoesNotExist()
            saveScreenshot("ui-server-editor")
            compose.onNodeWithTag("advanced").performScrollTo().performClick()
            compose.onNodeWithTag("jump-1-host").performScrollTo().assertTextContains("127.0.0.1")
            compose.onNodeWithTag("jump-2-host").performScrollTo().assertTextContains("127.0.0.1")
            saveScreenshot("ui-jump-hosts")
            compose.onNodeWithTag("profile-name").performScrollTo().performTextReplacement("开发服务器 · 已编辑")
            compose.onNodeWithTag("save-server").performClick()
            compose.waitUntil(10000) { store.all().first { it.id==profile.id }.name.contains("已编辑") }
            assertEquals(2,store.login(store.all().first { it.id==profile.id }).jumps.size)
            compose.onNodeWithTag("ssh-${profile.id}").performScrollTo().performClick()
            val view=terminal()
            compose.waitUntil(45000) { evaluate(view,"terminal.buffer.active.getLine(0).translateToString(true)").contains("MTMUX_SSH_WELCOME") }
            compose.onNodeWithTag("command-input").performTextInput("printf 'JUMP_UI_OK\\n'")
            compose.onNodeWithText("发送回车").performClick()
            compose.waitUntil(15000) { evaluate(view,"Array.from({length:terminal.buffer.active.length},(_,i)=>terminal.buffer.active.getLine(i).translateToString(true)).join('\\n')").contains("JUMP_UI_OK") }
            val beforeHome=ServerCache(compose.activity).read(profile)!!.updatedAt
            ServerCache(compose.activity).markAttempt(profile,System.currentTimeMillis()-31*60*1000L)
            compose.onNodeWithTag("terminal-home").performClick()
            compose.waitForIdle()
            assertEquals(beforeHome,ServerCache(compose.activity).read(profile)!!.updatedAt)
            assertTrue(ServerCache(compose.activity).shouldRefresh(profile))
            compose.onNodeWithTag("task-${profile.id}-%0").performScrollTo().performClick()
            compose.waitUntil(45000) { evaluate(view,"connected")=="true" }
            compose.onNodeWithText("工具").performClick()
            compose.onNodeWithTag("reply-target").assertTextEquals("回复目标 %0")
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("terminal-home").performClick()
        } finally {
            // Route edits invalidate target caches; shared hop trust survives until its last profile is removed.
            val saved = store.all().first { it.id == profile.id }
            val secrets = store.credentials(saved)!!
            val shared = saved.copy(id="shared-route-fixture", name="共享跳板")
            store.save(shared,secrets)
            store.delete(profile.id)
            assertEquals(hostKey,pins.getString(saved.trustEndpoint(),null))
            ServerCache(compose.activity).write(shared,emptyList())
            store.save(shared.copy(jumps=shared.jumps.reversed()),secrets)
            assertEquals(null,ServerCache(compose.activity).read(shared))
            store.delete(shared.id);store.delete(offline.id)
            assertEquals(null,ServerCache(compose.activity).read(offline))
            assertEquals(null,pins.getString(first.trustEndpoint,null))
            compose.activity.getSharedPreferences("terminal-settings",0).edit().putFloat("scrollSensitivity",1f).commit()
        }
    }

    @Test fun profilesRestoreEncryptedPasswordAndCanBeDeleted() {
        compose.runOnUiThread { compose.activity.getSharedPreferences("server-profiles",0).edit().clear().commit() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("add-server").performClick()
        compose.onNodeWithTag("profile-name").performTextInput("测试服务器")
        compose.onNodeWithTag("server-host").performTextInput("127.0.0.1")
        compose.onNodeWithTag("server-user").performTextInput("tester")
        compose.onNodeWithText("密码",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithTag("server-secret").performScrollTo().performTextInput("NEVER_PERSIST_SECRET")
        compose.onNodeWithTag("save-server").performClick()
        val store=ServerProfiles(compose.activity)
        compose.waitUntil(10000) { store.all().size==1 }
        val profile=store.all().single()
        assertFalse(compose.activity.getSharedPreferences("server-profiles",0).all.values.joinToString().contains("NEVER_PERSIST_SECRET"))
        compose.onNodeWithTag("profile-${profile.id}").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("more-${profile.id}").performClick()
        compose.onNodeWithText("编辑服务器").performClick()
        compose.onNodeWithTag("profile-name").assertTextContains("测试服务器")
        compose.onNodeWithTag("server-host").assertTextContains("127.0.0.1")
        assertEquals("NEVER_PERSIST_SECRET",store.credentials(profile)!!.password)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("more-${profile.id}").performClick()
        compose.onNodeWithText("删除服务器").performClick()
        compose.onNodeWithText("删除").performClick()
        assertTrue(store.all().isEmpty())
    }

    @Test fun tmuxNamesAndHistoryIncludeOutputBeforeAttach() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use scripts/test_ssh.py --android", args.containsKey("fixtureTmux"))
        val port = args.getString("fixturePort")!!.toInt()
        showTerminalForFixture()
        val view = terminal()
        compose.waitUntil(30000) { evaluate(view, "typeof window.receive") == "\"function\"" }
        fun connect(session: String?, discover: Boolean) {
            compose.runOnUiThread {
                compose.activity.getSharedPreferences("host-pins", 0).edit()
                    .putString("[127.0.0.1]:$port", args.getString("fixtureHostKey")).commit()
                val login = Login("127.0.0.1", port, args.getString("fixtureUser")!!, "",
                    Base64.getDecoder().decode(args.getString("fixtureKey")))
                val method = MainActivity::class.java.getDeclaredMethod("connect", Login::class.java,
                    String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(compose.activity, login, args.getString("fixtureTmux"), session, discover)
            }
        }
        connect(null, true)
        compose.waitUntil(30000) { compose.onAllNodesWithText("发现 1 个 pane，可按名称选择会话").fetchSemanticsNodes().isNotEmpty() }
        connect("$0", false)
        compose.waitUntil(30000) { evaluate(view,"connected")=="true" }
        compose.onNodeWithText("历史快照").assertDoesNotExist()
        compose.onNodeWithText("退出 tmux 滚动").assertDoesNotExist()
        compose.onNodeWithTag("terminal-title").assertExists()
        saveScreenshot("terminal-named-tmux")
        disconnectFixture()
    }

    @Test fun remoteDisconnectPreservesOutputAndMarksItAsHistory() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixturePort"))
        val port = args.getString("fixturePort")!!.toInt()
        showTerminalForFixture()
        val view = terminal()
        compose.waitUntil(30000) { evaluate(view, "typeof window.receive") == "\"function\"" }
        compose.runOnUiThread {
            compose.activity.getSharedPreferences("host-pins", 0).edit()
                .putString("[127.0.0.1]:$port", args.getString("fixtureHostKey")).commit()
            val login = Login("127.0.0.1", port, args.getString("fixtureUser")!!, "",
                Base64.getDecoder().decode(args.getString("fixtureKey")))
            val method = MainActivity::class.java.getDeclaredMethod("connect", Login::class.java,
                String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(compose.activity, login, "tmux", null, false)
        }
        compose.waitUntil(30000) { evaluate(view,"connected") == "true" }
        compose.onNodeWithTag("command-input").performTextInput("printf 'REMOTE_DONE\\n'; exit")
        compose.onNodeWithText("发送回车").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("disconnected-history").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(evaluate(view, "Array.from({length:terminal.buffer.active.length},(_,i)=>terminal.buffer.active.getLine(i).translateToString(true)).join('\\n')").contains("REMOTE_DONE"))
        compose.onNodeWithText("发送回车").assertIsNotEnabled()
        saveScreenshot("remote-disconnect-retained")
    }

    @Test fun encryptedPrivateKeysRestoreAndConnectWithOneClick() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureEncryptedKey"))
        val port = args.getString("fixturePort")!!.toInt()
        for (encrypted in listOf(false, true)) {
            val key = Base64.getDecoder().decode(args.getString(if (encrypted) "fixtureEncryptedKey" else "fixtureKey"))
            val profile = ServerProfile(name = "一键连接", host = "127.0.0.1", port = port,
                user = args.getString("fixtureUser")!!, path = "tmux", keyAuthentication = true)
            compose.runOnUiThread {
                val store = ServerProfiles(compose.activity)
                store.all().forEach { store.delete(it.id) }
                store.save(profile, SavedCredentials(privateKey = key, passphrase = if (encrypted) "p0-test-only" else ""))
                val raw = compose.activity.getSharedPreferences("server-profiles", 0).all.values.joinToString()
                assertFalse(raw.contains("OPENSSH PRIVATE KEY"))
                assertFalse(raw.contains(Base64.getEncoder().encodeToString(key)))
                assertFalse(raw.contains("p0-test-only"))
                compose.activity.getSharedPreferences("host-pins", 0).edit()
                    .putString("[127.0.0.1]:$port", args.getString("fixtureHostKey")).commit()
            }
            compose.activityRule.scenario.recreate()
            val view = terminal()
            compose.waitUntil(30000) { evaluate(view, "typeof window.receive") == "\"function\"" }
            compose.onNodeWithTag("ssh-${profile.id}").performScrollTo().performClick()
            compose.waitUntil(30000) { evaluate(view,"connected") == "true" }
            compose.waitUntil(30000) { evaluate(view, "terminal.buffer.active.getLine(0).translateToString(true)").contains("MTMUX_SSH_WELCOME") }
            disconnectFixture()
            compose.runOnUiThread {
                val store = ServerProfiles(compose.activity)
                store.delete(profile.id)
                assertNull(store.credentials(profile))
                assertFalse(compose.activity.getSharedPreferences("server-profiles", 0).contains("credential:${profile.id}"))
            }
        }
    }

    @Test fun credentialCipherRejectsTamperingAndWrongServer() {
        compose.runOnUiThread {
            val profile = ServerProfile(name = "密文测试", host = "one", port = 22, user = "u", path = "tmux", keyAuthentication = false)
            val store = ServerProfiles(compose.activity)
            store.save(profile, SavedCredentials("secret-roundtrip"))
            val preferences = compose.activity.getSharedPreferences("server-profiles", 0)
            val first = preferences.getString("credential:${profile.id}", null)!!
            store.save(profile, SavedCredentials("secret-roundtrip"))
            assertNotEquals(first, preferences.getString("credential:${profile.id}", null))
            assertTrue(runCatching { store.credentials(profile.copy(host = "different")) }.isFailure)
            val record = org.json.JSONObject(preferences.getString("credential:${profile.id}", null)!!)
            val bytes = Base64.getDecoder().decode(record.getString("data"))
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            record.put("data", Base64.getEncoder().encodeToString(bytes))
            preferences.edit().putString("credential:${profile.id}", record.toString()).commit()
            assertTrue(runCatching { store.credentials(profile) }.isFailure)
            store.delete(profile.id)
        }
    }

    @Test fun recentTaskSurvivesRecreationAndReconnectDoesNotReplayDraft() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureTmux"))
        val port = args.getString("fixturePort")!!.toInt()
        val path = args.getString("fixtureTmux")!!
        val key = Base64.getDecoder().decode(args.getString("fixtureKey"))
        val login = Login("127.0.0.1", port, args.getString("fixtureUser")!!, "", key)
        val control = dev.mtmux.core.SshClient(object : dev.mtmux.core.PinStore {
            override fun get(endpoint: String) = args.getString("fixtureHostKey")
        })
        control.connect(login)
        val tmux = dev.mtmux.core.Tmux.quote(path)
        val session = control.exec("$tmux new-session -d -P -F '#{session_id}' -s recent-phone -n agent 'stty -echo; cat'").output.trim()
        val original = control.bindPane(session, path)
        val profile = ServerProfile(name="最近任务服务器", host=login.host, port=port, user=login.user, path=path, keyAuthentication=true)
        val store = ServerProfiles(compose.activity)
        try {
            store.save(profile, SavedCredentials("", key, ""))
            store.rememberTask(RecentTask(profile.id, profile.host, port, profile.user, path,
                args.getString("fixtureHostKey")!!, original.identity, "recent-phone", "agent"))
            compose.activity.getSharedPreferences("host-pins",0).edit().putString(login.endpoint,args.getString("fixtureHostKey")).commit()
            compose.activityRule.scenario.recreate()
            val tag = "task-${profile.id}-${original.pane}"
            compose.waitUntil(30000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
            var view = terminal()
            compose.waitUntil(30000) { evaluate(view,"connected") == "true" }
            val firstToken = evaluate(view,"connectionToken")
            compose.onNodeWithTag("command-input").performTextInput("UNSENT_DRAFT")
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(10000) { evaluate(view,"connected") == "false" }
            control.exec("$tmux new-window -t '$session' -n elsewhere 'stty -echo; cat'")
            compose.onNodeWithTag("reconnect-recent").performClick()
            compose.waitUntil(30000) { evaluate(view,"connected") == "true" }
            assertNotEquals(firstToken,evaluate(view,"connectionToken"))
            assertEquals(original,control.bindPane(session,path))
            compose.onNodeWithTag("command-input").assertTextContains("UNSENT_DRAFT")
            assertFalse(control.exec(dev.mtmux.core.Tmux.history(original.pane,path)).output.contains("UNSENT_DRAFT"))
            evaluate(view,"sendDraft('STALE_REPLAY', $firstToken, 'old-request', true); true")
            Thread.sleep(150)
            assertFalse(control.exec(dev.mtmux.core.Tmux.history(original.pane,path)).output.contains("STALE_REPLAY"))
            disconnectFixture()
            compose.activity.getSharedPreferences("host-pins",0).edit().putString(login.endpoint,"changed-test-pin").commit()
            compose.onNodeWithTag("reconnect-recent").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("服务器信任记录已改变",substring=true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("false",evaluate(view,"connected"))
            assertEquals(original.identity,store.recent().first { it.profileId==profile.id }.identity)
            compose.activity.getSharedPreferences("host-pins",0).edit().putString(login.endpoint,args.getString("fixtureHostKey")).commit()
            control.exec("$tmux respawn-pane -k -t '${original.pane}' 'stty -echo; cat'")
            compose.onNodeWithTag("reconnect-recent").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("原任务已结束", substring=true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("false",evaluate(view,"connected"))
            compose.onNodeWithTag("command-input").assertTextContains("UNSENT_DRAFT")
            assertEquals(original.identity,store.recent().first { it.profileId==profile.id }.identity)
            saveScreenshot("recent-task-stale-blocked")
            compose.onNodeWithText("重新选择任务").performClick()
            compose.onNodeWithTag("refresh-${profile.id}").performScrollTo().performClick()
            compose.waitUntil(30000) { ServerCache(compose.activity).read(profile)?.tasks?.any { it.binding?.pane==original.pane && it.identity!=original.identity } == true }
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.waitUntil(30000) { evaluate(view,"connected") == "true" }
            assertNotEquals(original.identity,store.recent().first().identity)
            assertFalse(control.exec(dev.mtmux.core.Tmux.history(original.pane,path)).output.contains("UNSENT_DRAFT"))
            disconnectFixture()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            saveScreenshot("recent-task-entry")
        } finally {
            store.delete(profile.id)
            control.exec("$tmux kill-session -t '$session'")
            control.close()
        }
    }

    @Test fun recentTasksAreBoundedAndInvalidatedByProfileEditsAndDeletion() {
        val store = ServerProfiles(compose.activity)
        val profile = ServerProfile(name="recent-store-test",host="test.invalid",port=22,user="test",path="tmux",keyAuthentication=false)
        store.save(profile,SavedCredentials("local-test-password",null,""))
        try {
            repeat(12) { index -> store.rememberTask(RecentTask(profile.id,profile.host,22,profile.user,"tmux","test-host-key",
                "123:456:$1:@$index:%$index:789","session","window",index.toLong())) }
            assertEquals(10,store.recent().count { it.profileId==profile.id })
            assertEquals("%11",store.recent().first().binding!!.pane)
            assertEquals(profile.id,store.last())
            assertFalse(compose.activity.getSharedPreferences("server-profiles",0).getString("recent-tasks","")!!.contains("local-test-password"))
            store.save(profile.copy(name="renamed"))
            assertEquals(10,store.recent().count { it.profileId==profile.id })
            store.rememberTask(RecentTask(profile.id,profile.host,22,profile.user,"tmux","test-host-key",
                "123:456:$1:@11:%11:999","session","window",20))
            assertEquals(10,store.recent().count { it.profileId==profile.id })
            assertEquals(1,store.recent().count { it.profileId==profile.id && it.binding?.pane=="%11" })
            store.rememberTask(RecentTask(profile.id,profile.host,22,profile.user,"tmux","test-host-key",
                "999:999:$1:@0:%0:999","session","window",21))
            assertEquals(1,store.recent().count { it.profileId==profile.id })
            store.save(profile.copy(host="changed.invalid"),SavedCredentials("replacement",null,""))
            assertTrue(store.recent().none { it.profileId==profile.id })
            store.save(profile,SavedCredentials("local-test-password",null,""))
            assertTrue(store.recent().none { it.profileId==profile.id })
            store.rememberTask(RecentTask(profile.id,profile.host,22,profile.user,"tmux","test-host-key",null,"",""))
            store.delete(profile.id)
            assertTrue(store.recent().none { it.profileId==profile.id })
        } finally { store.delete(profile.id) }
    }

    @Test fun draftIsRetainedWhenDesktopChangesPane() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureTmux"))
        val port = args.getString("fixturePort")!!.toInt()
        val path = args.getString("fixtureTmux")!!
        val login = Login("127.0.0.1", port, args.getString("fixtureUser")!!, "",
            Base64.getDecoder().decode(args.getString("fixtureKey")))
        val controller = dev.mtmux.core.SshClient(object : dev.mtmux.core.PinStore {
            override fun get(endpoint: String) = args.getString("fixtureHostKey")
        })
        controller.connect(login)
        val tmux = dev.mtmux.core.Tmux.quote(path)
        val session = controller.exec("$tmux new-session -d -P -F '#{session_id}' -s phone-binding -x 80 -y 24 'stty -echo; cat'").output.trim()
        try {
            val bound = controller.bindPane(session, path)
            showTerminalForFixture()
            val view = terminal()
            compose.waitUntil(30000) { evaluate(view, "typeof window.receive") == "\"function\"" }
            compose.runOnUiThread {
                compose.activity.getSharedPreferences("host-pins", 0).edit()
                    .putString("[127.0.0.1]:$port", args.getString("fixtureHostKey")).commit()
                val method = MainActivity::class.java.getDeclaredMethod("connect", Login::class.java,
                    String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(compose.activity, login, path, session, false)
            }
            compose.waitUntil(30000) { evaluate(view, "connected") == "true" }
            // A real xterm DA2 response must go to the attached tmux client, not pane paste.
            evaluate(view,"window.daReplies=[];terminal.onData(d=>window.daReplies.push(d));true")
            val connectionToken=org.json.JSONTokener(evaluate(view,"connectionToken")).nextValue() as String
            repeat(3) { view.render("\u001b[>c".toByteArray(),connectionToken) }
            compose.waitUntil(10000) { evaluate(view,"daReplies.filter(d=>d==='\\x1b[>0;276;0c').length").toInt()>=3 }
            // Flush the same writer queue with a legitimate bound input before checking capture.
            compose.onNodeWithTag("command-input").performTextInput("DA_ROUTE_BARRIER")
            compose.onNodeWithText("发送回车").performClick()
            compose.waitUntil(10000) { controller.exec(dev.mtmux.core.Tmux.history(bound.pane,path)).output.contains("DA_ROUTE_BARRIER") }
            assertFalse(controller.exec(dev.mtmux.core.Tmux.history(bound.pane,path)).output.contains("0;276;0c"))
            compose.onNodeWithText("工具").performClick()
            compose.onNodeWithTag("reply-target").assertTextEquals("回复目标 ${bound.pane}")
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("command-input").performTextInput("PHONE_BOUND_REPLY")
            val second = controller.exec("$tmux split-window -h -P -F '#{pane_id}' -t '${bound.pane}' 'stty -echo; cat'").output.trim()
            assertTrue("split pane failed: $second", second.matches(Regex("%[0-9]+")))
            compose.onNodeWithText("发送回车").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("未发送：", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("command-input").assertTextContains("PHONE_BOUND_REPLY")
            fun capture(pane: String) = controller.exec(dev.mtmux.core.Tmux.history(pane, path)).output
            assertFalse(capture(second).contains("PHONE_BOUND_REPLY"))
            assertFalse(capture(bound.pane).contains("PHONE_BOUND_REPLY"))
            controller.exec("$tmux select-pane -t '${bound.pane}'")
            compose.onNodeWithText("发送回车").performClick()
            compose.waitUntil(10000) { capture(bound.pane).contains("PHONE_BOUND_REPLY") }
            compose.onNodeWithTag("command-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            assertFalse(capture(second).contains("PHONE_BOUND_REPLY"))
            controller.exec("$tmux select-pane -t '$second'")
            compose.onNodeWithText("工具").performClick()
            compose.onNodeWithText("使用当前 pane").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("回复目标 $second").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("command-input").performTextInput("EXPLICIT_NEW_TARGET")
            compose.onNodeWithText("发送回车").performClick()
            compose.waitUntil(10000) { capture(second).contains("EXPLICIT_NEW_TARGET") }
            assertFalse(capture(bound.pane).contains("EXPLICIT_NEW_TARGET"))
            saveScreenshot("bound-pane-reply")
            disconnectFixture()
        } finally {
            controller.exec("$tmux kill-session -t '$session'")
            controller.close()
        }
    }

    @Test fun touchScrollAndClickOperateTheLiveTmuxTerminal() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureTmux"))
        val port = args.getString("fixturePort")!!.toInt()
        val path = args.getString("fixtureTmux")!!
        val login = Login("127.0.0.1", port, args.getString("fixtureUser")!!, "",
            Base64.getDecoder().decode(args.getString("fixtureKey")))
        val controller = dev.mtmux.core.SshClient(object : dev.mtmux.core.PinStore {
            override fun get(endpoint: String) = args.getString("fixtureHostKey")
        })
        controller.connect(login)
        val tmux = dev.mtmux.core.Tmux.quote(path) + " -u"
        fun state(format: String) = controller.exec("$tmux display-message -p -t '%0' '$format'").output.trim()
        var secondPane: String? = null
        try {
            showTerminalForFixture()
            val view = terminal()
            compose.waitUntil(30000) { evaluate(view, "typeof window.receive") == "\"function\"" }
            compose.runOnUiThread {
                compose.activity.getSharedPreferences("host-pins", 0).edit()
                    .putString("[127.0.0.1]:$port", args.getString("fixtureHostKey")).commit()
                val method = MainActivity::class.java.getDeclaredMethod("connect", Login::class.java,
                    String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(compose.activity, login, path, "$0", false)
            }
            compose.waitUntil(30000) { evaluate(view, "terminal.modes.mouseTrackingMode !== 'none'") == "true" }
            val webCols=evaluate(view,"terminal.cols").toInt()
            val webRows=evaluate(view,"terminal.rows").toInt()
            compose.waitUntil(10000) {
                controller.exec("$tmux list-clients -F '#{client_width}:#{client_height}'").output.trim()=="$webCols:$webRows"
            }
            assertEquals("tmux pane must match the mobile width",webCols,state("#{pane_width}").toInt())
            // This fixture has one tmux status row.
            assertEquals(webRows-1,state("#{pane_height}").toInt())
            println("LIVE_GEOMETRY xterm=$webCols:$webRows tmuxPane="+state("#{pane_width}:#{pane_height}"))
            // Real-time held gesture: the old fast Compose swipe never exercised
            // WebView long-press timers or continued drag after a remote redraw.
            val location = IntArray(2)
            compose.runOnUiThread { view.getLocationOnScreen(location) }
            val downAt = android.os.SystemClock.uptimeMillis()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            fun touch(action: Int, fraction: Float) {
                val event = android.view.MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action,
                    location[0] + view.width * 0.1f, location[1] + view.height * fraction, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            evaluate(view, "window.slowTrace=[];['touchstart','touchmove','touchcancel','touchend'].forEach(t=>document.addEventListener(t,e=>slowTrace.push([t,terminal.modes.mouseTrackingMode,e.touches[0]?.clientY]),true));true")
            var previous = 0
            touch(android.view.MotionEvent.ACTION_DOWN, 0.18f)
            try {
                for (stage in 0..2) {
                    for (step in 1..12) {
                        Thread.sleep(60)
                        touch(android.view.MotionEvent.ACTION_MOVE, 0.18f + stage * 0.17f + step * 0.17f / 12)
                    }
                    Thread.sleep(180)
                    val position = state("#{scroll_position}").toIntOrNull() ?: 0
                    println("SLOW_DRAG stage=$stage previous=$previous position=$position trace=" + evaluate(view, "JSON.stringify(slowTrace)"))
                    assertTrue("Held slow drag stopped at stage $stage: $previous -> $position", position > previous)
                    previous = position
                }
            } finally { touch(android.view.MotionEvent.ACTION_UP, 0.69f) }
            compose.waitUntil(10000) { state("#{pane_in_mode}") == "1" && state("#{scroll_position}").toIntOrNull()?.let { it > 0 } == true }
            val before = state("#{scroll_position}").toInt()
            saveScreenshot("live-tmux-touch-history")
            val tmuxAnchor=state("#{pane_width}:#{pane_height}:#{scroll_position}:#{pane_in_mode}")
            val topLine=evaluate(view,"terminal.buffer.active.getLine(terminal.buffer.active.viewportY).translateToString(true)")
            repeat(3) {
                compose.onNodeWithTag("command-input").performClick()
                compose.waitUntil(10000) { var open=false;compose.runOnUiThread {open=ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==true};open }
                Thread.sleep(300)
                assertEquals(tmuxAnchor,state("#{pane_width}:#{pane_height}:#{scroll_position}:#{pane_in_mode}"))
                assertEquals(topLine,evaluate(view,"terminal.buffer.active.getLine(terminal.buffer.active.viewportY).translateToString(true)"))
                if(it==0) saveScreenshot("terminal-tmux-keyboard-anchor")
                compose.onNodeWithText("收起键盘").performClick()
                compose.waitUntil(10000) { var closed=false;compose.runOnUiThread {closed=ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==false};closed }
                Thread.sleep(300)
                assertEquals(tmuxAnchor,state("#{pane_width}:#{pane_height}:#{scroll_position}:#{pane_in_mode}"))
            }
            // The bottom terminal row is tmux's status bar: wheel there selects windows.
            // Scroll within pane content, just as a desktop mouse would.
            compose.onNodeWithTag("terminal-webview").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.8f),
                    androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.2f))
            }
            compose.waitUntil(10000) { state("#{pane_in_mode}") == "0" || (state("#{scroll_position}").toIntOrNull() ?: before) < before }
            compose.onNodeWithTag("terminal-latest").performClick()
            compose.waitUntil(10000) { state("#{pane_in_mode}") == "0" }
            secondPane = controller.exec("$tmux split-window -h -d -P -F '#{pane_id}' -t '%0' 'sleep 300'").output.trim()
            assertTrue(secondPane!!.matches(Regex("%[0-9]+")))
            Thread.sleep(500)
            compose.onNodeWithTag("terminal-webview").performTouchInput {
                click(androidx.compose.ui.geometry.Offset(width * 0.75f, height * 0.35f))
            }
            compose.waitUntil(10000) {
                controller.exec("$tmux display-message -p -t '$0' '#{pane_id}'").output.trim() == secondPane
            }
            saveScreenshot("live-tmux-tap-pane")
            disconnectFixture()
        } finally {
            secondPane?.takeIf { it.matches(Regex("%[0-9]+")) }?.let { controller.exec("$tmux kill-pane -t '$it'") }
            controller.exec("$tmux send-keys -X -t '%0' cancel")
            controller.close()
        }
    }

    @Test fun mouseEventsReachAFullScreenApplicationInsideTmux() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("fixtureMouseCommand"))
        val port = args.getString("fixturePort")!!.toInt()
        val path = args.getString("fixtureTmux")!!
        val login = Login("127.0.0.1", port, args.getString("fixtureUser")!!, "",
            Base64.getDecoder().decode(args.getString("fixtureKey")))
        val controller = dev.mtmux.core.SshClient(object : dev.mtmux.core.PinStore {
            override fun get(endpoint: String) = args.getString("fixtureHostKey")
        })
        controller.connect(login)
        val tmux = dev.mtmux.core.Tmux.quote(path) + " -u"
        val pane = controller.exec("$tmux new-window -P -F '#{pane_id}' -t '$0' -n mouse-tui " +
            dev.mtmux.core.Tmux.quote(args.getString("fixtureMouseCommand")!!)).output.trim()
        assertTrue(pane.matches(Regex("%[0-9]+")))
        fun capture() = controller.exec("$tmux capture-pane -p -t '$pane'").output
        try {
            showTerminalForFixture()
            val view = terminal()
            compose.waitUntil(30000) { evaluate(view, "typeof window.receive") == "\"function\"" }
            compose.runOnUiThread {
                compose.activity.getSharedPreferences("host-pins", 0).edit()
                    .putString("[127.0.0.1]:$port", args.getString("fixtureHostKey")).commit()
                val method = MainActivity::class.java.getDeclaredMethod("connect", Login::class.java,
                    String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(compose.activity, login, path, "$0", false)
            }
            compose.waitUntil(30000) { evaluate(view, "terminal.buffer.active.getLine(0).translateToString(true)").contains("SYNTHETIC_MOUSE_TUI") }
            compose.onNodeWithTag("terminal-webview").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * 0.4f, height * 0.2f), androidx.compose.ui.geometry.Offset(width * 0.4f, height * 0.7f))
            }
            compose.waitUntil(10000) { Regex("UP=[1-9][0-9]*").containsMatchIn(capture()) }
            compose.onNodeWithTag("terminal-webview").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * 0.4f, height * 0.7f), androidx.compose.ui.geometry.Offset(width * 0.4f, height * 0.2f))
            }
            compose.waitUntil(10000) { Regex("DOWN=[1-9][0-9]*").containsMatchIn(capture()) }
            assertTrue("Scrolling must not click", capture().contains("CLICK=0"))
            compose.onNodeWithTag("terminal-webview").performTouchInput { click(center) }
            compose.waitUntil(10000) { capture().contains("CLICK=1") }
            saveScreenshot("mouse-inside-fullscreen-tui")
            disconnectFixture()
        } finally {
            controller.exec("$tmux kill-pane -t '$pane'")
            controller.exec("$tmux select-window -t '$0:@0'")
            controller.close()
        }
    }

}
