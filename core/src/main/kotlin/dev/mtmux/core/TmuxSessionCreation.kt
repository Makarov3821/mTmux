package dev.mtmux.core

object TmuxSessionCreation {
    data class Created(val binding: PaneBinding, val windowIndex: String)

    fun validName(name: String): Boolean = name.isNotBlank() && name == name.trim() &&
        name.toByteArray(Charsets.UTF_8).size <= 128 && name.none { it == ':' || it == '.' || Character.isISOControl(it) }

    fun command(name: String, path: String): String {
        requireValid(validName(name), ErrorCode.TMUX_SESSION_NAME_INVALID)
        val tmux = Tmux.quote(path) + " -u"
        val exists = "$tmux has-session -t ${argument("=$name")} 2>/dev/null"
        // -s and -n expand tmux formats; keep user-entered # characters literal too.
        val literalName = argument(name.replace("#", "##"))
        val create = "$tmux new-session -d -s $literalName -n $literalName -P -F " +
            Tmux.quote(Tmux.bindingFormat + "\t#{window_index}")
        // new-session itself atomically rejects a duplicate, including a concurrent creator.
        return "${Tmux.version(path)} >/dev/null 2>&1 || exit 127; " +
            "if $exists; then exit 42; fi; " +
            "$create || { if $exists; then exit 42; else exit 43; fi; }"
    }

    // tmux's argv parser treats a trailing semicolon as a command separator,
    // even after the shell has correctly quoted that argv element.
    private fun argument(value: String) = Tmux.quote(if (value.endsWith(';')) value.dropLast(1) + "\\;" else value)

    fun parse(result: ExecResult): Created {
        ensure(result.status != 127, ErrorCode.TMUX_UNAVAILABLE)
        ensure(result.status != 42, ErrorCode.TMUX_SESSION_EXISTS)
        ensure(result.status == 0, ErrorCode.TMUX_CREATE_FAILED)
        val fields = result.output.trim().split('\t')
        requireValid(fields.size == 2 && fields[1].matches(Regex("[0-9]+")), ErrorCode.TMUX_METADATA_INVALID)
        val session = fields[0].split(':').getOrNull(2).orEmpty()
        return Created(PaneBinding.parse(session, fields[0]), fields[1])
    }
}
