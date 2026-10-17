package dev.mtmux.core

/** A single read-only list command supplies names and resumable identities before screen reads. */
object TaskCatalog {
    data class Entry(val binding: PaneBinding, val pane: Pane) {
        fun snapshot() = TaskProbe.Snapshot(binding, pane.sessionName, pane.windowName, pane.windowIndex, "", "", "", false)
    }
    private val values = listOf(Tmux.bindingFormat + ":#{pane_dead}", "#{session_name}", "#{window_name}", "#{window_index}", "#{pane_active}")
    fun command(path: String, session: String? = null): String {
        Tmux.discover(path, session) // Validate path and session ID.
        val scope = if (session == null) "-a" else "-s -t ${Tmux.quote(session)}"
        return "${Tmux.version(path)} >/dev/null 2>&1 || exit 127; " +
            "${Tmux.quote(path)} -u list-panes $scope -F ${Tmux.quote(TmuxFields.format(values))}"
    }
    fun parse(output: String): List<Entry> = TmuxFields.records(output.toByteArray(Charsets.UTF_8), values.size).mapNotNull { fields ->
        val raw = fields[0]
        requireValid(raw.endsWith(":0") || raw.endsWith(":1"), ErrorCode.TMUX_IDENTITY_INVALID)
        val parts = raw.split(':')
        requireValid(parts.size == 7, ErrorCode.TMUX_IDENTITY_INVALID)
        val binding = PaneBinding.parse(parts[2], raw.dropLast(2))
        requireValid(fields[4] in listOf("0", "1"), ErrorCode.TMUX_METADATA_INVALID)
        if (raw.endsWith(":1")) null else Entry(binding, Pane(binding.session, parts[3], binding.pane,
            fields[4] == "1", fields[1].take(512), fields[2].take(512), fields[3]))
    }
}
