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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

/** Staged connection progress shown by the floating connection card. */
internal data class ConnectStep(val label: UiText, val state: StepState, val millis: Long? = null)

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
    private data class WriteTarget(val connection: SshClient, val epoch: Long, val token: String,
        val binding: PaneBinding?, val path: String)
    private val inputWrites = TerminalWriteQueue<WriteTarget>(writer, ::writeInput)
    private val closer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var terminalView: TerminalView? = null
    private var client: SshClient? = null
    private val warmConnections = WarmConnectionPool<SshClient>(isReusable = { it.isReusable() },
        onIdle = { it.setIdle(true) }, onTake = { it.setIdle(false) })
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
    private var reconnectTarget by mutableStateOf<TaskConnection?>(null)
    private var activeProfile: ServerProfile? = null
    private var showConfig by mutableStateOf(true)
    private var initialHomeRefresh = true
    private var homeRefreshProfileId: String? = null
    private var reopenSettings = false
    private val pins by lazy { getSharedPreferences("host-pins", MODE_PRIVATE) }
    private var connectSteps by mutableStateOf(emptyList<ConnectStep>())
    private var connectRoute by mutableStateOf(emptyList<UiText>())
    private var connectFailure by mutableStateOf<Throwable?>(null)
    private var stepStartedAt = 0L
    private fun advanceStep(index: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        connectSteps = connectSteps.mapIndexed { i, step -> when {
            i < index && step.state != StepState.DONE -> step.copy(state = StepState.DONE, millis = if (i == index - 1) now - stepStartedAt else step.millis)
            i == index && step.state != StepState.ACTIVE -> step.copy(state = StepState.ACTIVE)
            else -> step
        } }
        if (connectSteps.getOrNull(index)?.state == StepState.ACTIVE) stepStartedAt = now
    }

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
        warmConnections.setCapacity(savedSshRetention(this).capacity)
        initializeVoiceFlavor(applicationContext)
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
        previous?.cancel()
        client = null
        if (previous != null) closer.execute { previous.close() }
        inputWrites.clear()
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

    override fun onResume() { super.onResume(); warmConnections.setActive(true); capturePaused = false; updateCapturePolicy(); DebugLog.event(DebugLog.Event.RESUME) }
    override fun onPause() {
        capturePaused = true
        updateCapturePolicy()
        super.onPause()
    }

    override fun onStop() {
        DebugLog.event(DebugLog.Event.STOP)
        super.onStop()
        disconnect(TerminalStatus(uiText(R.string.status_backgrounded)))
        warmConnections.setActive(false)
    }

    override fun onDestroy() {
        DebugLog.event(DebugLog.Event.DESTROY)
        disconnect()
        scope.cancel()
        writer.shutdownNow()
        closer.shutdown()
        warmConnections.close()
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
        try { inputWrites.offer(WriteTarget(connection, epoch, token, bound, connectedPath), bytes, onWritten) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            disconnect(TerminalStatus(uiText(R.string.status_input_overflow)))
        }
    }

    private fun writeInput(target: WriteTarget, bytes: ByteArray, onWritten: (() -> Unit)?) {
        val connection = target.connection
        if (client !== connection || target.token != wireToken) return
        try {
            if (target.binding != null) {
                if (!connection.sendToPane(target.epoch, target.binding, target.path, bytes)) {
                    DebugLog.event(DebugLog.Event.INPUT_BLOCKED)
                    inputWrites.clearMatching { it.connection === connection }
                    runOnUiThread { if (client === connection && target.token == wireToken) {
                        pastePending = false
                        status = TerminalStatus(uiText(R.string.status_input_blocked), alert = true)
                    } }
                    return
                }
            } else connection.send(target.epoch, bytes)
            runOnUiThread { if (client === connection && target.token == wireToken) onWritten?.invoke() }
        } catch (error: Exception) {
            inputWrites.clearMatching { it.connection === connection }
            DebugLog.event(DebugLog.Event.INPUT_FAILED, error=error)
            runOnUiThread { if (client === connection) disconnect(TerminalStatus(uiText(R.string.status_send_uncertain))) }
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
        reconnectTarget = TaskConnection(profile, task)
        return runCatching {
            profileRepository.rememberTask(task)
            recentTasks = profileRepository.recent()
        }.isSuccess
    }

    private fun connectTask(login: Login, path: String, session: String?, discoverOnly: Boolean,
                            profile: ServerProfile?, recent: RecentTask?, handedOff: SshClient? = null) {
        if (!discoverOnly) {
            showConfig = false
            reconnectTarget = profile?.let { TaskConnection(it, recent) }
        }
        disconnect(TerminalStatus(uiText(R.string.status_connecting)), clear = true)
        connectFailure = null
        if (!discoverOnly) {
            val hops = login.jumps.size
            connectRoute = (1..hops).map { hopText(it) } + hopText(0)
            connectSteps = (1..hops).map { ConnectStep(uiText(R.string.step_hop_auth, hopText(it)), StepState.PENDING) } +
                ConnectStep(uiText(R.string.step_hop_auth, hopText(0)), StepState.PENDING) +
                ConnectStep(if (session != null) uiText(R.string.step_enter_tmux, session) else uiText(R.string.step_open_shell), StepState.PENDING)
            stepStartedAt = android.os.SystemClock.elapsedRealtime()
            advanceStep(0)
        }
        renderedBytes = 0L
        panes = emptyList()
        challenge = null
        busy = true
        val attempt = generation
        val traceAttempt = DebugLog.nextAttempt()
        DebugLog.event(DebugLog.Event.CONNECT,attempt.toInt(),login.jumps.size)
        activeProfile = profile
        val mouseForSession = profile?.enableTmuxMouse ?: enableTmuxMouse
        val pooled = handedOff ?: profile?.let { warmConnections.take(profileRepository.connectionRevision(it)) }
        fun freshConnection() = SshClient(object : PinStore {
            override fun get(endpoint: String) = pins.getString(endpoint, null)
        }).also { fresh -> fresh.observe { DebugLog.connectionTrace(DebugLog.Owner.CONNECT, attempt.toInt(), it, traceAttempt) } }
        var connection = pooled ?: freshConnection()
        connection.observe { DebugLog.connectionTrace(DebugLog.Owner.CONNECT, attempt.toInt(), it, traceAttempt) }
        client = connection
        scope.launch {
            try {
                var binding: PaneBinding? = null
                val found = withContext(Dispatchers.IO) {
                    if (recent != null) {
                        if (profile == null || !recent.matches(profile)) throw AppError(R.string.err_profile_changed)
                        if (pins.getString(login.trustEndpoint, null) != recent.hostKey) throw AppError(R.string.err_trust_changed)
                    }
                    if (pooled != null) connection.reportReuse()
                    else connection.connect(login) { progress -> DebugLog.stage(progress,attempt.toInt()); scope.launch { if (attempt == generation && busy) {
                        status = TerminalStatus(progressText(progress))
                        if (!discoverOnly) advanceStep(if (progress.hop == 0) login.jumps.size else progress.hop - 1)
                    } } }
                    withContext(Dispatchers.Main) { if (attempt == generation) {
                        status = TerminalStatus(uiText(if (session != null || discoverOnly) R.string.status_ssh_checking_tmux else R.string.status_ssh_opening_terminal))
                        if (!discoverOnly) advanceStep(login.jumps.size + 1)
                    } }
                    if (attempt != generation) { connection.close(); throw MtmuxException(ErrorCode.CANCELLED) }
                    if (discoverOnly) {
                        connection.discoverPanes(path)
                    } else {
                        val expected = recent?.binding
                        val prepared = session?.let { selectedSession ->
                            try { connection.prepareTmux(selectedSession, expected, mouseForSession, path) }
                            catch (error: Exception) {
                                // Preparation sends no input. A stale idle transport may be retried once before attach.
                                val transportFailure = error !is CodedError || error.code in setOf(ErrorCode.NOT_CONNECTED, ErrorCode.REMOTE_TIMEOUT)
                                if (pooled == null || !transportFailure || attempt != generation) throw error
                                connection.close()
                                connection = freshConnection()
                                withContext(Dispatchers.Main) {
                                    if (attempt != generation) { connection.close(); throw MtmuxException(ErrorCode.CANCELLED) }
                                    client = connection
                                }
                                connection.connect(login) { progress -> DebugLog.stage(progress, attempt.toInt()) }
                                connection.prepareTmux(selectedSession, expected, mouseForSession, path)
                            }
                        }
                        binding = prepared?.binding
                        connection.openTerminal(binding?.let { Tmux.resume(it, path) }, cols, rows)
                        binding?.let { connection.verifyResume(it, path) }
                        listOfNotNull(prepared?.pane)
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
                    connectSteps = emptyList()
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
                            initialHomeRefresh = true; homeRefreshProfileId = profile?.id
                            showConfig = true
                        } else disconnect(TerminalStatus(uiText(R.string.status_terminal_closed)))
                    }
                }
            } catch (error: Exception) {
                val cancelled = attempt != generation || error is CancellationException || (error is CodedError && error.code == ErrorCode.CANCELLED)
                DebugLog.event(if (cancelled) DebugLog.Event.CONNECTION_CANCELLED else DebugLog.Event.CONNECTION_FAILED,attempt.toInt(),error=error)
                connection.close()
                if (attempt == generation) {
                    busy = false
                    wireToken = ""
                    pastePending = false
                    terminalView?.connection("")
                    client = null
                    if (!cancelled) {
                        connectFailure = error
                        val failedAt = (error as? ConnectionFailure)?.hop?.let { if (it == 0) login.jumps.size else it - 1 }
                            ?: connectSteps.indexOfFirst { it.state == StepState.ACTIVE }
                        connectSteps = connectSteps.mapIndexed { i, step -> when {
                            i < failedAt -> step.copy(state = StepState.DONE)
                            i == failedAt -> step.copy(state = StepState.FAILED)
                            else -> step.copy(state = StepState.PENDING)
                        } }
                    } else connectSteps = emptyList()
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

    @OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
    @Composable private fun App() {
        val profileStore = remember { ServerProfiles(this) }
        val terminalPreferences = remember { getSharedPreferences("terminal-settings", MODE_PRIVATE) }
        var appearance by remember { mutableStateOf(AppAppearance.read(terminalPreferences.getString("appearance",null))) }
        var palette by remember { mutableStateOf(AppPalette.saved(this)) }
        val dark=appearance.dark(isSystemInDarkTheme())
        val currentDark by rememberUpdatedState(dark)
        val currentPalette by rememberUpdatedState(palette)
        var quickReplyToken by remember { mutableStateOf<String?>(null) }
        var pendingReply by remember { mutableStateOf<Pair<String,String>?>(null) }
        LaunchedEffect(dark,palette) {
            val background=appColors(dark,palette).background.toArgb()
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background))
            val bars=if(dark) SystemBarStyle.dark(background) else SystemBarStyle.light(background,background)
            enableEdgeToEdge(statusBarStyle=bars,navigationBarStyle=bars)
            WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }
            terminalView?.appearance(dark,palette=palette)
        }
        var remoteTouch by remember { mutableStateOf(terminalPreferences.getBoolean("remoteTouch", true)) }
        var scrollSensitivity by remember { mutableFloatStateOf(savedScrollSpeed(terminalPreferences)) }
        val draft = remember { TerminalDraft() }
        var draftTarget by remember { mutableStateOf("") }
        val drafts = remember { mutableMapOf<String, String>() }
        LaunchedEffect(wireToken, draftScopeKey) {
            if (wireToken.isNotEmpty() && draftScopeKey != draftTarget) {
                drafts[draftTarget] = draft.text
                draft.text = drafts[draftScopeKey].orEmpty()
                draftTarget = draftScopeKey
            }
        }
        var fontSize by remember { mutableIntStateOf(terminalPreferences.getInt("fontSize",14)) }
        var editorOpen by remember { mutableStateOf(false) }
        var editorToken by remember { mutableStateOf("") }
        var editorTarget by remember { mutableStateOf("") }
        var resumeDirectInput by remember { mutableStateOf(false) }
        var pendingDraft by remember { mutableStateOf<PendingDraft?>(null) }
        var draftSequence by remember { mutableLongStateOf(0L) }
        var showTools by remember { mutableStateOf(false) }
        var showDetails by remember { mutableStateOf(false) }
        var copySnapshot by remember { mutableStateOf<TerminalSnapshot?>(null) }
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val directInput = remember { DirectTerminalInput(this) }
        val density = LocalDensity.current
        var visibleTerminalHeight by remember { mutableIntStateOf(0) }
        val sticky=remember(wireToken,draftScopeKey,replyPane?.identity,showConfig) {StickyKeys()}
        val keyToken=wireToken
        val keyBinding=replyPane
        val keysEnabled=!showConfig && keyToken.isNotEmpty() && !pastePending && !busy
        fun clearModifiers() { if(sticky.modifiers!=0) {sticky.clear(); directInput.modifiers=0; directInput.restart()} }
        LaunchedEffect(keysEnabled,showTools,quickReplyToken,copySnapshot,editorOpen) {
            if((!keysEnabled || showTools || quickReplyToken!=null || copySnapshot!=null || editorOpen) && sticky.modifiers!=0) {
                clearModifiers()
            }
        }
        val keyLifecycle=LocalLifecycleOwner.current.lifecycle
        DisposableEffect(keyLifecycle,sticky) {
            val observer=LifecycleEventObserver {_,event ->
                if(event==Lifecycle.Event.ON_STOP) {sticky.clear();directInput.retire()}
                if(event==Lifecycle.Event.ON_RESUME) directInput.restart()
            }
            keyLifecycle.addObserver(observer)
            onDispose {keyLifecycle.removeObserver(observer);sticky.clear();directInput.retire()}
        }
        fun sendKeyStroke(key: String, additional: Int=0) {
            val latched=sticky.take()
            val modifiers=latched or additional
            if(latched!=0) {directInput.modifiers=0;directInput.restart()}
            if(!keysEnabled || keyToken!=wireToken || keyBinding!=replyPane) return
            val encoded=TerminalKeyStroke.encode(key,modifiers)
            if(encoded==null) {status=TerminalStatus(uiText(R.string.keys_modifier_unsupported),alert=true);return}
            submitBytes(keyToken,encoded.toByteArray(Charsets.UTF_8),bound=keyBinding)
        }
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
        fun openTask(profile: ServerProfile, task: RecentTask?, handedOff: SshClient? = null) {
            reconnectTarget = TaskConnection(profile, task)
            showConfig = false
            try {
                connectTask(profileStore.login(profile), profile.path, task?.binding?.session, false, profile, task, handedOff)
                focusManager.clearFocus(force = true); keyboard?.hide()
            } catch (_: Exception) { handedOff?.close(); status = TerminalStatus(uiText(R.string.status_credentials_unreadable)) }
        }
        fun resumeTask(selection: TaskConnection) {
            if (busy || wireToken.isNotEmpty() || !terminalReady) return
            val profile = profileStore.all().firstOrNull { selection.matches(it) }
            if (profile == null) { status = TerminalStatus(uiText(R.string.err_profile_changed), alert = true); recentTasks = profileStore.recent(); return }
            openTask(profile, selection.task)
        }
        fun home() {
            disconnect(TerminalStatus(uiText(R.string.status_returned_home)))
            initialHomeRefresh = true; homeRefreshProfileId = activeProfile?.id
            focusManager.clearFocus(force = true); keyboard?.hide(); showConfig = true
        }
        BackHandler(enabled = !showConfig) { home() }
        // Returning home from outside the composable (remote exit) must also drop the terminal's input focus.
        LaunchedEffect(showConfig) { if (showConfig) { focusManager.clearFocus(force = true); keyboard?.hide() } }
        fun sendDraft(text: String, enter: Boolean, token: String = wireToken) {
            if (token.isEmpty() || token != wireToken || pastePending) return
            draftSequence++
            val request = PendingDraft(draftSequence.toString(), token, draftScopeKey, replyPane, text, enter)
            pendingDraft = request
            pastePending = true
            terminalView?.sendDraft(text, token, request.id, enter)
        }
        fun openEditor() {
            directInput.retire(); clearModifiers()
            editorToken=wireToken; editorTarget=draftScopeKey; editorOpen=true
        }
        fun showTerminalKeyboard() {
            if (!keysEnabled || editorOpen || showTools || quickReplyToken != null) return
            terminalView?.inputFocus(true)
            directInput.showKeyboard()
        }
        val currentFocusInput by rememberUpdatedState({showTerminalKeyboard()})
        val offerVoice: (String,String,String) -> VoiceDraftOffer = { token, target, text ->
            when {
                token!=wireToken || target!=draftScopeKey || token.isEmpty() -> VoiceDraftOffer.TARGET_CHANGED
                text.isBlank() -> VoiceDraftOffer.NO_SPEECH
                else -> {
                    draft.text=commitVoiceDraft(token,target,wireToken,draftScopeKey,draft.text,text,true).draft
                    openEditor()
                    VoiceDraftOffer.INSERTED
                }
            }
        }
        val commitVoice: (String,String,String,Boolean) -> Boolean = { token,target,text,append ->
            val commit=commitVoiceDraft(token,target,wireToken,draftScopeKey,draft.text,text,append)
            if(commit.accepted) {
                draft.text=commit.draft
                openEditor()
            }
            commit.accepted
        }
        val hideVoiceKeyboard: () -> Unit = {
            directInput.retire(); clearModifiers(); focusManager.clearFocus(force=true); keyboard?.hide()
        }
        SideEffect {
            directInput.inputEnabled=keysEnabled && !editorOpen && !showTools && quickReplyToken==null && copySnapshot==null && pendingReply==null
            directInput.modifiers=sticky.modifiers
            directInput.onInputFocus={terminalView?.inputFocus(it)}
            directInput.onComposition={ if(it.isNotEmpty()) terminalView?.revealInput(); terminalView?.composition(it) }
            directInput.onKey={key,modifiers -> terminalView?.revealInput(); sendKeyStroke(key,modifiers)}
            directInput.onText={text ->
                if(keysEnabled && keyToken==wireToken && keyBinding==replyPane) {
                    clearModifiers()
                    if(text.any { it=='\n' || it=='\r' } || text.any { it.isISOControl() && it!='\t' }) {
                        draft.text=if(draft.text.isEmpty()) text else draft.text+"\n"+text
                        openEditor()
                    } else { terminalView?.revealInput(); submitBytes(keyToken,text.toByteArray(Charsets.UTF_8),bound=keyBinding) }
                }
            }
        }
        LaunchedEffect(editorOpen,keysEnabled) {
            if(!editorOpen && keysEnabled && resumeDirectInput) {resumeDirectInput=false;showTerminalKeyboard()}
        }
        LaunchedEffect(wireToken,draftScopeKey,showConfig) {
            directInput.restart()
            if(editorOpen && (editorToken!=wireToken || editorTarget!=draftScopeKey || showConfig)) editorOpen=false
        }
        DisposableEffect(directInput) { onDispose { directInput.retire() } }
        MaterialTheme(colorScheme = appColors(dark,palette),typography=appTypography,shapes=appShapes) {
            Surface(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                // IME only clips the visible lower part. The WebView/grid keeps its full
                // keyboard-free size, so focus changes cannot resize/reflow remote tmux.
                // With adjustResize the window itself can shrink for the IME (seen on API 35 with direct
                // input). Keep the last keyboard-free height while the IME is up; rotation/width resets it.
                val imeVisible = WindowInsets.isImeVisible
                val keyboardFreeHeight = remember(maxWidth) { mutableStateOf(maxHeight) }
                val stableHeight = if (imeVisible) maxOf(keyboardFreeHeight.value, maxHeight) else maxHeight
                SideEffect { keyboardFreeHeight.value = stableHeight }
                val gridHeight = (stableHeight - 148.dp).coerceAtLeast(48.dp)
                Column(Modifier.fillMaxSize().imePadding()) {
                    val backHomeLabel = stringResource(R.string.terminal_back_home)
                    val keyboardLabel = stringResource(R.string.terminal_direct_keyboard)
                    val latestLabel = stringResource(R.string.terminal_latest)
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=2.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIconButton(UiSymbol.BACK,backHomeLabel,{home()},Modifier.testTag("terminal-home"),tone=IconTone.PLAIN,iconSize=22.dp)
                        val server = activeProfile?.let { privacySettings.label(it, privacyDisplay) } ?: target?.let { if (privacyDisplay) stringResource(R.string.terminal_current_server) else it } ?: stringResource(R.string.terminal_not_connected)
                        val pane = panes.firstOrNull { it.id == replyPane?.pane }
                        val taskName = pane?.let { if (it.windowName == it.sessionName) it.sessionName else "${it.sessionName} · ${it.windowName}" } ?: if (connectedSession != null) "tmux" else "SSH"
                        val connected = wireToken.isNotEmpty()
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable {showDetails=true}.padding(horizontal=4.dp,vertical=2.dp).testTag("terminal-title")) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                                when {
                                    busy -> CircularProgressIndicator(Modifier.size(9.dp),strokeWidth=1.5.dp)
                                    else -> TaskStateDot(when { connected -> StatusColors.ready(); connectFailure != null -> StatusColors.attention(); else -> StatusColors.unknown() })
                                }
                                Text(server,style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.SemiBold),maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            Text((if (!connected && renderedBytes > 0 && !busy) stringResource(R.string.terminal_disconnected_short) + " · " else "") + taskName,
                                style=MaterialTheme.typography.labelSmall.copy(fontFamily=FontFamily.Monospace),color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        AppIconButton(UiSymbol.KEYBOARD,keyboardLabel,{showTerminalKeyboard()},Modifier.testTag("terminal-keyboard"),enabled=keysEnabled,tone=IconTone.PLAIN,iconSize=20.dp)
                        AppIconButton(UiSymbol.EDIT,stringResource(R.string.terminal_edit_text),{openEditor()},Modifier.testTag("terminal-editor"),enabled=terminalReady && !pastePending,
                            tone=if(editorOpen) IconTone.ACCENT else IconTone.PLAIN,iconSize=20.dp)
                        AppIconButton(UiSymbol.LATEST,latestLabel,{latest()},Modifier.testTag("terminal-latest"),tone=IconTone.PLAIN,iconSize=20.dp)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clipToBounds().onSizeChanged { visibleTerminalHeight = it.height; terminalView?.viewport(it.height / density.density) }.testTag("terminal-panel")) {
                    AndroidView(factory = { context ->
                        TerminalView(context,
                            onReady = { c, r -> cols = c; rows = r; terminalReady = true; terminalView?.appearance(currentDark,force=true,palette=currentPalette); terminalView?.scrollSensitivity(scrollSensitivity); terminalView?.font(fontSize); terminalView?.remoteTouch(remoteTouch); terminalView?.viewport(visibleTerminalHeight / density.density) },
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
                                if (request != null && request.token == token && request.id == id && token == wireToken && request.target == draftScopeKey && request.binding == replyPane) {
                                    submitBytes(token, data.toByteArray(Charsets.UTF_8), bound = request.binding, onWritten = {
                                        if (draft.text == request.text) {
                                            draft.text = ""
                                            editorOpen=false
                                            resumeDirectInput=true
                                        }
                                        pendingDraft = null
                                        pastePending = false
                                        status = TerminalStatus(uiText(if (request.enter) R.string.status_sent_enter else R.string.status_pasted))
                                    })
                                }
                            },
                            onRendered = { if(renderedBytes==0L) DebugLog.event(DebugLog.Event.FIRST_RENDER,generation.toInt()); renderedBytes += it },
                            onFailure = { message -> disconnect(TerminalStatus(message)); terminalReady = false },
                            onPasteFinished = { if (it == wireToken) {pastePending = false; pendingDraft=null} },
                            onNotice = { status = it },
                            onReading = { reading = it },
                            onBinaryInput = { token, bytes -> submitBytes(token, bytes) },
                            onFocusInput = {currentFocusInput()}
                        ).also { terminalView = it }
                    }, update = { it.appearance(dark,palette=palette); it.viewport(visibleTerminalHeight / density.density) }, modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).requiredHeight(gridHeight).align(Alignment.TopStart).testTag("terminal-webview"))
                    if (renderedBytes == 0L && !(busy && wireToken.isEmpty() && connectSteps.isNotEmpty()) && !(!busy && wireToken.isEmpty() && connectFailure != null)) {
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
                    // The native IME endpoint has no visible text field or persistent draft.
                    AndroidView(factory={directInput},modifier=Modifier.size(1.dp).align(Alignment.BottomStart).testTag("terminal-ime"))
                    // Status overlays do not change terminal size or move its reading anchor.
                    val selection = reconnectTarget ?: recentTasks.firstOrNull()?.let { task ->
                        profileStore.all().firstOrNull { task.matches(it) }?.let { TaskConnection(it, task) }
                    }
                    val connectingCard = busy && wireToken.isEmpty() && connectSteps.isNotEmpty()
                    val failedCard = !busy && wireToken.isEmpty() && connectFailure != null
                    val disconnectedCard = !busy && wireToken.isEmpty() && connectFailure == null && renderedBytes > 0
                    val idleCard = !busy && wireToken.isEmpty() && connectFailure == null && renderedBytes == 0L && !status.isEmpty
                    if (connectingCard || failedCard) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha=.84f)))
                    when {
                        connectingCard || failedCard -> FloatingCard(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal=2.dp).testTag("connection-card")) {
                            Column(Modifier.padding(start=18.dp,end=18.dp,top=18.dp,bottom=12.dp)) {
                                val failure = connectFailure
                                if (failedCard) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    val reason = (failure as? ConnectionFailure)?.reason
                                    ToneBadge(when (reason) { FailureReason.AUTH, FailureReason.PRIVATE_KEY -> UiSymbol.LOCK; FailureReason.TIMEOUT -> UiSymbol.CLOCK
                                        else -> if (failure is HostKeyRejected) UiSymbol.SHIELD_ALERT else UiSymbol.ALERT_CIRCLE }, Tone.DANGER)
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(reason?.text() ?: R.string.terminal_connect_failed),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold))
                                        Text(status.text.string(),Modifier.testTag("connection-status"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text(stringResource(R.string.terminal_connecting_title),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold))
                                    Text(status.text.string(),Modifier.size(1.dp).testTag("connection-status"),color=Color.Transparent)
                                }
                                val failedIndex = connectSteps.indexOfFirst { it.state == StepState.FAILED }
                                val activeIndex = connectSteps.indexOfFirst { it.state == StepState.ACTIVE }
                                if (connectRoute.size > 1) RouteChips(connectRoute.mapIndexed { i, hop -> hop.string() to when {
                                    failedIndex >= 0 && i == failedIndex -> RouteState.FAILED
                                    failedIndex >= 0 && i < failedIndex || failedIndex < 0 && activeIndex >= 0 && i < activeIndex || activeIndex >= connectRoute.size -> RouteState.DONE
                                    i == activeIndex -> RouteState.CURRENT
                                    else -> RouteState.NORMAL
                                } }, Modifier.padding(top=10.dp,bottom=4.dp))
                                Spacer(Modifier.height(6.dp))
                                connectSteps.forEach { step -> StepRow(step.label.string(), step.state,
                                    when { step.millis != null -> "${step.millis} ms"; step.state == StepState.ACTIVE -> stringResource(R.string.step_in_progress); else -> null }) }
                                Spacer(Modifier.height(12.dp))
                                if (failedCard) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    TonalActionButton(DialogAction(stringResource(R.string.terminal_back_home_short),{home()}),Modifier.weight(1f))
                                    if (selection != null) PrimaryActionButton(DialogAction(stringResource(R.string.terminal_retry),{resumeTask(selection)},"reconnect-recent",enabled=terminalReady),Modifier.weight(1f))
                                } else TonalActionButton(DialogAction(stringResource(R.string.common_cancel),{home()},"cancel-connect"),Modifier.fillMaxWidth())
                            }
                        }
                        disconnectedCard && reading -> Surface(Modifier.align(Alignment.TopCenter).padding(top=10.dp).testTag("disconnected-pill"),shape=CircleShape,
                            color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.96f),shadowElevation=8.dp) {
                            Row(Modifier.padding(start=14.dp,end=5.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TaskStateDot(StatusColors.unknown())
                                Text(stringResource(R.string.terminal_disconnected_history),Modifier.testTag("disconnected-history"),style=MaterialTheme.typography.labelMedium)
                                Text(status.text.string(),Modifier.size(1.dp).testTag("connection-status"),color=Color.Transparent)
                                if (selection != null) Button(onClick={resumeTask(selection)},enabled=terminalReady,shape=CircleShape,contentPadding=PaddingValues(horizontal=12.dp),
                                    modifier=Modifier.height(28.dp).testTag("reconnect-recent")) { Text(stringResource(R.string.terminal_reconnect),style=MaterialTheme.typography.labelMedium) }
                            }
                        }
                        disconnectedCard || idleCard -> FloatingCard(Modifier.align(if (idleCard) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth().padding(top=if (idleCard) 10.dp else 0.dp,bottom=if (idleCard) 0.dp else 12.dp)) {
                            Row(Modifier.padding(start=14.dp,end=12.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                ToneBadge(if (disconnectedCard) UiSymbol.UNPLUG else UiSymbol.INFO, if (disconnectedCard) Tone.WARNING else Tone.NEUTRAL, 40.dp, 20.dp)
                                Column(Modifier.weight(1f)) {
                                    if (disconnectedCard) Text(stringResource(R.string.terminal_disconnected_history),Modifier.testTag("disconnected-history"),style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.SemiBold))
                                    Text(status.text.string(),Modifier.testTag("connection-status"),style=if (disconnectedCard) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                                        color=if (disconnectedCard) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,maxLines=3,overflow=TextOverflow.Ellipsis)
                                }
                                if (selection != null) Button(onClick={resumeTask(selection)},enabled=terminalReady,shape=CircleShape,contentPadding=PaddingValues(horizontal=16.dp),
                                    modifier=Modifier.height(36.dp).testTag("reconnect-recent")) {
                                    AppIcon(UiSymbol.REFRESH,modifier=Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.terminal_reconnect),style=MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        !status.isEmpty && status.alert -> FloatingCard(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top=10.dp),shape=RoundedCornerShape(16.dp)) {
                            Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                AppIcon(UiSymbol.ALERT,modifier=Modifier.size(18.dp),tint=StatusColors.running())
                                Text(status.text.string(),Modifier.weight(1f).testTag("connection-status"),style=MaterialTheme.typography.bodySmall)
                                AppIconButton(UiSymbol.CLOSE,stringResource(R.string.common_close),{status=TerminalStatus.NONE},tone=IconTone.PLAIN,size=28.dp,iconSize=16.dp)
                            }
                        }
                    }
                    }
                    Surface(Modifier.fillMaxWidth().height(100.dp),color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)) {
                        Column(Modifier.padding(horizontal=8.dp)) {
                            TerminalKeys(enabled=keysEnabled,modifiers=sticky.modifiers,onModifier={bit ->
                                directInput.finishComposition()
                                sticky.toggle(bit)
                                directInput.modifiers=sticky.modifiers
                                showTerminalKeyboard()
                                directInput.restart()
                            },onKey={key ->
                                directInput.finishComposition()
                                if(key=="Ctrl-C") sendKeyStroke("c",TerminalKeyStroke.CTRL) else sendKeyStroke(key)
                            })
                            TerminalActions(keysEnabled,
                                onTools={directInput.retire();clearModifiers();showTools=true},
                                onReplies={directInput.retire();clearModifiers();quickReplyToken=wireToken}) {
                                if(!editorOpen) VoiceInputControl(modifier=Modifier, enabled=keysEnabled,
                                    connectionToken=wireToken,targetKey=draftScopeKey,onHideKeyboard=hideVoiceKeyboard,
                                    onOfferDraft=offerVoice,onCommitDraft=commitVoice,onStatus={status=it})
                            }
                        }
                    }
                }
                }
                if (showConfig) {
                    ServerHome(warmConnections=warmConnections, openSettings=reopenSettings, privacyDisplay=privacyDisplay, onPrivacyDisplay=::changePrivacyDisplay, refreshOnEntry = initialHomeRefresh, refreshProfileOnEntry = homeRefreshProfileId, onEntryHandled = { initialHomeRefresh = false; homeRefreshProfileId = null; reopenSettings = false }, onOpen = { profile, task, handedOff ->
                        openTask(profile, task, handedOff)
                    }, appearance=appearance,onAppearance={appearance=it;terminalPreferences.edit {putString("appearance",it.name)}},
                        palette=palette,onPalette={palette=it;terminalPreferences.edit {putString("palette",it.name)}},
                        onPreferences = { font, speed, touch ->
                        fontSize=font;scrollSensitivity=speed;remoteTouch=touch
                        terminalView?.font(font);terminalView?.scrollSensitivity(speed);terminalView?.remoteTouch(touch)
                    })
                }
                if (showDetails) ProtectSensitiveContent()
                if (showDetails) AppDialog(onDismiss={showDetails=false},properties=protectedDialogProperties,title=stringResource(R.string.terminal_details_title),
                    icon=UiSymbol.SERVER,tone=if(wireToken.isNotEmpty()) Tone.SUCCESS else Tone.NEUTRAL,dismiss=DialogAction(stringResource(R.string.common_close),{showDetails=false})) {
                    InsetPanel {
                        Text(activeProfile?.name.orEmpty(),style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.SemiBold))
                        Text("${target ?: stringResource(R.string.terminal_not_connected)} · ${targetSession ?: stringResource(R.string.terminal_plain_shell)}",style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),color=MaterialTheme.colorScheme.onSurfaceVariant)
                        panes.firstOrNull { it.id == replyPane?.pane }?.let { Text("${it.sessionName} / ${it.windowName}",style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(status.text.string(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showTools) {
                    AppSheet(onDismiss = { showTools = false }, floating = false) {
                        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal=8.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.tools_title),Modifier.padding(start=4.dp,top=4.dp),style=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.Bold))
                    replyPane?.let { bound ->
                        InsetPanel {
                            Text(stringResource(R.string.tools_reply_target, bound.pane), modifier = Modifier.testTag("reply-target"),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
                            TextButton(enabled = draft.text.isEmpty() && !pastePending && !busy, onClick = {
                                val connection = client ?: return@TextButton
                                val token = wireToken
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { connection.prepareTmux(bound.session, null, false, connectedPath) } }
                                    if (token == wireToken && client === connection) {
                                        busy = false
                                        result.fold({ prepared ->
                                            val next = prepared.binding
                                            panes = listOf(prepared.pane)
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

                            FilledTonalButton(onClick={showTools=false;openEditor()},enabled=keysEnabled,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().height(48.dp).testTag("open-text-editor")){AppIcon(UiSymbol.EDIT,modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(stringResource(R.string.terminal_edit_text))}
                            InsetPanel {

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = remoteTouch, onCheckedChange = { remoteTouch = it; terminalView?.remoteTouch(it); terminalPreferences.edit { putBoolean("remoteTouch", it) } })
                                Text(stringResource(if (remoteTouch) R.string.tools_touch_remote else R.string.tools_touch_local))
                            }
                            Text(stringResource(R.string.tools_touch_help), style = MaterialTheme.typography.bodySmall)
                            ScrollSpeedControl(scrollSensitivity, { value ->
                                scrollSensitivity=value
                                terminalView?.scrollSensitivity(value)
                                terminalPreferences.edit { putFloat("scrollSensitivity",value) }
                            },"scroll-speed")
                            Row {
                                TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(8); terminalView?.font(fontSize); terminalPreferences.edit { putInt("fontSize", fontSize) } }) { Text(stringResource(R.string.tools_font_smaller)) }
                                TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28); terminalView?.font(fontSize); terminalPreferences.edit { putInt("fontSize", fontSize) } }) { Text(stringResource(R.string.tools_font_larger)) }
                            }
                            }
                            FilledTonalButton(onClick = {
                                showTools = false; keyboard?.hide()
                                terminalView?.snapshot { snapshot -> copySnapshot = snapshot }
                            }, shape=RoundedCornerShape(14.dp), modifier=Modifier.fillMaxWidth().height(48.dp).testTag("select-terminal-text")) { AppIcon(UiSymbol.COPY,modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(stringResource(R.string.tools_select_copy)) }
                            
                            TextButton(onClick = { draft.text = ""; showTools = false }) { Text(stringResource(R.string.tools_clear_draft),color=MaterialTheme.colorScheme.error) }
                            Text(stringResource(R.string.tools_terminal_info, cols, rows, renderedBytes), style = MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                            TonalActionButton(DialogAction(stringResource(R.string.common_close),{ showTools = false }),Modifier.fillMaxWidth().padding(top=8.dp))
                        }
                    }
                }
                quickReplyToken?.let { openedToken -> QuickReplySheet(onDismiss={quickReplyToken=null},onPick={text ->
                    quickReplyToken=null
                    if(openedToken!=wireToken || wireToken.isEmpty() || pastePending) status=TerminalStatus(uiText(R.string.status_quick_reply_stale), alert = true)
                    else if(draft.text.isEmpty()) {draft.text=text;openEditor()}
                    else pendingReply=openedToken to text
                }) }
                pendingReply?.let { (token,text) ->
                    fun stale() { status=TerminalStatus(uiText(R.string.status_quick_reply_stale), alert = true) }
                    val usable = token==wireToken && token.isNotEmpty() && !pastePending
                    AppDialog(onDismiss={pendingReply=null},title=stringResource(R.string.quick_reply_keep_title),body=stringResource(R.string.quick_reply_keep_body),icon=UiSymbol.REPLY,
                        confirm=DialogAction(stringResource(R.string.quick_reply_append),{if(usable) {draft.text=if(draft.text.isEmpty()) text else draft.text+"\n"+text;openEditor()} else stale();pendingReply=null},"append-quick-reply"),
                        dismiss=DialogAction(stringResource(R.string.quick_reply_replace),{if(usable) {draft.text=text;openEditor()} else stale();pendingReply=null},"replace-quick-reply"),
                        extra=DialogAction(stringResource(R.string.common_cancel),{pendingReply=null}))
                }
                copySnapshot?.let { snapshot -> TerminalCopyDialog(snapshot, fontSize, dark, onClose={copySnapshot=null}) }
                if (editorOpen) {
                    val valid=editorToken==wireToken && editorTarget==draftScopeKey && wireToken.isNotEmpty()
                    AppSheet(onDismiss={if(!pastePending) editorOpen=false},floating=false,dismissible=!pastePending) {
                        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=8.dp).padding(bottom=8.dp),
                            verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.terminal_edit_text),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold))
                                val targetName=panes.firstOrNull { it.id == replyPane?.pane }?.sessionName ?: if(connectedSession!=null) "tmux" else "SSH"
                                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primary.copy(alpha=.14f)) {
                                    Text("→ $targetName",Modifier.padding(horizontal=10.dp,vertical=3.dp),style=MaterialTheme.typography.labelMedium.copy(fontFamily=FontFamily.Monospace),color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                                AppIconButton(UiSymbol.CLOSE,stringResource(R.string.common_close),{editorOpen=false},Modifier.testTag("close-text-editor"),enabled=!pastePending,tone=IconTone.PLAIN,size=30.dp,iconSize=16.dp)
                            }
                            OutlinedTextField(value=draft.text,onValueChange={draft.text=it},enabled=!pastePending,shape=RoundedCornerShape(14.dp),colors=appFieldColors(),
                                textStyle=MaterialTheme.typography.bodyLarge,
                                modifier=Modifier.fillMaxWidth().heightIn(max=260.dp).testTag("command-input"),
                                minLines=3,maxLines=8)
                            Text(stringResource(R.string.terminal_editor_help),Modifier.padding(horizontal=4.dp),style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                VoiceInputControl(modifier=Modifier,enabled=valid && !pastePending,
                                    connectionToken=wireToken,targetKey=draftScopeKey,onHideKeyboard=hideVoiceKeyboard,
                                    onOfferDraft=offerVoice,onCommitDraft=commitVoice,onStatus={status=it})
                                Spacer(Modifier.weight(1f))
                                Button(onClick={sendDraft(draft.text,false,editorToken)},enabled=valid && draft.text.isNotEmpty() && !pastePending,
                                    shape=RoundedCornerShape(14.dp),modifier=Modifier.height(46.dp).testTag("insert-terminal")) {
                                    Text(stringResource(if(pastePending) R.string.terminal_sending else R.string.terminal_insert_text))
                                }
                            }
                        }
                    }
                }
                challenge?.let { item ->
                    ProtectSensitiveContent()
                    HostKeyDialog(item,onDismiss={challenge=null},onTrust={
                        pins.edit { putString(item.endpoint, item.key) }
                        challenge = null
                        status = TerminalStatus(uiText(R.string.status_host_key_saved))
                    },subtitle=stringResource(R.string.host_key_verify_hint)+" "+stringResource(if (item.changed) R.string.host_key_changed_hint else R.string.host_key_first_hint))
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

private data class PendingDraft(val id: String, val token: String, val target: String, val binding: PaneBinding?, val text: String, val enter: Boolean)
