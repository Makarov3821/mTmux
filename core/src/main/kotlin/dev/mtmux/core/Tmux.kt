package dev.mtmux.core

object Tmux {
    internal const val bindingFormat = "#{pid}:#{start_time}:#{session_id}:#{window_id}:#{pane_id}:#{pane_pid}"
    fun exactBinding(binding: PaneBinding, path: String) =
        "${executable(path)} display-message -p -t ${quote(binding.target)} ${quote(bindingFormat + ":#{pane_dead}")}"

    fun resume(binding: PaneBinding, path: String): String {
        val condition = "#{&&:#{==:$bindingFormat,${binding.identity}},#{==:#{pane_dead},0}}"
        val commands = "select-window -t '${binding.target}' ; select-pane -t '${binding.target}' ; attach-session -t '${binding.session}'"
        return "${executable(path)} if-shell -F -t ${quote(binding.target)} ${quote(condition)} ${quote(commands)} ${quote("display-message -p '原任务已改变，请重新选择'")}"
    }
    fun binding(session: String, path: String): String {
        require(Regex("\\$[0-9]+").matches(session))
        return "${executable(path)} display-message -p -t ${quote(session + ":")} ${quote(bindingFormat)}"
    }

    fun pasteBound(binding: PaneBinding, path: String): String {
        val tmux = executable(path)
        val buffer = "mtmux-" + java.util.UUID.randomUUID().toString()
        // Format check and explicit pane dispatch run in tmux's command queue.
        // Even if focus changes afterwards the payload cannot follow that focus.
        val condition = "#{&&:#{==:$bindingFormat,${binding.identity}},#{&&:#{==:#{pane_in_mode},0},#{&&:#{==:#{pane_dead},0},#{==:#{synchronize-panes},0}}}}"
        val paste = "paste-buffer -d -r -b $buffer -t '${binding.pane}' ; display-message -p MTMUX_SENT"
        return "trap ${quote("$tmux delete-buffer -b $buffer >/dev/null 2>&1")} EXIT; " +
            "$tmux load-buffer -b $buffer - && " +
            "$tmux if-shell -F -t ${quote(binding.session + ":")} ${quote(condition)} ${quote(paste)} ${quote("display-message -p MTMUX_BLOCKED") }"
    }
    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL is not a shell argument" }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun executable(path: String): String {
        require(path == "tmux" || (path.startsWith('/') && '\n' !in path && '\r' !in path)) {
            "tmux 路径须为绝对路径或 tmux"
        }
        return quote(path) + " -u"
    }

    fun version(path: String = "tmux") = "${executable(path)} -V"
    // IDs only: user-controlled names/titles cannot break the record framing.
    fun discover(path: String = "tmux") = "${executable(path)} list-panes -a -F " +
        quote("#{session_id}\t#{window_id}\t#{pane_id}\t#{pane_active}")

    fun attach(session: String, path: String = "tmux"): String {
        require(Regex("\\$[0-9]+").matches(session)) { "Invalid session ID" }
        // P0 deliberately exposes shared-session behavior. Never detach another client.
        return "${executable(path)} attach-session -t ${quote(session)}"
    }

    fun leaveCopyMode(session: String, path: String = "tmux"): String {
        require(Regex("\\$[0-9]+").matches(session)) { "Invalid session ID" }
        return "${executable(path)} send-keys -X -t ${quote(session)} cancel"
    }

    fun leaveCopyMode(binding: PaneBinding, path: String): String {
        val condition = "#{&&:#{==:$bindingFormat,${binding.identity}},#{==:#{pane_dead},0}}"
        val cancel = "if-shell -F -t '${binding.target}' '#{==:#{pane_mode},copy-mode}' 'send-keys -X -t ${binding.target} cancel' ; display-message -p MTMUX_LATEST"
        return "${executable(path)} if-shell -F -t ${quote(binding.session + ":")} ${quote(condition)} ${quote(cancel)} ${quote("display-message -p MTMUX_CHANGED") }"
    }

    fun enableMouse(session: String, path: String = "tmux"): String {
        require(Regex("\\$[0-9]+").matches(session)) { "Invalid session ID" }
        return "${executable(path)} set-option -t ${quote(session)} mouse on"
    }

    fun field(pane: Pane, field: String, path: String = "tmux"): String {
        require(Regex("%[0-9]+").matches(pane.id) && Regex("\\$[0-9]+").matches(pane.session) &&
            Regex("@[0-9]+").matches(pane.window)) { "Invalid pane target" }
        require(field in setOf("session_name", "window_name", "window_index"))
        return "${executable(path)} display-message -p -t ${quote("${pane.session}:${pane.window}.${pane.id}")} ${quote("#{${field}}")}" 
    }

    /** Only the current pane screen; never enter copy-mode or change shared focus. */
    fun taskScreen(binding: PaneBinding, path: String): String =
        "${executable(path)} capture-pane -p -J -t ${quote(binding.target)}"

    fun history(pane: String, path: String = "tmux"): String {
        require(Regex("%[0-9]+").matches(pane)) { "Invalid pane ID" }
        return "${executable(path)} capture-pane -p -J -S -2000 -t ${quote(pane)}"
    }

    fun parse(output: String): List<Pane> = output.lineSequence().filter { it.isNotBlank() }.map { line ->
        val fields = line.split('\t')
        require(fields.size == 4 && Regex("\\$[0-9]+").matches(fields[0]) &&
            Regex("@[0-9]+").matches(fields[1]) && Regex("%[0-9]+").matches(fields[2]) &&
            fields[3] in listOf("0", "1")) { "无法解析 tmux 元数据（可能包含 shell 启动输出）" }
        Pane(fields[0], fields[1], fields[2], fields[3] == "1")
    }.toList()
}

data class PaneBinding private constructor(val session: String, val pane: String, val identity: String) {
    val target: String get() = "$session:${identity.split(':')[3]}.$pane"
    companion object {
        fun parse(session: String, value: String): PaneBinding {
            require(Regex("[0-9]+:[0-9]+:\\$[0-9]+:@[0-9]+:%[0-9]+:[0-9]+").matches(value)) { "无法核对 tmux 目标身份" }
            val fields = value.split(':')
            require(fields[2] == session) { "tmux 会话已改变" }
            return PaneBinding(session, fields[4], value)
        }
    }
}

data class Pane(
    val session: String, val window: String, val id: String, val active: Boolean,
    val sessionName: String = session, val windowName: String = window, val windowIndex: String = ""
) {
    val label: String get() = "${windowIndex.ifEmpty { window }} · $windowName · $id"
}

/** Tokens belong to one connection only; reconnect never revives queued input. */
class ConnectionEpoch {
    private var generation = 0L
    private var connected = false
    @Synchronized fun open(): Long { generation++; connected = true; return generation }
    @Synchronized fun close() { connected = false; generation++ }
    @Synchronized fun token(): Long? = if (connected) generation else null
    @Synchronized fun accepts(token: Long): Boolean = connected && generation == token
}
