package dev.mtmux

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import dev.mtmux.core.*
import kotlinx.coroutines.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
    private val closer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var terminalView: TerminalView? = null
    private var client: SshClient? = null
    @Volatile private var generation = 0L
    private var wireToken by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var pastePending by mutableStateOf(false)
    private var renderedBytes by mutableLongStateOf(0L)
    private var status by mutableStateOf("配置测试服务器后，先发现会话或连接 shell")
    private var panes by mutableStateOf(emptyList<Pane>())
    private var challenge by mutableStateOf<HostKeyChallenge?>(null)
    private var terminalReady by mutableStateOf(false)
    private var target by mutableStateOf("尚未连接")
    private var enableTmuxMouse by mutableStateOf(true)
    private var connectedPath = "tmux"
    private var connectedSession by mutableStateOf<String?>(null)
    private var replyPane by mutableStateOf<PaneBinding?>(null)
    private var draftScopeKey by mutableStateOf("")
    private var reading by mutableStateOf(false)
    private var cols = 80
    private var rows = 24
    private val profileRepository by lazy { ServerProfiles(this) }
    private var recentTasks by mutableStateOf(emptyList<RecentTask>())
    private var activeProfile: ServerProfile? = null
    private var showConfig by mutableStateOf(true)
    private var initialHomeRefresh = true
    private val pins by lazy { getSharedPreferences("host-pins", MODE_PRIVATE) }

    private val privacySettings by lazy { PrivacyDisplay(this) }
    private var privacyDisplay by mutableStateOf(true)
    private val protectedContent = mutableSetOf<Any>()
    private var capturePaused = true

    internal fun protectCapture(owner: Any, protect: Boolean) {
        if (protect) protectedContent.add(owner) else protectedContent.remove(owner)
        updateCapturePolicy()
    }
    private var captureRevision = 0
    private fun updateCapturePolicy() {
        val revision = ++captureRevision
        if (!privacyDisplay || protectedContent.isNotEmpty() || capturePaused) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            // Wait for the redacted/new page to paint before making its surface capturable.
            window.decorView.postOnAnimation { window.decorView.postOnAnimation {
                if (captureRevision == revision && !isDestroyed)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } }
        }
    }
    private fun changePrivacyDisplay(enabled: Boolean) {
        // Protect immediately before exposing connection fields. When enabling privacy,
        // release only after the redacted composition has had a frame to paint.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        privacySettings.enabled(enabled)
        privacyDisplay = enabled
        updateCapturePolicy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        privacyDisplay = privacySettings.enabled()
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        updateCapturePolicy()
        recentTasks = profileRepository.recent()
        setContent { App() }
    }

    private fun disconnect(message: String = "已断开；不会自动重发输入", clear: Boolean = false) {
        DebugLog.event(DebugLog.Event.DISCONNECT,generation.toInt(),if(client!=null) 1 else 0)
        if(client!=null) DebugLog.event(DebugLog.Event.OUTPUT_SUMMARY,generation.toInt(),renderedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        generation++
        val previous = client
        previous?.invalidateInput()
        client = null
        if (previous != null) closer.execute { previous.close() }
        writer.queue.clear()
        wireToken = ""
        busy = false
        pastePending = false
        terminalView?.connection("")
        if (clear) {
            terminalView?.resetScreen()
            renderedBytes = 0L
            target = "尚未连接"
            reading = false
        }
        connectedSession = null
        replyPane = null
        status = message
    }

    override fun onResume() { super.onResume(); capturePaused = false; updateCapturePolicy(); DebugLog.event(DebugLog.Event.RESUME) }
    override fun onPause() {
        capturePaused = true
        updateCapturePolicy()
        super.onPause()
    }

    override fun onStop() {
        DebugLog.event(DebugLog.Event.STOP)
        super.onStop()
        disconnect("App 已退后台，SSH 已断开；返回后可重新连接")
    }

    override fun onDestroy() {
        DebugLog.event(DebugLog.Event.DESTROY)
        disconnect()
        scope.cancel()
        writer.shutdownNow()
        closer.shutdown()
        terminalView?.dispose()
        terminalView = null
        super.onDestroy()
    }

    private fun submit(token: String, text: String, onWritten: (() -> Unit)? = null) =
        submitBytes(token, text.toByteArray(Charsets.UTF_8), onWritten)

    private fun submitBytes(token: String, bytes: ByteArray, onWritten: (() -> Unit)? = null, bound: PaneBinding? = null) {
        val connection = client ?: return
        if (token != wireToken || token.isEmpty()) return
        val epoch = connection.token() ?: return
        if (bytes.size > 65536) {
            status = "输入过长；未发送"
            return
        }
        try {
            writer.execute {
                try {
                    if (bound != null) {
                        if (!connection.sendToPane(epoch, bound, connectedPath, bytes)) {
                            DebugLog.event(DebugLog.Event.INPUT_BLOCKED)
                            runOnUiThread { if (client === connection && token == wireToken) {
                                pastePending = false
                                status = "未发送：回复目标已变化、正在阅读历史或开启同步输入；草稿已保留"
                            } }
                            return@execute
                        }
                    } else connection.send(epoch, bytes)
                    runOnUiThread { if (client === connection && token == wireToken) onWritten?.invoke() }
                }
                catch (error: Exception) { DebugLog.event(DebugLog.Event.INPUT_FAILED,error=error); runOnUiThread {
                    if (client === connection) disconnect("发送结果不确定；请检查远端输出，不会自动重发")
                } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            disconnect("输入过快，已断开；请核对远端结果，不会自动重发")
        }
    }

    private fun connect(login: Login, path: String, session: String?, discoverOnly: Boolean) =
        connectTask(login, path, session, discoverOnly, null, null)

    private fun rememberTask(profile: ServerProfile?, binding: PaneBinding?, metadata: List<Pane>): Boolean {
        if (profile == null) return true
        val pane = metadata.firstOrNull { it.id == binding?.pane }
        val task = RecentTask(profile.id, profile.host, profile.port, profile.user, profile.path,
            pins.getString(profile.trustEndpoint(), null) ?: return false,
            binding?.identity, pane?.sessionName.orEmpty(), pane?.windowName.orEmpty(), route = profile.routeContext())
        return runCatching {
            profileRepository.rememberTask(task)
            recentTasks = profileRepository.recent()
        }.isSuccess
    }

    private fun connectTask(login: Login, path: String, session: String?, discoverOnly: Boolean,
                            profile: ServerProfile?, recent: RecentTask?) {
        if (!discoverOnly) showConfig = false
        disconnect("连接中…", clear = true)
        renderedBytes = 0L
        panes = emptyList()
        challenge = null
        busy = true
        val attempt = generation
        DebugLog.event(DebugLog.Event.CONNECT,attempt.toInt(),login.jumps.size)
        activeProfile = profile
        val mouseForSession = profile?.enableTmuxMouse ?: enableTmuxMouse
        val connection = SshClient(object : PinStore {
            override fun get(endpoint: String) = pins.getString(endpoint, null)
        })
        client = connection
        scope.launch {
            try {
                var binding: PaneBinding? = null
                val found = withContext(Dispatchers.IO) {
                    if (recent != null) {
                        check(profile != null && recent.matches(profile)) { "服务器配置已改变，请重新选择任务" }
                        check(pins.getString(login.trustEndpoint, null) == recent.hostKey) { "服务器信任记录已改变，请重新发现并选择任务" }
                    }
                    connection.connect(login) { progress -> DebugLog.stage(progress,attempt.toInt()); scope.launch { if (attempt == generation && busy) status = progress } }
                    withContext(Dispatchers.Main) { if (attempt == generation) status = if (session != null || discoverOnly) "SSH 已连接 · 正在核对 tmux 目标…" else "SSH 已连接 · 正在打开终端…" }
                    if (attempt != generation) { connection.close(); error("连接已取消") }
                    if (discoverOnly) {
                        connection.discoverPanes(path)
                    } else {
                        val expected = recent?.binding
                        expected?.let { connection.verifyResume(it, path) }
                        val metadata = if (session != null) connection.discoverPanes(path).filter { it.session == session } else emptyList()
                        check(session == null || metadata.isNotEmpty()) { "tmux 会话已消失，请重新发现" }
                        if (session != null && mouseForSession) {
                            check(connection.exec(Tmux.enableMouse(session, path)).status == 0) { "无法为该 tmux 会话开启鼠标，请检查权限" }
                        }
                        binding = expected ?: session?.let { connection.bindPane(it, path) }
                        connection.openTerminal(expected?.let { Tmux.resume(it, path) } ?: session?.let { Tmux.attach(it, path) }, cols, rows)
                        expected?.let { connection.verifyResume(it, path) }
                        metadata
                    }
                }
                if (attempt != generation) { connection.close(); return@launch }
                busy = false
                DebugLog.event(DebugLog.Event.CONNECTED,attempt.toInt(),if(discoverOnly) 0 else 1)
                if (discoverOnly) {
                    panes = found
                    status = if (found.isEmpty()) "未发现 pane；仍可连接 shell" else "发现 ${found.size} 个 pane，可按名称选择会话"
                    connection.close()
                    client = null
                } else {
                    panes = found
                    connectedPath = path
                    connectedSession = session
                    replyPane = binding
                    draftScopeKey = "${login.trustEndpoint}/${login.user}/${binding?.identity ?: "shell"}"
                    target = "${login.user}@${login.host}:${login.port} · ${found.firstOrNull()?.sessionName ?: "普通 shell"}"
                    wireToken = "$attempt:${connection.token()}"
                    terminalView?.resetScreen()
                    terminalView?.connection(wireToken)
                    status = if (session == null) "普通 shell：不保证断线后任务存活" else "已连接：回复绑定到指定 pane；窗口与尺寸仍与电脑共享"
                    if (!rememberTask(profile, binding, found)) status += "；最近任务未能保存"
                    val renderToken = wireToken
                    withContext(Dispatchers.IO) { connection.pump { terminalView?.render(it, renderToken) ?: error("终端不存在") } }
                    if (attempt == generation) disconnect("终端已断开；核对远端结果后手动重连，不会重放输入")
                }
            } catch (error: Exception) {
                DebugLog.event(DebugLog.Event.CONNECTION_FAILED,attempt.toInt(),error=error)
                connection.close()
                if (attempt == generation) {
                    busy = false
                    wireToken = ""
                    pastePending = false
                    terminalView?.connection("")
                    client = null
                    if (error is HostKeyRejected) {
                        challenge = error.challenge
                        status = "连接已拦截：请核对服务器主机密钥"
                    } else {
                        // Never display arbitrary SSH exception text that might contain private paths/data.
                        status = connectionErrorText(error)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun App() {
        val profileStore = remember { ServerProfiles(this) }
        val terminalPreferences = remember { getSharedPreferences("terminal-settings", MODE_PRIVATE) }
        var appearance by remember { mutableStateOf(AppAppearance.read(terminalPreferences.getString("appearance",null))) }
        val dark=appearance.dark(isSystemInDarkTheme())
        val currentDark by rememberUpdatedState(dark)
        var quickReplyToken by remember { mutableStateOf<String?>(null) }
        var pendingReply by remember { mutableStateOf<Pair<String,String>?>(null) }
        LaunchedEffect(dark) {
            val background=if(dark) 0xFF111916.toInt() else 0xFFF7FAF8.toInt()
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background))
            val bars=if(dark) SystemBarStyle.dark(background) else SystemBarStyle.light(background,background)
            enableEdgeToEdge(statusBarStyle=bars,navigationBarStyle=bars)
            WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }
            terminalView?.appearance(dark)
        }
        var remoteTouch by remember { mutableStateOf(terminalPreferences.getBoolean("remoteTouch", true)) }
        var scrollSensitivity by remember { mutableFloatStateOf(terminalPreferences.getFloat("scrollSensitivity",1f)) }
        var draft by remember { mutableStateOf("") }
        var draftTarget by remember { mutableStateOf("") }
        val drafts = remember { mutableMapOf<String, String>() }
        LaunchedEffect(wireToken, draftScopeKey) {
            if (wireToken.isNotEmpty() && draftScopeKey != draftTarget) {
                drafts[draftTarget] = draft
                draft = drafts[draftScopeKey].orEmpty()
                draftTarget = draftScopeKey
            }
        }
        var fontSize by remember { mutableIntStateOf(terminalPreferences.getInt("fontSize",14)) }
        var preview by remember { mutableStateOf(false) }
        var previewEnter by remember { mutableStateOf(false) }
        var pendingDraft by remember { mutableStateOf<PendingDraft?>(null) }
        var draftSequence by remember { mutableLongStateOf(0L) }
        var showTools by remember { mutableStateOf(false) }
        var showDetails by remember { mutableStateOf(false) }
        var copySnapshot by remember { mutableStateOf<TerminalSnapshot?>(null) }
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val draftFocus = remember { FocusRequester() }
        val density = LocalDensity.current
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        var visibleTerminalHeight by remember { mutableIntStateOf(0) }
        fun latest() {
            val session = connectedSession
            val connection = client
            val token = wireToken
            if (session == null || connection == null) { terminalView?.latest(); return }
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val visible = connection.bindPane(session, connectedPath)
                    val response = connection.exec(Tmux.leaveCopyMode(visible, connectedPath))
                    check(response.status == 0 && response.output.trim() == "MTMUX_LATEST") { "目标已变化，请重试" }
                } }
                if (token == wireToken) result.fold({ terminalView?.latest(); reading = false }, { status = "回到底端失败：目标已变化或连接不可用" })
            }
        }
        fun resumeTask(task: RecentTask) {
            if (busy || wireToken.isNotEmpty() || !terminalReady) return
            val profile = profileStore.all().firstOrNull { task.matches(it) }
            if (profile == null) { status = "服务器配置已改变，请重新选择任务"; recentTasks = profileStore.recent(); return }
            try {
                connectTask(profileStore.login(profile), profile.path, task.binding?.session, false, profile, task)
                focusManager.clearFocus(force = true); keyboard?.hide()
            } catch (_: Exception) { status = "无法读取最近任务或本机凭据，请重新选择配置" }
        }
        fun home() {
            disconnect("已返回服务器列表")
            focusManager.clearFocus(force = true); keyboard?.hide(); showConfig = true
        }
        BackHandler(enabled = !showConfig) { home() }
        fun sendDraft(text: String, enter: Boolean, token: String = wireToken) {
            if (token.isEmpty() || token != wireToken || pastePending) return
            draftSequence++
            val request = PendingDraft(draftSequence.toString(), token, text, enter)
            pendingDraft = request
            pastePending = true
            terminalView?.sendDraft(text, token, request.id, enter)
        }
        fun requestDraft(enter: Boolean) {
            if (draft.isEmpty()) {
                if (enter) submitBytes(wireToken, "\r".toByteArray(), bound = replyPane)
            } else if (draft.contains('\n') || draft.contains('\r') || !enter) {
                previewEnter = enter
                preview = true
            } else sendDraft(draft, enter)
        }
        MaterialTheme(colorScheme = appColors(dark)) {
            Surface(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                // IME only clips the visible lower part. The WebView/grid keeps its full
                // keyboard-free size, so focus changes cannot resize/reflow remote tmux.
                val gridHeight = (maxHeight - 204.dp).coerceAtLeast(48.dp)
                Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { home() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(40.dp).semantics { contentDescription = "返回服务器首页" }.testTag("terminal-home")) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                        val server = activeProfile?.let { privacySettings.label(it, privacyDisplay) } ?: if (privacyDisplay && target != "尚未连接") "当前服务器" else target.substringBefore(" · ")
                        val pane = panes.firstOrNull { it.id == replyPane?.pane }
                        val taskName = pane?.let { "${it.sessionName} / ${it.windowName}" } ?: if (connectedSession != null) "tmux" else "SSH"
                        Text("$server · $taskName", Modifier.weight(1f).clickable { showDetails = true }.testTag("terminal-title"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { latest() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp).semantics { contentDescription = "回到底端" }.testTag("terminal-latest")) { Text("⇣", style = MaterialTheme.typography.headlineSmall) }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { visibleTerminalHeight = it.height; terminalView?.viewport(it.height / density.density) }.testTag("terminal-panel")) {
                    AndroidView(factory = { context ->
                        TerminalView(context,
                            onReady = { c, r -> cols = c; rows = r; terminalReady = true; terminalView?.appearance(currentDark,force=true); terminalView?.scrollSensitivity(scrollSensitivity); terminalView?.font(fontSize); terminalView?.remoteTouch(remoteTouch); terminalView?.viewport(visibleTerminalHeight / density.density) },
                            onResize = { c, r ->
                                cols = c; rows = r
                                val current = client
                                scope.launch(Dispatchers.IO) { runCatching { current?.resize(c, r) } }
                            },
                            onInput = { token, data ->
                                // xterm's DA/color replies must reach tmux's client parser. Pasting them
                                // into the bound pane leaks protocol text into the remote editor.
                                if (TerminalInput.isClientReply(data) || Regex("\\u001b\\[<[0-9]+;[0-9]+;[0-9]+[Mm]").matches(data)) submit(token, data)
                                else submitBytes(token, data.toByteArray(Charsets.UTF_8), bound = replyPane)
                            },
                            onDraftInput = { token, id, data ->
                                val request = pendingDraft
                                if (request != null && request.token == token && request.id == id && token == wireToken) {
                                    submitBytes(token, data.toByteArray(Charsets.UTF_8), bound = replyPane, onWritten = {
                                        if (draft == request.text) {
                                            draft = ""
                                            if (request.enter) {
                                                focusManager.clearFocus()
                                                keyboard?.hide()
                                            }
                                        }
                                        pendingDraft = null
                                        pastePending = false
                                        status = if (request.enter) "已发送文本和回车，请查看终端结果" else "已粘贴文本；点击 Enter 提交"
                                    })
                                }
                            },
                            onRendered = { if(renderedBytes==0L) DebugLog.event(DebugLog.Event.FIRST_RENDER,generation.toInt()); renderedBytes += it },
                            onFailure = { message -> disconnect(message); terminalReady = false },
                            onPasteFinished = { if (it == wireToken) pastePending = false },
                            onNotice = { status = it },
                            onReading = { reading = it },
                            onBinaryInput = { token, bytes -> submitBytes(token, bytes) }
                        ).also { terminalView = it }
                    }, update = { it.appearance(dark); it.viewport(visibleTerminalHeight / density.density) }, modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).requiredHeight(gridHeight).align(Alignment.TopStart).testTag("terminal-webview"))
                    if (renderedBytes == 0L) {
                        Text(
                            when {
                                !terminalReady -> "终端显示区加载中…"
                                wireToken.isNotEmpty() -> "SSH 已连接，等待服务器输出。\n在下方输入 pwd，再点“发送并回车”。"
                                busy -> "正在建立连接，请查看上方进度…"
                                else -> "尚未连接 · 请查看上方提示\n可返回首页检查配置或重新连接。"
                            },
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // Status overlays do not change terminal size or move its reading anchor.
                    if (status.isNotBlank() && (wireToken.isEmpty() || status.contains("未发送") || status.contains("失败") || status.contains("已改变") || status.contains("已结束") || status.contains("未能"))) {
                        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color=MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(8.dp)) {
                                Text(status, modifier=Modifier.testTag("connection-status"), style=MaterialTheme.typography.bodySmall)
                                if(wireToken.isEmpty() && renderedBytes>0) Text("已断开 · 以下为历史内容",modifier=Modifier.testTag("disconnected-history"),color=MaterialTheme.colorScheme.error)
                                if(wireToken.isEmpty() && !busy && recentTasks.isNotEmpty()) Row {
                                    TextButton(onClick={resumeTask(recentTasks.first())},enabled=terminalReady,modifier=Modifier.testTag("reconnect-recent")){Text("重连最近任务")}
                                    TextButton(onClick={home()}){Text("重新选择任务")}
                                }
                            }
                        }
                    }
                    }
                    Column(Modifier.fillMaxWidth().height(156.dp)) {
                        TerminalKeys(enabled = wireToken.isNotEmpty() && !pastePending) { label, data ->
                            if (label == "Enter") requestDraft(true)
                            else submitBytes(wireToken, data.toByteArray(), bound = replyPane)
                        }
                        TextField(draft,{draft=it},placeholder={Text("输入命令或回复…")},maxLines=2,shape=RoundedCornerShape(10.dp),
                            colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
                            modifier=Modifier.fillMaxWidth().height(64.dp).focusRequester(draftFocus).testTag("command-input"))
                        Row(Modifier.fillMaxWidth().height(52.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick={showTools=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(0.7f)){Text("工具",maxLines=1)}
                            TextButton(onClick={focusManager.clearFocus();keyboard?.hide()},enabled=imeVisible,contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(1.1f).testTag("hide-keyboard")){Text("收起键盘",maxLines=1)}
                            TextButton(onClick={quickReplyToken=wireToken},enabled=wireToken.isNotEmpty() && !pastePending,contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(1.1f).testTag("quick-replies")){Text("快捷回复",maxLines=1)}
                            Button(onClick={requestDraft(true)},enabled=wireToken.isNotEmpty() && !pastePending && draft.isNotEmpty(),shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.weight(1.2f).testTag("send-enter")){Text(if(pastePending) "发送中…" else "发送回车",maxLines=1)}
                        }
                    }
                }
                }
                if (showConfig) {
                    ServerHome(privacyDisplay=privacyDisplay, onPrivacyDisplay=::changePrivacyDisplay, refreshOnEntry = initialHomeRefresh, onEntryHandled = { initialHomeRefresh = false }, onOpen = { profile, task ->
                        try {
                            connectTask(profileStore.login(profile),profile.path,task?.binding?.session,false,profile,task)
                        } catch (_: Exception) { showConfig = false; status = "凭据无法读取，请返回服务器列表编辑配置" }
                    }, appearance=appearance,onAppearance={appearance=it;terminalPreferences.edit {putString("appearance",it.name)}},
                        onPreferences = { font, speed, touch ->
                        fontSize=font;scrollSensitivity=speed;remoteTouch=touch
                        terminalView?.font(font);terminalView?.scrollSensitivity(speed);terminalView?.remoteTouch(touch)
                    })
                }
                if (showDetails) ProtectSensitiveContent()
                if (showDetails) AlertDialog(properties=protectedDialogProperties,onDismissRequest={showDetails=false},title={Text("当前连接")},text={Text("${activeProfile?.name.orEmpty()}\n$target\n${panes.firstOrNull { it.id == replyPane?.pane }?.let { "${it.sessionName} / ${it.windowName}" }.orEmpty()}\n$status")},confirmButton={TextButton(onClick={showDetails=false}){Text("关闭")}})
                if (showTools) {
                    ModalBottomSheet(onDismissRequest = { showTools = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal=16.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Text("终端工具",style=MaterialTheme.typography.titleLarge)
                    replyPane?.let { bound ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("回复目标 ${bound.pane}", modifier = Modifier.weight(1f).testTag("reply-target"))
                            TextButton(enabled = draft.isEmpty() && !pastePending && !busy, onClick = {
                                val connection = client ?: return@TextButton
                                val token = wireToken
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { connection.bindPane(bound.session, connectedPath) } }
                                    if (token == wireToken && client === connection) {
                                        busy = false
                                        result.fold({ next ->
                                            replyPane = next
                                            draftScopeKey = draftScopeKey.substringBeforeLast('/') + "/" + next.identity
                                            status = "回复目标已确认为 ${next.pane}"
                                            if (!rememberTask(activeProfile, next, panes)) status += "；最近任务未能保存"
                                        }, { status = "无法确认目标，请重新发现会话" })
                                    }
                                }
                            }) { Text("使用当前 pane") }
                        }
                    }

                            TextButton(onClick={showTools=false;requestDraft(false)},enabled=wireToken.isNotEmpty() && draft.isNotEmpty()){Text("仅粘贴")}

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = remoteTouch, onCheckedChange = { remoteTouch = it; terminalView?.remoteTouch(it); terminalPreferences.edit { putBoolean("remoteTouch", it) } })
                                Text(if (remoteTouch) "触摸发送滚轮和点击" else "仅滑动手机本地历史")
                            }
                            Text("轻点左键，长按后松开右键；上下滑动滚轮。实际行为由 tmux / Agent 的鼠标支持决定。", style = MaterialTheme.typography.bodySmall)
                            Text("远端滚动速度：${"%.1f".format(java.util.Locale.ROOT, scrollSensitivity)}×")
                            Slider(value = scrollSensitivity, onValueChange = {
                                scrollSensitivity = it; terminalView?.scrollSensitivity(it)
                            }, onValueChangeFinished = { terminalPreferences.edit { putFloat("scrollSensitivity", scrollSensitivity) } },
                                valueRange = 0.5f..2f, steps = 2, modifier = Modifier.testTag("scroll-speed"))
                            Text("慢拖逐步滚动，松手即停。", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(8); terminalView?.font(fontSize); terminalPreferences.edit { putInt("fontSize", fontSize) } }) { Text("字号−") }
                                TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28); terminalView?.font(fontSize); terminalPreferences.edit { putInt("fontSize", fontSize) } }) { Text("字号+") }
                            }
                            TextButton(onClick = {
                                showTools = false; keyboard?.hide()
                                terminalView?.snapshot { snapshot -> copySnapshot = snapshot }
                            }, modifier=Modifier.testTag("select-terminal-text")) { Text("选择复制") }
                            
                            TextButton(onClick = { draft = ""; showTools = false }) { Text("清空草稿") }
                            Text("终端 ${cols}×${rows}，已解析 $renderedBytes 字节", style = MaterialTheme.typography.bodySmall)
                        }
                            TextButton(onClick = { showTools = false }) { Text("关闭") }
                        }
                    }
                }
                quickReplyToken?.let { openedToken -> QuickReplySheet(onDismiss={quickReplyToken=null},onPick={text ->
                    quickReplyToken=null
                    if(openedToken!=wireToken || wireToken.isEmpty() || pastePending) status="连接已改变；快捷回复未填入"
                    else if(draft.isEmpty()) {draft=text}
                    else pendingReply=openedToken to text
                }) }
                pendingReply?.let { (token,text) -> AlertDialog(onDismissRequest={pendingReply=null},title={Text("保留已有草稿？")},text={Text("当前输入框已有内容。可以追加快捷回复，或明确替换草稿；都不会自动发送。")},confirmButton={
                    TextButton(onClick={if(token==wireToken && token.isNotEmpty() && !pastePending) {draft=if(draft.isEmpty()) text else draft+"\n"+text} else status="连接已改变；快捷回复未填入";pendingReply=null},modifier=Modifier.testTag("append-quick-reply")){Text("追加到草稿")}
                },dismissButton={Row {
                    TextButton(onClick={if(token==wireToken && token.isNotEmpty() && !pastePending) {draft=text} else status="连接已改变；快捷回复未填入";pendingReply=null},modifier=Modifier.testTag("replace-quick-reply")){Text("替换草稿")}
                    TextButton(onClick={pendingReply=null}){Text("取消")}
                }}) }
                copySnapshot?.let { snapshot -> TerminalCopyDialog(snapshot, fontSize, dark, onClose={copySnapshot=null}) }
                if (preview) {
                    val previewToken = remember { wireToken }
                    val previewText = remember { draft }
                    val previewTarget = activeProfile?.let { privacySettings.label(it, privacyDisplay) } ?: if (privacyDisplay) "当前服务器" else target
                    val submitWithEnter = remember { previewEnter }
                    AlertDialog(onDismissRequest = { preview = false }, title = { Text("${if (submitWithEnter) "发送到" else "粘贴到"} $previewTarget") },
                        text = { Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            Text("回复与快捷键绑定到显示的 pane；焦点变化时停止发送并保留草稿。多行需要目标程序启用 bracketed paste。")
                            Text(previewText)
                        } },
                        confirmButton = { TextButton(onClick = {
                            if (previewToken == wireToken && wireToken.isNotEmpty()) {
                                sendDraft(previewText, submitWithEnter, previewToken)
                            }
                            else status = "连接已改变；文本未发送"
                            preview = false
                        }) { Text(if (submitWithEnter) "发送并回车" else "仅粘贴，不回车") } },
                        dismissButton = { TextButton(onClick = { preview = false }) { Text("取消") } })
                }
                challenge?.let { item ->
                    ProtectSensitiveContent()
                    AlertDialog(properties=protectedDialogProperties,onDismissRequest = { challenge = null },
                        title = { Text(if (item.changed) "主机密钥已改变：禁止连接" else "首次连接：核对指纹") },
                        text = { Text("${item.endpoint}\n${item.fingerprint}\n请通过服务器管理员或可信终端核对。" + if (item.changed) "\nP0 不提供覆盖入口，核对原因后才能清除应用信任记录。" else "\n信任后请重新点击连接。") },
                        confirmButton = {
                            if (!item.changed) TextButton(onClick = {
                                pins.edit { putString(item.endpoint, item.key) }
                                challenge = null
                                status = "已保存该主机密钥，请重新连接"
                            }) { Text("指纹一致，信任") }
                        },
                        dismissButton = { TextButton(onClick = { challenge = null }) { Text("关闭") } })
                }
            }
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(result.size() + count <= limit)
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

private data class PendingDraft(val id: String, val token: String, val text: String, val enter: Boolean)
