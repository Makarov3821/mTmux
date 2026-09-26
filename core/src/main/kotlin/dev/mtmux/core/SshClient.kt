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

class SshClient(private val pins: PinStore) : AutoCloseable {
    @Volatile private var session: Session? = null
    @Volatile private var terminal: com.jcraft.jsch.Channel? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private val epoch = ConnectionEpoch()
    private val writeLock = Any()
    private val chain = java.util.concurrent.CopyOnWriteArrayList<Session>()
    @Volatile private var connectGeneration = 0L

    fun connect(login: Login, onProgress: (ConnectProgress) -> Unit = {}) {
        close()
        val generation = connectGeneration
        val hops = login.jumps + login
        requireValid(login.jumps.none { it.jumps.isNotEmpty() }, ErrorCode.INVALID_JUMP_CHAIN)
        var previous: Session? = null
        try {
            hops.forEachIndexed { index, hop ->
                ensure(generation == connectGeneration, ErrorCode.CANCELLED)
                val scoped = Login(hop.host, hop.port, hop.user, hop.password, hop.privateKey, hop.passphrase, hops.take(index))
                val jsch = JSch()
                val verifier = PinnedHostKeys(scoped.trustEndpoint, pins)
                jsch.hostKeyRepository = verifier
                val hopNumber = if (index < hops.lastIndex) index + 1 else 0
                try {
                    onProgress(ConnectProgress(hopNumber, ConnectProgress.Stage.PREPARING))
                    hop.privateKey?.let { jsch.addIdentity("memory-key", it, null, hop.passphrase.toByteArray()) }
                    val forwarded = previous?.setPortForwardingL("127.0.0.1", 0, hop.host, hop.port)
                    val candidate = jsch.getSession(hop.user, if (forwarded == null) hop.host else "127.0.0.1", forwarded ?: hop.port)
                    chain.add(candidate)
                    candidate.setHostKeyAlias(scoped.trustEndpoint)
                    candidate.setConfig("StrictHostKeyChecking", "yes")
                    candidate.setConfig("PreferredAuthentications", if (hop.privateKey == null) "password" else "publickey")
                    candidate.setPassword(hop.password.toByteArray())
                    candidate.setServerAliveInterval(15_000)
                    candidate.setServerAliveCountMax(2)
                    ensure(generation == connectGeneration, ErrorCode.CANCELLED)
                    onProgress(ConnectProgress(hopNumber, ConnectProgress.Stage.AUTHENTICATING))
                    candidate.connect(15_000)
                    ensure(generation == connectGeneration, ErrorCode.CANCELLED)
                    previous = candidate
                } catch (error: Exception) {
                    verifier.challenge?.let { throw HostKeyRejected(it) }
                    if (error is MtmuxException && error.code == ErrorCode.CANCELLED) throw error
                    throw ConnectionFailure(hopNumber, connectionFailureReason(error), error)
                } finally { jsch.removeAllIdentity() }
            }
            session = previous
        } catch (error: Exception) { close(); throw error }
    }

    fun exec(command: String, stdin: ByteArray? = null): ExecResult {
        val connection = session ?: fail(ErrorCode.NOT_CONNECTED)
        val channel = connection.openChannel("exec") as ChannelExec
        val stdout = LimitedOutput(512 * 1024)
        val stderr = LimitedOutput(32 * 1024)
        channel.setCommand(command)
        stdin?.let { channel.setInputStream(java.io.ByteArrayInputStream(it)) }
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        try {
            channel.connect(10_000)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!channel.isClosed) {
                ensure(System.nanoTime() < deadline, ErrorCode.REMOTE_TIMEOUT)
                Thread.sleep(10)
            }
            ensure(!stdout.overflow && !stderr.overflow, ErrorCode.REMOTE_OUTPUT_LIMIT)
            return ExecResult(channel.exitStatus, stdout.toString("UTF-8"), stderr.toString("UTF-8"))
        } finally { channel.disconnect() }
    }

    /** Names travel as separate bounded responses, never as tab/newline-delimited records. */
    fun discoverPanes(path: String): List<Pane> {
        val version = exec(Tmux.version(path))
        ensure(version.status == 0, ErrorCode.TMUX_UNAVAILABLE)
        val result = exec(Tmux.discover(path))
        if (result.status != 0 && (result.error.contains("no server running on") || (result.error.contains("error connecting to") && result.error.contains("No such file or directory")))) return emptyList()
        ensure(result.status == 0, ErrorCode.TMUX_DISCOVERY_FAILED)
        val panes = Tmux.parse(result.output)
        ensure(panes.size <= 256, ErrorCode.TOO_MANY_PANES)
        val sessions = mutableMapOf<String, String>()
        val windows = mutableMapOf<String, Pair<String, String>>()
        fun field(pane: Pane, name: String): String {
            val value = exec(Tmux.field(pane, name, path))
            ensure(value.status == 0, ErrorCode.TMUX_TARGET_CHANGED)
            return value.output.removeSuffix("\n").take(512)
        }
        return panes.map { pane ->
            val session = sessions.getOrPut(pane.session) { field(pane, "session_name") }
            val window = windows.getOrPut("${pane.session}/${pane.window}") {
                field(pane, "window_index") to field(pane, "window_name")
            }
            pane.copy(sessionName = session, windowIndex = window.first, windowName = window.second)
        }
    }

    fun openTerminal(command: String?, cols: Int, rows: Int): Long {
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
        terminal = channel
        input = channel.inputStream
        output = channel.outputStream
        channel.connect(10_000)
        return epoch.open()
    }

    /** Runs on an IO thread. The consumer must apply bounded backpressure. */
    fun pump(consume: (ByteArray) -> Unit) {
        val channel = terminal ?: fail(ErrorCode.TERMINAL_NOT_CONNECTED)
        try {
            val stream = input ?: fail(ErrorCode.TERMINAL_NOT_CONNECTED)
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                consume(buffer.copyOf(count))
            }
        } finally { close() }
    }

    fun token() = epoch.token()
    fun bindPane(session: String, path: String): PaneBinding {
        val result = exec(Tmux.binding(session, path))
        ensure(result.status == 0, ErrorCode.BIND_FAILED)
        return PaneBinding.parse(session, result.output.trim())
    }

    fun verifyResume(binding: PaneBinding, path: String) {
        val result = exec(Tmux.exactBinding(binding, path))
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

    override fun close() {
        connectGeneration++
        epoch.close()
        terminal?.disconnect()
        chain.reversed().forEach { it.disconnect() }
        chain.clear()
        session?.disconnect()
        terminal = null
        session = null
        output = null
        input = null
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
