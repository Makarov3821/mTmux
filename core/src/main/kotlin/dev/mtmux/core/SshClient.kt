package dev.mtmux.core

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.InputStream

/** Credentials are memory-only in P0. Never include this object in logs. */
class Login(
    val host: String, val port: Int, val user: String,
    val password: String, val privateKey: ByteArray? = null, val passphrase: String = "",
    val jumps: List<Login> = emptyList()
) {
    init {
        requireValid(host.isNotBlank() && host.none { it.isWhitespace() } && port in 1..65535 && user.isNotBlank(), ErrorCode.INVALID_LOGIN)
    }
    val endpoint: String get() = "[$host]:$port"
    val trustEndpoint: String get() = if (jumps.isEmpty()) endpoint else endpoint + "|via:" +
        java.security.MessageDigest.getInstance("SHA-256").digest(jumps.joinToString("\n") { "${it.user}@${it.endpoint}" }.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }

}

data class ExecResult(val status: Int, val output: String, val error: String)

class SshClient(private val pins: PinStore, private val confirmUnknownHost: ((HostKeyChallenge) -> Boolean)? = null) : AutoCloseable {
    @Volatile private var session: Session? = null
    @Volatile private var terminal: com.jcraft.jsch.Channel? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private val epoch = ConnectionEpoch()
    private val writeLock = Any()
    private val chain = java.util.concurrent.CopyOnWriteArrayList<Session>()
    private val stateLock = Any()
    @Volatile private var cancelled = false
    private var started = false
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
    private val lookups = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.Future<*>>()
    private val commands = java.util.concurrent.CopyOnWriteArrayList<com.jcraft.jsch.Channel>()
    private val trace = TraceSource()

    fun observe(observer: (ConnectionTrace) -> Unit) { trace.observer = observer }
    fun isReusable() = !cancelled && session?.isConnected == true && terminal == null && commands.isEmpty()
    fun reportReuse() { checkActive(); trace.instant(ConnectionPhase.SSH_REUSED) }
    /** No periodic application heartbeat while a bounded idle chain is parked. */
    fun setIdle(idle: Boolean) {
        checkActive()
        chain.forEach { it.setServerAliveInterval(if (idle) 0 else 15_000) }
    }
    private fun checkActive() = ensure(!cancelled, ErrorCode.CANCELLED)
    private fun ownSocket(socket: java.net.Socket) = synchronized(stateLock) {
        if (cancelled) { socket.close(); fail(ErrorCode.CANCELLED) }
        sockets.add(socket)
        Unit
    }
    private fun ownLookup(lookup: java.util.concurrent.Future<*>) = synchronized(stateLock) {
        if (cancelled) { lookup.cancel(true); fail(ErrorCode.CANCELLED) }
        lookups.add(lookup)
        Unit
    }
    private fun ownChannel(channel: com.jcraft.jsch.Channel) = synchronized(stateLock) {
        checkActive(); commands.add(channel); Unit
    }
    internal fun <T> measured(phase: ConnectionPhase, hop: Int = 0, block: () -> T): T {
        val span = trace.span(phase, hop)
        try { return trace.within(span) { block().also { value ->
            if (value is ExecResult) span.end(if (value.status == 0) TraceResult.OK else TraceResult.FAILED,
                bytes = value.output.toByteArray(Charsets.UTF_8).size + value.error.toByteArray(Charsets.UTF_8).size, exitStatus = value.status)
            else span.end()
        } } }
        catch (error: Exception) {
            span.end(if (cancelled || (error is CodedError && error.code == ErrorCode.CANCELLED)) TraceResult.CANCELLED else TraceResult.FAILED, error = error)
            if (cancelled) throw MtmuxException(ErrorCode.CANCELLED, error)
            throw error
        }
    }

