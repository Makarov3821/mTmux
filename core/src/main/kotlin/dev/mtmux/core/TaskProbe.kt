package dev.mtmux.core

import java.util.Base64

/** Read-only, bounded batches. Only validated IDs enter commands; names/text are base64 framed. */
object TaskProbe {
    const val BATCH_SIZE = 8
    data class Snapshot(val binding: PaneBinding, val sessionName: String, val windowName: String,
                        val windowIndex: String, val command: String, val title: String, val screen: String,
                        val readable: Boolean) {
        val state: TaskState get() = if (readable) TaskStates.inspect(screen, command, title) else TaskState.UNKNOWN
    }

    fun command(panes: List<Pane>, path: String): String {
        require(panes.size in 1..BATCH_SIZE)
        val tmux = Tmux.quote(path) + " -u"
        // Validate executable using the same policy as other tmux operations.
        Tmux.version(path)
        return buildString {
            append("command -v base64 >/dev/null || exit 127; printf 'MTMUX_PROBE_1\\n';\n")
            for (pane in panes) {
                // Tmux.field validates all three IDs without incorporating user-controlled names.
                Tmux.field(pane, "session_name", path)
                val target = Tmux.quote("${pane.session}:${pane.window}.${pane.id}")
                val identity = "$tmux display-message -p -t $target ${Tmux.quote(Tmux.bindingFormat + ":#{pane_dead}")}"
                append("before=\$($identity 2>/dev/null) || before=;\n")
                append("printf '%s\\n' \"\$before\";\n")
                for (field in listOf("session_name", "window_name", "window_index", "pane_current_command", "pane_title")) {
                    append("$tmux display-message -p -t $target ${Tmux.quote("#{${field}}") } 2>/dev/null | head -c 2048 | base64 | tr -d '\\r\\n'; printf '\\n';\n")
                }
                append("ok=1; screen=\$($tmux capture-pane -p -t $target 2>/dev/null) || ok=0;\n")
                // Keep line boundaries (full-screen TUI footers), never download scrollback.
                append("printf '%s' \"\$screen\" | tail -c 24576 | base64 | tr -d '\\r\\n'; printf '\\n';\n")
                append("after=\$($identity 2>/dev/null) || after=; printf '%s\\n%s\\n' \"\$after\" \"\$ok\";\n")
            }
            append("printf 'MTMUX_END\\n'")
        }
    }

    fun parse(output: String, panes: List<Pane>): List<Snapshot> {
        val lines = output.removeSuffix("\n").split('\n')
        require(lines.size == 2 + panes.size * 9 && lines.first() == "MTMUX_PROBE_1" && lines.last() == "MTMUX_END") {
            "无法解析状态快照（输出截断或 shell 启动输出）"
        }
        return panes.mapIndexedNotNull { index, pane ->
            val fields = lines.subList(1 + index * 9, 1 + (index + 1) * 9)
            val before = fields[0]
            if (!before.endsWith(":0")) return@mapIndexedNotNull null
            val binding = PaneBinding.parse(pane.session, before.removeSuffix(":0"))
            require(binding.pane == pane.id && binding.target == "${pane.session}:${pane.window}.${pane.id}")
            fun decode(i: Int) = String(Base64.getDecoder().decode(fields[i]), Charsets.UTF_8)
            Snapshot(binding, decode(1).removeSuffix("\n").take(512), decode(2).removeSuffix("\n").take(512),
                decode(3).trim(), decode(4).trim(), decode(5).trim(), decode(6), before == fields[7] && fields[8] == "1")
        }
    }
}

/** Two initial channels plus one per eight panes; no per-pane SSH round trips. */
fun SshClient.discoverTaskSnapshots(path: String, onBatch: (List<TaskProbe.Snapshot>) -> Unit = {}): List<TaskProbe.Snapshot> {
    check(exec(Tmux.version(path)).status == 0) { "tmux 未安装、不可执行或不在 PATH；请检查绝对路径" }
    val listed = exec(Tmux.discover(path))
    if (listed.status != 0 && (listed.error.contains("no server running on") ||
                (listed.error.contains("error connecting to") && listed.error.contains("No such file or directory")))) return emptyList()
    check(listed.status == 0) { "tmux 发现失败；请检查 socket 或权限" }
    val panes = Tmux.parse(listed.output)
    check(panes.size <= 256) { "pane 数量超过当前上限 256" }
    return panes.chunked(TaskProbe.BATCH_SIZE).flatMap { batch ->
        val result = exec(TaskProbe.command(batch, path))
        check(result.status == 0) { "状态读取失败；请检查 tmux 与 base64 命令是否可用" }
        TaskProbe.parse(result.output, batch).also(onBatch)
    }
}
