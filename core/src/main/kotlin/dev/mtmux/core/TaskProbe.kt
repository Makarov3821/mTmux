package dev.mtmux.core

import java.util.Base64

/** Read-only, bounded batches. Only validated IDs enter commands; names/text are base64 framed. */
object TaskProbe {
    const val BATCH_SIZE = TmuxFields.BATCH_SIZE
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
            append("command -v base64 >/dev/null || exit 127; printf 'MTMUX_PROBE_2\\n';\n")
            for (pane in panes) {
                // Tmux.field validates all three IDs without incorporating user-controlled names.
                Tmux.field(pane, "session_name", path)
                val target = Tmux.quote("${pane.session}:${pane.window}.${pane.id}")
                val identity = "$tmux display-message -p -t $target ${Tmux.quote(Tmux.bindingFormat + ":#{pane_dead}")}"
                append("before=\$($identity 2>/dev/null) || before=;\n")
                append("printf '%s\\n' \"\$before\";\n")
                append(TmuxFields.encodedCommand(pane, listOf("session_name", "window_name", "window_index", "pane_current_command", "pane_title").map { "#{${it}}" }, path, required = false))
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
        requireValid(lines.size == 2 + panes.size * 5 && lines.first() == "MTMUX_PROBE_2" && lines.last() == "MTMUX_END", ErrorCode.STATUS_PARSE_FAILED)
        return panes.mapIndexedNotNull { index, pane ->
            val fields = lines.subList(1 + index * 5, 1 + (index + 1) * 5)
            val before = fields[0]
            if (!before.endsWith(":0")) return@mapIndexedNotNull null
            val binding = PaneBinding.parse(pane.session, before.removeSuffix(":0"))
            require(binding.pane == pane.id && binding.target == "${pane.session}:${pane.window}.${pane.id}")
            val metadata = if (fields[1].isNotEmpty()) TmuxFields.decode(fields[1], 5) else
                listOf(pane.sessionName, pane.windowName, pane.windowIndex, "", "")
            val screen = String(Base64.getDecoder().decode(fields[2]), Charsets.UTF_8)
            Snapshot(binding, metadata[0].take(512), metadata[1].take(512), metadata[2].trim(),
                metadata[3].trim(), metadata[4].trim(), screen, fields[1].isNotEmpty() && before == fields[3] && fields[4] == "1")
        }
    }
}

/** Publish names first; at most two screen batches run concurrently on this SSH session. */
fun SshClient.discoverTaskSnapshots(path: String,
    onListed: (List<TaskProbe.Snapshot>) -> Unit = {},
    stopRequested: () -> Boolean = { false },
    onBatch: (List<TaskProbe.Snapshot>) -> Unit = {}
): List<TaskProbe.Snapshot> {
    val catalog = discoverTaskCatalog(path)
    onListed(catalog.map { it.snapshot() })
    val batches = catalog.map { it.pane }.chunked(TaskProbe.BATCH_SIZE)
    if (batches.isEmpty() || stopRequested()) return catalog.map { it.snapshot() }
    val worker = java.util.concurrent.Executors.newFixedThreadPool(minOf(2, batches.size)) { task ->
        Thread(task, "mtmux-status").apply { isDaemon = true }
    }
    val completion = java.util.concurrent.ExecutorCompletionService<Pair<Int, List<TaskProbe.Snapshot>>>(worker)
    val futures = mutableListOf<java.util.concurrent.Future<Pair<Int, List<TaskProbe.Snapshot>>>>()
    fun submit(index: Int) { futures += completion.submit(java.util.concurrent.Callable {
        val batch = batches[index]
        val result = exec(TaskProbe.command(batch, path), phase = ConnectionPhase.TMUX_STATUS)
        ensure(result.status == 0, ErrorCode.STATUS_READ_FAILED)
        index to TaskProbe.parse(result.output, batch)
    }) }
    try {
        val results = arrayOfNulls<List<TaskProbe.Snapshot>>(batches.size)
        var next = 0
        var running = 0
        while (next < minOf(2, batches.size) && !stopRequested()) { submit(next++); running++ }
        while (running > 0) {
            val (index, snapshots) = try { completion.take().get() }
            catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
            running--
            results[index] = snapshots
            onBatch(snapshots)
            if (next < batches.size && !stopRequested()) { submit(next++); running++ }
        }
        // A handoff drains every submitted command before returning exclusive ownership.
        return results.flatMapIndexed { index, snapshots ->
            snapshots ?: catalog.drop(index * TaskProbe.BATCH_SIZE).take(TaskProbe.BATCH_SIZE).map { it.snapshot() }
        }
    } finally {
        futures.forEach { it.cancel(true) }
        worker.shutdownNow()
    }
}