    fun connect(login: Login, onProgress: (ConnectProgress) -> Unit = {}) {
        synchronized(stateLock) {
            checkActive()
            ensure(!started, ErrorCode.CONNECTION_CHANGED)
            started = true
        }
        val hops = login.jumps + login
        requireValid(login.jumps.none { it.jumps.isNotEmpty() }, ErrorCode.INVALID_JUMP_CHAIN)
        val total = trace.span(ConnectionPhase.SSH_CONNECT)
        var previous: Session? = null
        try {
            hops.forEachIndexed { index, hop ->
                checkActive()
                val scoped = Login(hop.host, hop.port, hop.user, hop.password, hop.privateKey, hop.passphrase, hops.take(index))
                val jsch = JSch()
                val verifier = PinnedHostKeys(scoped.trustEndpoint, pins, confirmUnknownHost)
                jsch.hostKeyRepository = verifier
                val hopNumber = if (index < hops.lastIndex) index + 1 else 0
                try {
                    onProgress(ConnectProgress(hopNumber, ConnectProgress.Stage.PREPARING))
                    measured(ConnectionPhase.KEY_LOAD, hopNumber) {
                        hop.privateKey?.let { jsch.addIdentity("memory-key", it, null, hop.passphrase.toByteArray()) }
                    }
                    val forwarded = previous?.let { relay -> measured(ConnectionPhase.JUMP_FORWARD, hopNumber) {
                        relay.setPortForwardingL("127.0.0.1", 0, hop.host, hop.port)
                    } }
                    val candidate = jsch.getSession(hop.user, if (forwarded == null) hop.host else "127.0.0.1", forwarded ?: hop.port)
                    synchronized(stateLock) { checkActive(); chain.add(candidate) }
                    candidate.setSocketFactory(ConnectingSocketFactory(trace, hopNumber, ::checkActive, ::ownSocket,
                        ::ownLookup, { lookups.remove(it) }))
                    val handshake = HandshakeTrace(trace, hopNumber)
                    candidate.setLogger(handshake)
                    candidate.setHostKeyAlias(scoped.trustEndpoint)
                    candidate.setConfig("StrictHostKeyChecking", "yes")
                    candidate.setConfig("PreferredAuthentications", if (hop.privateKey == null) "password" else "publickey")
                    candidate.setPassword(hop.password.toByteArray())
                    candidate.setServerAliveInterval(15_000)
                    candidate.setServerAliveCountMax(2)
                    checkActive()
                    onProgress(ConnectProgress(hopNumber, ConnectProgress.Stage.AUTHENTICATING))
                    try {
                        candidate.connect(15_000)
                        checkActive()
                        handshake.finish(TraceResult.OK)
                    } catch (error: Exception) {
                        handshake.finish(if (cancelled) TraceResult.CANCELLED else TraceResult.FAILED, error)
                        throw error
                    }
                    previous = candidate
                } catch (error: Exception) {
                    if (cancelled) throw MtmuxException(ErrorCode.CANCELLED, error)
                    verifier.challenge?.let { throw HostKeyRejected(it) }
                    if (error is MtmuxException && error.code == ErrorCode.CANCELLED) throw error
                    throw ConnectionFailure(hopNumber, connectionFailureReason(error), error)
                } finally { jsch.removeAllIdentity() }
            }
            synchronized(stateLock) { checkActive(); session = previous }
            total.end()
            trace.instant(ConnectionPhase.SSH_READY)
        } catch (error: Exception) {
            total.end(if (cancelled) TraceResult.CANCELLED else TraceResult.FAILED, error = error)
            close(); throw error
        }
    }

    fun exec(command: String, stdin: ByteArray? = null, phase: ConnectionPhase = ConnectionPhase.REMOTE_EXEC): ExecResult = measured(phase) {
        checkActive()
        val connection = session ?: fail(ErrorCode.NOT_CONNECTED)
        val channel = connection.openChannel("exec") as ChannelExec
        val stdout = LimitedOutput(512 * 1024)
        val stderr = LimitedOutput(32 * 1024)
        channel.setCommand(command)
        stdin?.let { channel.setInputStream(java.io.ByteArrayInputStream(it)) }
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        ownChannel(channel)
        try {
            measured(ConnectionPhase.CHANNEL_OPEN) { channel.connect(10_000); checkActive() }
            val wait = trace.span(ConnectionPhase.COMMAND_WAIT)
            try {
                val deadline = System.nanoTime() + 10_000_000_000L
                while (!channel.isClosed) {
                    checkActive()
                    ensure(System.nanoTime() < deadline, ErrorCode.REMOTE_TIMEOUT)
                    Thread.sleep(10)
                }
                checkActive()
                ensure(!stdout.overflow && !stderr.overflow, ErrorCode.REMOTE_OUTPUT_LIMIT)
                wait.end(bytes = stdout.size() + stderr.size(), exitStatus = channel.exitStatus)
                ExecResult(channel.exitStatus, stdout.toString("UTF-8"), stderr.toString("UTF-8"))
            } catch (error: Exception) {
                wait.end(if (cancelled) TraceResult.CANCELLED else TraceResult.FAILED, error = error); throw error
            }
        } finally { channel.disconnect(); commands.remove(channel) }
    }

