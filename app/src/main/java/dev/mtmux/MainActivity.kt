package dev.mtmux

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.ui.res.stringResource
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

class MainActivity : AppCompatActivity() {
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
    private var status by mutableStateOf(TerminalStatus(uiText(R.string.status_initial)))
    private var panes by mutableStateOf(emptyList<Pane>())
    private var challenge by mutableStateOf<HostKeyChallenge?>(null)
    private var terminalReady by mutableStateOf(false)
    /** user@host:port of the live/last connection; null when not connected. Never translated. */
    private var target by mutableStateOf<String?>(null)
    private var targetSession by mutableStateOf<String?>(null)
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
    private var reopenSettings = false
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
        if (savedInstanceState != null && AppLanguage.consumeRecreation()) { initialHomeRefresh = false; reopenSettings = true }
        setContent { App() }
    }

    private fun disconnect(message: TerminalStatus = TerminalStatus(uiText(R.string.status_disconnected)), clear: Boolean = false) {
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
            target = null
            targetSession = null
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
        disconnect(TerminalStatus(uiText(R.string.status_backgrounded)))
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
            status = TerminalStatus(uiText(R.string.status_input_too_long), alert = true)
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
                                status = TerminalStatus(uiText(R.string.status_input_blocked), alert = true)
                            } }
                            return@execute
                        }
                    } else connection.send(epoch, bytes)
                    runOnUiThread { if (client === connection && token == wireToken) onWritten?.invoke() }
                }
                catch (error: Exception) { DebugLog.event(DebugLog.Event.INPUT_FAILED,error=error); runOnUiThread {
                    if (client === connection) disconnect(TerminalStatus(uiText(R.string.status_send_uncertain)))
                } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            disconnect(TerminalStatus(uiText(R.string.status_input_overflow)))
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
        disconnect(TerminalStatus(uiText(R.string.status_connecting)), clear = true)
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
                        if (profile == null || !recent.matches(profile)) throw AppError(R.string.err_profile_changed)
                        if (pins.getString(login.trustEndpoint, null) != recent.hostKey) throw AppError(R.string.err_trust_changed)
                    }
                    connection.connect(login) { progress -> DebugLog.stage(progress,attempt.toInt()); scope.launch { if (attempt == generation && busy) status = TerminalStatus(progressText(progress)) } }
                    withContext(Dispatchers.Main) { if (attempt == generation) status = TerminalStatus(uiText(if (session != null || discoverOnly) R.string.status_ssh_checking_tmux else R.string.status_ssh_opening_terminal)) }
                    if (attempt != generation) { connection.close(); throw MtmuxException(ErrorCode.CANCELLED) }
                    if (discoverOnly) {
                        connection.discoverPanes(path)
                    } else {
                        val expected = recent?.binding
                        expected?.let { connection.verifyResume(it, path) }
                        val metadata = if (session != null) connection.discoverPanes(path).filter { it.session == session } else emptyList()
                        if (session != null && metadata.isEmpty()) throw AppError(R.string.err_session_gone)
                        if (session != null && mouseForSession) {
                            if (connection.exec(Tmux.enableMouse(session, path)).status != 0) throw AppError(R.string.err_enable_mouse)
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
                    status = TerminalStatus(if (found.isEmpty()) uiText(R.string.status_no_panes) else UiText.Plural(R.plurals.status_found_panes, found.size))
                    connection.close()
                    client = null
                } else {
                    panes = found
                    connectedPath = path
                    connectedSession = session
                    replyPane = binding
                    draftScopeKey = "${login.trustEndpoint}/${login.user}/${binding?.identity ?: "shell"}"
                    target = "${login.user}@${login.host}:${login.port}"
                    targetSession = found.firstOrNull()?.sessionName
                    wireToken = "$attempt:${connection.token()}"
                    terminalView?.resetScreen()
                    terminalView?.connection(wireToken)
                    status = TerminalStatus(uiText(if (session == null) R.string.status_connected_shell else R.string.status_connected_tmux))
                    if (!rememberTask(profile, binding, found)) status = status.withAlert(uiText(R.string.status_recent_not_saved))
                    val renderToken = wireToken
                    val exitStatus = withContext(Dispatchers.IO) { connection.pump { terminalView?.render(it, renderToken) ?: throw MtmuxException(ErrorCode.TERMINAL_CLOSED) } }
                    if (attempt == generation) {
                        if (exitStatus >= 0) {
                            // The remote program ended on its own (`exit`, tmux detach): nothing left to
                            // read, so go back to the server list. Drops without an exit status keep history.
                            DebugLog.event(DebugLog.Event.REMOTE_EXIT,attempt.toInt(),exitStatus.coerceIn(0,255))
                            disconnect(TerminalStatus(uiText(R.string.status_remote_exited)), clear = true)
                            showConfig = true
                        } else disconnect(TerminalStatus(uiText(R.string.status_terminal_closed)))
                    }
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
                        status = TerminalStatus(uiText(R.string.status_host_key_blocked))
                    } else {
                        // Never display arbitrary SSH exception text that might contain private paths/data.
                        status = TerminalStatus(errorText(error))
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
                    check(response.status == 0 && response.output.trim() == "MTMUX_LATEST")
                } }
                if (token == wireToken) result.fold({ terminalView?.latest(); reading = false }, { status = TerminalStatus(uiText(R.string.status_latest_failed), alert = true) })
            }
        }
        fun resumeTask(task: RecentTask) {
            if (busy || wireToken.isNotEmpty() || !terminalReady) return
            val profile = profileStore.all().firstOrNull { task.matches(it) }
            if (profile == null) { status = TerminalStatus(uiText(R.string.err_profile_changed), alert = true); recentTasks = profileStore.recent(); return }
            try {
                connectTask(profileStore.login(profile), profile.path, task.binding?.session, false, profile, task)
                focusManager.clearFocus(force = true); keyboard?.hide()
            } catch (_: Exception) { status = TerminalStatus(uiText(R.string.status_recent_unreadable), alert = true) }
        }
        fun home() {
            disconnect(TerminalStatus(uiText(R.string.status_returned_home)))
            focusManager.clearFocus(force = true); keyboard?.hide(); showConfig = true
        }
        BackHandler(enabled = !showConfig) { home() }
        // Returning home from outside the composable (remote exit) must also drop the terminal's input focus.
        LaunchedEffect(showConfig) { if (showConfig) { focusManager.clearFocus(force = true); keyboard?.hide() } }
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
                    val backHomeLabel = stringResource(R.string.terminal_back_home)
                    val latestLabel = stringResource(R.string.terminal_latest)
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { home() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(40.dp).semantics { contentDescription = backHomeLabel }.testTag("terminal-home")) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                        val server = activeProfile?.let { privacySettings.label(it, privacyDisplay) } ?: target?.let { if (privacyDisplay) stringResource(R.string.terminal_current_server) else it } ?: stringResource(R.string.terminal_not_connected)
                        val pane = panes.firstOrNull { it.id == replyPane?.pane }
                        val taskName = pane?.let { "${it.sessionName} / ${it.windowName}" } ?: if (connectedSession != null) "tmux" else "SSH"
                        Text("$server · $taskName", Modifier.weight(1f).clickable { showDetails = true }.testTag("terminal-title"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { latest() }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp).semantics { contentDescription = latestLabel }.testTag("terminal-latest")) { Text("⇣", style = MaterialTheme.typography.headlineSmall) }
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
                                        status = TerminalStatus(uiText(if (request.enter) R.string.status_sent_enter else R.string.status_pasted))
                                    })
                                }
                            },
                            onRendered = { if(renderedBytes==0L) DebugLog.event(DebugLog.Event.FIRST_RENDER,generation.toInt()); renderedBytes += it },
                            onFailure = { message -> disconnect(TerminalStatus(message)); terminalReady = false },
                            onPasteFinished = { if (it == wireToken) pastePending = false },
                            onNotice = { status = it },
                            onReading = { reading = it },
                            onBinaryInput = { token, bytes -> submitBytes(token, bytes) }
                        ).also { terminalView = it }
                    }, update = { it.appearance(dark); it.viewport(visibleTerminalHeight / density.density) }, modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).requiredHeight(gridHeight).align(Alignment.TopStart).testTag("terminal-webview"))
                    if (renderedBytes == 0L) {
                        Text(
                            stringResource(when {
                                !terminalReady -> R.string.terminal_placeholder_loading
                                wireToken.isNotEmpty() -> R.string.terminal_placeholder_waiting
                                busy -> R.string.terminal_placeholder_connecting
                                else -> R.string.terminal_placeholder_disconnected
                            }),
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // Status overlays do not change terminal size or move its reading anchor.
                    if (!status.isEmpty && (wireToken.isEmpty() || status.alert)) {
                        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color=MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(8.dp)) {
                                Text(status.text.string(), modifier=Modifier.testTag("connection-status"), style=MaterialTheme.typography.bodySmall)
                                if(wireToken.isEmpty() && renderedBytes>0) Text(stringResource(R.string.terminal_disconnected_history),modifier=Modifier.testTag("disconnected-history"),color=MaterialTheme.colorScheme.error)
                                if(wireToken.isEmpty() && !busy && recentTasks.isNotEmpty()) Row {
                                    TextButton(onClick={resumeTask(recentTasks.first())},enabled=terminalReady,modifier=Modifier.testTag("reconnect-recent")){Text(stringResource(R.string.terminal_reconnect_recent))}
                                    TextButton(onClick={home()}){Text(stringResource(R.string.terminal_reselect_task))}
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
                        TextField(draft,{draft=it},placeholder={Text(stringResource(R.string.terminal_input_placeholder))},maxLines=2,shape=RoundedCornerShape(10.dp),
                            colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
                            modifier=Modifier.fillMaxWidth().height(64.dp).focusRequester(draftFocus).testTag("command-input"))
                        Row(Modifier.fillMaxWidth().height(52.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick={showTools=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(0.7f)){Text(stringResource(R.string.terminal_tools),maxLines=1)}
                            TextButton(onClick={focusManager.clearFocus();keyboard?.hide()},enabled=imeVisible,contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(1.1f).testTag("hide-keyboard")){Text(stringResource(R.string.terminal_hide_keyboard),maxLines=1)}
                            TextButton(onClick={quickReplyToken=wireToken},enabled=wireToken.isNotEmpty() && !pastePending,contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(1.1f).testTag("quick-replies")){Text(stringResource(R.string.terminal_quick_replies),maxLines=1)}
                            Button(onClick={requestDraft(true)},enabled=wireToken.isNotEmpty() && !pastePending && draft.isNotEmpty(),shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.weight(1.2f).testTag("send-enter")){Text(stringResource(if(pastePending) R.string.terminal_sending else R.string.terminal_send_enter),maxLines=1)}
                        }
                    }
                }
                }
                if (showConfig) {
                    ServerHome(openSettings=reopenSettings, privacyDisplay=privacyDisplay, onPrivacyDisplay=::changePrivacyDisplay, refreshOnEntry = initialHomeRefresh, onEntryHandled = { initialHomeRefresh = false; reopenSettings = false }, onOpen = { profile, task ->
                        try {
                            connectTask(profileStore.login(profile),profile.path,task?.binding?.session,false,profile,task)
                        } catch (_: Exception) { showConfig = false; status = TerminalStatus(uiText(R.string.status_credentials_unreadable)) }
                    }, appearance=appearance,onAppearance={appearance=it;terminalPreferences.edit {putString("appearance",it.name)}},
                        onPreferences = { font, speed, touch ->
                        fontSize=font;scrollSensitivity=speed;remoteTouch=touch
                        terminalView?.font(font);terminalView?.scrollSensitivity(speed);terminalView?.remoteTouch(touch)
                    })
                }
                if (showDetails) ProtectSensitiveContent()
                if (showDetails) AlertDialog(properties=protectedDialogProperties,onDismissRequest={showDetails=false},title={Text(stringResource(R.string.terminal_details_title))},text={Text("${activeProfile?.name.orEmpty()}\n${target ?: stringResource(R.string.terminal_not_connected)} · ${targetSession ?: stringResource(R.string.terminal_plain_shell)}\n${panes.firstOrNull { it.id == replyPane?.pane }?.let { "${it.sessionName} / ${it.windowName}" }.orEmpty()}\n${status.text.string()}")},confirmButton={TextButton(onClick={showDetails=false}){Text(stringResource(R.string.common_close))}})
                if (showTools) {
                    ModalBottomSheet(onDismissRequest = { showTools = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal=16.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Text(stringResource(R.string.tools_title),style=MaterialTheme.typography.titleLarge)
                    replyPane?.let { bound ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.tools_reply_target, bound.pane), modifier = Modifier.weight(1f).testTag("reply-target"))
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
                                            status = TerminalStatus(uiText(R.string.status_reply_target_confirmed, next.pane))
                                            if (!rememberTask(activeProfile, next, panes)) status = status.withAlert(uiText(R.string.status_recent_not_saved))
                                        }, { status = TerminalStatus(uiText(R.string.status_bind_failed), alert = true) })
                                    }
                                }
                            }) { Text(stringResource(R.string.tools_use_current_pane)) }
                        }
                    }

                            TextButton(onClick={showTools=false;requestDraft(false)},enabled=wireToken.isNotEmpty() && draft.isNotEmpty()){Text(stringResource(R.string.tools_paste_only))}

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = remoteTouch, onCheckedChange = { remoteTouch = it; terminalView?.remoteTouch(it); terminalPreferences.edit { putBoolean("remoteTouch", it) } })
                                Text(stringResource(if (remoteTouch) R.string.tools_touch_remote else R.string.tools_touch_local))
                            }
                            Text(stringResource(R.string.tools_touch_help), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.scroll_speed, "%.1f".format(java.util.Locale.ROOT, scrollSensitivity)))
                            Slider(value = scrollSensitivity, onValueChange = {
                                scrollSensitivity = it; terminalView?.scrollSensitivity(it)
                            }, onValueChangeFinished = { terminalPreferences.edit { putFloat("scrollSensitivity", scrollSensitivity) } },
                                valueRange = 0.5f..2f, steps = 2, modifier = Modifier.testTag("scroll-speed"))
                            Text(stringResource(R.string.tools_scroll_help), style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(8); terminalView?.font(fontSize); terminalPreferences.edit { putInt("fontSize", fontSize) } }) { Text(stringResource(R.string.tools_font_smaller)) }
                                TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28); terminalView?.font(fontSize); terminalPreferences.edit { putInt("fontSize", fontSize) } }) { Text(stringResource(R.string.tools_font_larger)) }
                            }
                            TextButton(onClick = {
                                showTools = false; keyboard?.hide()
                                terminalView?.snapshot { snapshot -> copySnapshot = snapshot }
                            }, modifier=Modifier.testTag("select-terminal-text")) { Text(stringResource(R.string.tools_select_copy)) }
                            
                            TextButton(onClick = { draft = ""; showTools = false }) { Text(stringResource(R.string.tools_clear_draft)) }
                            Text(stringResource(R.string.tools_terminal_info, cols, rows, renderedBytes), style = MaterialTheme.typography.bodySmall)
                        }
                            TextButton(onClick = { showTools = false }) { Text(stringResource(R.string.common_close)) }
                        }
                    }
                }
                quickReplyToken?.let { openedToken -> QuickReplySheet(onDismiss={quickReplyToken=null},onPick={text ->
                    quickReplyToken=null
                    if(openedToken!=wireToken || wireToken.isEmpty() || pastePending) status=TerminalStatus(uiText(R.string.status_quick_reply_stale), alert = true)
                    else if(draft.isEmpty()) {draft=text}
                    else pendingReply=openedToken to text
                }) }
                pendingReply?.let { (token,text) -> AlertDialog(onDismissRequest={pendingReply=null},title={Text(stringResource(R.string.quick_reply_keep_title))},text={Text(stringResource(R.string.quick_reply_keep_body))},confirmButton={
                    TextButton(onClick={if(token==wireToken && token.isNotEmpty() && !pastePending) {draft=if(draft.isEmpty()) text else draft+"\n"+text} else status=TerminalStatus(uiText(R.string.status_quick_reply_stale), alert = true);pendingReply=null},modifier=Modifier.testTag("append-quick-reply")){Text(stringResource(R.string.quick_reply_append))}
                },dismissButton={Row {
                    TextButton(onClick={if(token==wireToken && token.isNotEmpty() && !pastePending) {draft=text} else status=TerminalStatus(uiText(R.string.status_quick_reply_stale), alert = true);pendingReply=null},modifier=Modifier.testTag("replace-quick-reply")){Text(stringResource(R.string.quick_reply_replace))}
                    TextButton(onClick={pendingReply=null}){Text(stringResource(R.string.common_cancel))}
                }}) }
                copySnapshot?.let { snapshot -> TerminalCopyDialog(snapshot, fontSize, dark, onClose={copySnapshot=null}) }
                if (preview) {
                    val previewToken = remember { wireToken }
                    val previewText = remember { draft }
                    val previewTarget = activeProfile?.let { privacySettings.label(it, privacyDisplay) } ?: target?.let { if (privacyDisplay) stringResource(R.string.terminal_current_server) else it } ?: stringResource(R.string.terminal_not_connected)
                    val submitWithEnter = remember { previewEnter }
                    AlertDialog(onDismissRequest = { preview = false }, title = { Text(stringResource(if (submitWithEnter) R.string.preview_send_to else R.string.preview_paste_to, previewTarget)) },
                        text = { Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            Text(stringResource(R.string.preview_body))
                            Text(previewText)
                        } },
                        confirmButton = { TextButton(onClick = {
                            if (previewToken == wireToken && wireToken.isNotEmpty()) {
                                sendDraft(previewText, submitWithEnter, previewToken)
                            }
                            else status = TerminalStatus(uiText(R.string.status_text_stale), alert = true)
                            preview = false
                        }) { Text(stringResource(if (submitWithEnter) R.string.preview_send_enter else R.string.preview_paste_only)) } },
                        dismissButton = { TextButton(onClick = { preview = false }) { Text(stringResource(R.string.common_cancel)) } })
                }
                challenge?.let { item ->
                    ProtectSensitiveContent()
                    AlertDialog(properties=protectedDialogProperties,onDismissRequest = { challenge = null },
                        title = { Text(stringResource(if (item.changed) R.string.host_key_changed_title else R.string.host_key_first_title)) },
                        text = { Text("${item.endpoint}\n${item.fingerprint}\n" + stringResource(R.string.host_key_verify_hint) + "\n" + stringResource(if (item.changed) R.string.host_key_changed_hint else R.string.host_key_first_hint)) },
                        confirmButton = {
                            if (!item.changed) TextButton(onClick = {
                                pins.edit { putString(item.endpoint, item.key) }
                                challenge = null
                                status = TerminalStatus(uiText(R.string.status_host_key_saved))
                            }) { Text(stringResource(R.string.host_key_trust)) }
                        },
                        dismissButton = { TextButton(onClick = { challenge = null }) { Text(stringResource(R.string.common_close)) } })
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

/** Alert statuses stay visible over a live terminal; others only while disconnected. */
data class TerminalStatus(val text: UiText, val alert: Boolean = false) {
    val isEmpty: Boolean get() = text == EMPTY_TEXT
    fun withAlert(extra: UiText) = TerminalStatus(text + extra, alert = true)
    companion object {
        private val EMPTY_TEXT = UiText.Raw("")
        val NONE = TerminalStatus(EMPTY_TEXT)
    }
}

private data class PendingDraft(val id: String, val token: String, val text: String, val enter: Boolean)
