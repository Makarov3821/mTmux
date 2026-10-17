package dev.mtmux.core

/** One SSH command validates the exact target, reads its label, and sets the selected session option. */
object TmuxPreparation {
    data class Prepared(val binding: PaneBinding, val pane: Pane)
    private val values = listOf(Tmux.bindingFormat + ":#{pane_dead}", "#{session_name}", "#{window_name}", "#{window_index}", "#{pane_active}")
    fun command(session: String, expected: PaneBinding?, mouse: Boolean, path: String): String {
        Tmux.binding(session, path)
        require(expected == null || expected.session == session)
        val target = expected?.target ?: "$session:"
        val tmux = Tmux.quote(path) + " -u"
        val identity = "$tmux display-message -p -t ${Tmux.quote(target)} ${Tmux.quote(Tmux.bindingFormat + ":#{pane_dead}")}"
        return buildString {
            append("${Tmux.version(path)} >/dev/null 2>&1 || exit 127; ")
            append("before=\$($identity 2>/dev/null) || exit 1; ")
            if (expected != null) append("[ \"\$before\" = ${Tmux.quote(expected.identity + ":0")} ] || exit 1; ")
            append("printf 'MTMUX_PREPARE_1\\n%s\\n' \"\$before\"; ")
            append("$tmux display-message -p -t ${Tmux.quote(target)} ${Tmux.quote(TmuxFields.format(values))} || exit 1; ")
            if (mouse) append("${Tmux.enableMouse(session, path)} || exit 2; ")
            append("after=\$($identity 2>/dev/null) || exit 1; ")
            append("printf '%s\\nMTMUX_END\\n' \"\$after\"")
        }
    }
    fun parse(output: String, session: String, expected: PaneBinding?): Prepared {
        val prefix = "MTMUX_PREPARE_1\n"
        requireValid(output.startsWith(prefix), ErrorCode.TMUX_METADATA_INVALID)
        val boundary = output.indexOf('\n', prefix.length)
        requireValid(boundary >= 0, ErrorCode.TMUX_METADATA_INVALID)
        val before = output.substring(prefix.length, boundary)
        ensure(before.endsWith(":0"), ErrorCode.TASK_GONE)
        val binding = PaneBinding.parse(session, before.dropLast(2))
        ensure(expected == null || expected == binding, ErrorCode.TASK_GONE)
        val suffix = before + "\nMTMUX_END\n"
        ensure(output.endsWith(suffix), ErrorCode.TASK_GONE)
        val metadata = output.substring(boundary + 1, output.length - suffix.length)
        val entry = TaskCatalog.parse(metadata).singleOrNull() ?: fail(ErrorCode.TASK_GONE)
        ensure(entry.binding == binding, ErrorCode.TASK_GONE)
        return Prepared(binding, entry.pane)
    }
}