    /** Check availability and list IDs in one SSH round trip. */
    internal fun listPanes(path: String, session: String? = null): List<Pane> {
        val result = exec("${Tmux.version(path)} >/dev/null 2>&1 || exit 127; ${Tmux.discover(path, session)}")
        ensure(result.status != 127, ErrorCode.TMUX_UNAVAILABLE)
        if (result.status != 0 && (result.error.contains("no server running on") || (result.error.contains("error connecting to") && result.error.contains("No such file or directory")))) return emptyList()
        ensure(result.status == 0, ErrorCode.TMUX_DISCOVERY_FAILED)
        val panes = Tmux.parse(result.output)
        ensure(panes.size <= 256, ErrorCode.TOO_MANY_PANES)
        return panes
    }

    /** Names are length framed and batched; connecting only needs the selected session. */
    fun discoverPanes(path: String, session: String? = null): List<Pane> =
        listPanes(path, session).chunked(TmuxFields.BATCH_SIZE).flatMap { batch ->
            val result = exec(TmuxFields.metadataCommand(batch, path))
            ensure(result.status == 0, ErrorCode.TMUX_TARGET_CHANGED)
            TmuxFields.parseMetadata(result.output, batch)
        }

    fun discoverTaskCatalog(path: String): List<TaskCatalog.Entry> {
        val result = exec(TaskCatalog.command(path), phase = ConnectionPhase.TMUX_CATALOG)
        ensure(result.status != 127, ErrorCode.TMUX_UNAVAILABLE)
        if (result.status != 0 && (result.error.contains("no server running on") ||
            (result.error.contains("error connecting to") && result.error.contains("No such file or directory")))) return emptyList()
        ensure(result.status == 0, ErrorCode.TMUX_DISCOVERY_FAILED)
        return TaskCatalog.parse(result.output)
    }

    fun prepareTmux(session: String, expected: PaneBinding?, mouse: Boolean, path: String): TmuxPreparation.Prepared {
        val result = exec(TmuxPreparation.command(session, expected, mouse, path), phase = ConnectionPhase.TMUX_PREPARE)
        ensure(result.status >= 0, ErrorCode.NOT_CONNECTED)
        ensure(result.status != 127, ErrorCode.TMUX_UNAVAILABLE)
        ensure(result.status == 0, if (result.status == 2) ErrorCode.TMUX_MOUSE_FAILED else ErrorCode.TASK_GONE)
        return TmuxPreparation.parse(result.output, session, expected)
    }

    fun createTmuxSession(name: String, path: String): TmuxSessionCreation.Created =
        TmuxSessionCreation.parse(exec(TmuxSessionCreation.command(name, path)))

    fun openTerminal(command: String?, cols: Int, rows: Int): Long = measured(ConnectionPhase.TERMINAL_OPEN) {
        checkActive()
        val connection = session ?: fail(ErrorCode.NOT_CONNECTED)
        val channel: com.jcraft.jsch.Channel = if (command == null) {
            (connection.openChannel("shell") as ChannelShell).apply {
                setPtyType("xterm-256color", cols, rows, 0, 0)
            }
        } else {
            (connection.openChannel("exec") as ChannelExec).apply {
                setCommand(command)
                setPty(true)
                setPtyType("xterm-256color", cols, rows, 0, 0)
            }
        }
        synchronized(stateLock) { checkActive(); terminal = channel }
        input = channel.inputStream
        output = channel.outputStream
        checkActive()
        channel.connect(10_000)
        synchronized(stateLock) { checkActive(); epoch.open() }
    }

