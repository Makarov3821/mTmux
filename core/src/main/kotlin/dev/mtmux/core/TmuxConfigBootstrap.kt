package dev.mtmux.core

import java.security.MessageDigest

/** Receipt binds undo to the exact user-owned inode and template, not just a pathname. */
data class TmuxConfigReceipt(val home: String, val identity: String, val sha256: String, val history: Int)
data class TmuxConfigUndo(val backup: String)

object TmuxConfigBootstrap {
    fun template(history: Int): String {
        require(history in 2000..500000)
        return "# mTmux minimal config v1\nset -g mouse on\nset -g history-limit $history\nset -g destroy-unattached off\nset -s exit-unattached off\n"
    }
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun validateHome(home: String) {
        require(home.startsWith('/') && home != "/" && home.length <= 4096 && home.none { it == '\u0000' || it == '\n' || it == '\r' })
    }
    fun createCommand(path: String, home: String, history: Int, expectedVersion: String): String {
        Tmux.version(path); validateHome(home)
        require(TmuxRelease.parse(expectedVersion)?.let { it >= TmuxRelease.BASELINE } == true)
        val body = template(history)
        val script = """
            LC_ALL=C; export LC_ALL; umask 077; set -f
            doctor_tmux=${Tmux.quote(path)}
            doctor_home=${Tmux.quote(home)}
            ${TmuxEnvironmentProbe.safetyFunctions}
            doctor_home_safe && doctor_config_absent && doctor_no_server || exit 71
            doctor_version=${'$'}("${'$'}doctor_tmux" -V 2>/dev/null) || exit 71
            [ "${'$'}doctor_version" = ${Tmux.quote(expectedVersion)} ] || exit 71
            doctor_temp=${'$'}(mktemp "${'$'}doctor_home/.mtmux-doctor.XXXXXXXX") || exit 72
            trap 'rm -f -- "${'$'}doctor_temp"' EXIT HUP INT TERM
            printf '%s' ${Tmux.quote(body)} > "${'$'}doctor_temp" || exit 72
            doctor_hash=${'$'}(sha256sum < "${'$'}doctor_temp") || exit 72
            doctor_hash=${'$'}{doctor_hash%% *}
            [ "${'$'}doctor_hash" = ${Tmux.quote(hash(body))} ] || exit 72
            doctor_identity=${'$'}(stat -c '%d:%i:%u' -- "${'$'}doctor_temp") || exit 72
            # -T prevents an existing symlink/directory being interpreted as a destination directory.
            # No -f: link creation atomically refuses any existing destination.
            ln -T -- "${'$'}doctor_temp" "${'$'}doctor_home/.tmux.conf" || exit 73
            printf 'MTMUX_CONFIG_1\n%s\n%s\nMTMUX_END\n' "${'$'}doctor_identity" "${'$'}doctor_hash"
        """.trimIndent()
        return "sh -c " + Tmux.quote(script)
    }
    fun parseCreated(result: ExecResult, home: String, history: Int): TmuxConfigReceipt {
        validateHome(home)
        require(result.status == 0) { "config-create-unconfirmed" }
        val lines = result.output.split('\n')
        require(lines.size == 5 && lines[0] == "MTMUX_CONFIG_1" && lines[3] == "MTMUX_END" && lines[4].isEmpty())
        require(lines[1].matches(Regex("[0-9]+:[0-9]+:[0-9]+")) && lines[2] == hash(template(history)))
        return TmuxConfigReceipt(home, lines[1], lines[2], history)
    }
    fun undoCommand(receipt: TmuxConfigReceipt): String {
        validateHome(receipt.home)
        require(receipt.identity.matches(Regex("[0-9]+:[0-9]+:[0-9]+")) && receipt.sha256 == hash(template(receipt.history)))
        val script = """
            LC_ALL=C; export LC_ALL; umask 077; set -f
            doctor_home=${Tmux.quote(receipt.home)}
            doctor_target="${'$'}doctor_home/.tmux.conf"
            matches() {
                [ ! -L "${'$'}1" ] && [ -f "${'$'}1" ] || return 1
                [ "${'$'}(stat -c '%d:%i:%u' -- "${'$'}1")" = ${Tmux.quote(receipt.identity)} ] || return 1
                doctor_hash=${'$'}(sha256sum < "${'$'}1") || return 1
                [ "${'$'}{doctor_hash%% *}" = ${Tmux.quote(receipt.sha256)} ]
            }
            matches "${'$'}doctor_target" || exit 74
            doctor_backup=${'$'}(mktemp -d "${'$'}doctor_home/.mtmux-doctor-undo.XXXXXXXX") || exit 72
            # Preserve bytes in a private backup even if another process changes the target during undo.
            mv -T -- "${'$'}doctor_target" "${'$'}doctor_backup/tmux.conf" || exit 72
            if ! matches "${'$'}doctor_backup/tmux.conf"; then
                ln -T -- "${'$'}doctor_backup/tmux.conf" "${'$'}doctor_target" 2>/dev/null || :
                exit 75
            fi
            printf 'MTMUX_UNDO_1\n%s\nMTMUX_END\n' "${'$'}doctor_backup/tmux.conf"
        """.trimIndent()
        return "sh -c " + Tmux.quote(script)
    }
    fun parseUndone(result: ExecResult, receipt: TmuxConfigReceipt): TmuxConfigUndo {
        require(result.status == 0) { "config-undo-unconfirmed" }
        val lines = result.output.split('\n')
        require(lines.size == 4 && lines[0] == "MTMUX_UNDO_1" && lines[2] == "MTMUX_END" && lines[3].isEmpty())
        require(lines[1].startsWith(receipt.home + "/.mtmux-doctor-undo.") && lines[1].endsWith("/tmux.conf"))
        return TmuxConfigUndo(lines[1])
    }
}