    /** Runs on an IO thread. The consumer must apply bounded backpressure. */
    /**
     * Returns the remote exit status when the remote program ended normally (e.g. the user
     * typed `exit` or detached tmux), or -1 when the connection dropped without one.
     */
    fun pump(consume: (ByteArray) -> Unit): Int {
        val channel = terminal ?: fail(ErrorCode.TERMINAL_NOT_CONNECTED)
        try {
            val stream = input ?: fail(ErrorCode.TERMINAL_NOT_CONNECTED)
            val buffer = ByteArray(8192)
            val first = trace.span(ConnectionPhase.FIRST_BYTE)
            var received = false
            try {
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (!received) { received = true; first.end(bytes = count) }
                    consume(buffer.copyOf(count))
                }
            } finally { if (!received) first.end(if (cancelled) TraceResult.CANCELLED else TraceResult.FAILED) }
            // EOF can arrive slightly before the exit-status message; wait briefly for it.
            val deadline = System.nanoTime() + 1_500_000_000L
            while (channel.exitStatus == -1 && !channel.isClosed && System.nanoTime() < deadline) Thread.sleep(20)
            return channel.exitStatus
        } finally { close() }
    }

    fun token() = epoch.token()
    fun bindPane(session: String, path: String): PaneBinding {
        val result = exec(Tmux.binding(session, path))
        ensure(result.status == 0, ErrorCode.BIND_FAILED)
        return PaneBinding.parse(session, result.output.trim())
    }

    fun verifyResume(binding: PaneBinding, path: String) {
        val result = exec(Tmux.exactBinding(binding, path), phase = ConnectionPhase.TMUX_VERIFY)
        ensure(result.status == 0 && result.output.trim() == binding.identity + ":0", ErrorCode.TASK_GONE)
    }

    /** Payload goes over SSH stdin, never in shell arguments or command logs. */
    fun sendToPane(token: Long, binding: PaneBinding, path: String, bytes: ByteArray): Boolean = synchronized(writeLock) {
        ensure(epoch.accepts(token) && terminal?.isConnected == true, ErrorCode.CONNECTION_CHANGED)
        val result = exec(Tmux.pasteBound(binding, path), bytes)
        ensure(result.status == 0, ErrorCode.SEND_UNCERTAIN)
        when (result.output.trim()) {
            "MTMUX_SENT" -> true
            "MTMUX_BLOCKED" -> false
            else -> fail(ErrorCode.SEND_UNCERTAIN)
        }
    }
    fun invalidateInput() = epoch.close()
    fun send(token: Long, bytes: ByteArray) = synchronized(writeLock) {
        ensure(epoch.accepts(token), ErrorCode.CONNECTION_CHANGED)
        try {
            ensure(terminal?.isConnected == true, ErrorCode.TERMINAL_CLOSED)
            output!!.write(bytes)
            output!!.flush()
        } catch (error: Exception) {
            close()
            throw MtmuxException(ErrorCode.SEND_UNCERTAIN, error)
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols !in 2..1000 || rows !in 1..1000) return
        when (val channel = terminal) {
            is ChannelShell -> channel.setPtySize(cols, rows, 0, 0)
            is ChannelExec -> channel.setPtySize(cols, rows, 0, 0)
        }
    }

    /** Fast, idempotent cancellation; safe before connect() starts and from the UI thread. */
    fun cancel() = stop(true)
    private fun stop(report: Boolean) {
        val first = synchronized(stateLock) { if (cancelled) false else { cancelled = true; true } }
        epoch.close()
        if (first && report) trace.instant(ConnectionPhase.CANCEL_REQUEST)
        lookups.forEach { it.cancel(true) }
        sockets.forEach { runCatching { it.close() } }
    }

    override fun close() {
        stop(false)
        val previous = synchronized(stateLock) {
            val saved = (commands.toList() + listOfNotNull(terminal)) to chain.toList()
            commands.clear(); chain.clear(); sockets.clear(); lookups.clear()
            terminal = null; session = null; output = null; input = null
            saved
        }
        previous.first.forEach { runCatching { it.disconnect() } }
        previous.second.reversed().forEach { runCatching { it.disconnect() } }
    }
}

private class LimitedOutput(private val limit: Int) : ByteArrayOutputStream() {
    @Volatile var overflow = false
    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
        val available = (limit - size()).coerceAtLeast(0)
        if (len > available) overflow = true
        super.write(b, off, minOf(len, available))
    }
    @Synchronized override fun write(b: Int) {
        if (size() < limit) super.write(b) else overflow = true
    }
}
