package dev.mtmux.core

/** Compare release numbers and letter revisions without treating 3.10 as a decimal. */
data class TmuxRelease(val major: Int, val minor: Int, val revision: String) : Comparable<TmuxRelease> {
    override fun compareTo(other: TmuxRelease): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.revision })
    companion object {
        val BASELINE = TmuxRelease(3, 2, "a")
        fun parse(value: String): TmuxRelease? {
            val match = Regex("^(?:tmux )?(\\d{1,3})\\.(\\d{1,3})([a-z]*)$").matchEntire(value.trim()) ?: return null
            return TmuxRelease(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3])
        }
    }
}

enum class DoctorGrade { PASS, WARNING, FAIL, UNKNOWN }
enum class DoctorCheck { SSH, EXECUTABLE, VERSION, SESSIONS, PROTOCOL, MOUSE, WHEEL, HISTORY, PERSISTENCE, INPUT, DEPENDENCIES, DISPLAY, CONFIG }
enum class DoctorReason {
    VERIFIED, EXECUTABLE_OK, PATH_MISSING, PATH_NOT_EXECUTABLE, EXECUTABLE_FAILED,
    VERSION_OK, VERSION_OLD, VERSION_UNKNOWN, SESSIONS_OK, NO_SERVER, SERVER_INACCESSIBLE,
    PROTOCOL_OK, PROTOCOL_MISSING, MOUSE_ON, MOUSE_OFF, WHEEL_OK, WHEEL_CUSTOM, WHEEL_MISSING,
    HISTORY_OK, HISTORY_SMALL, PERSISTENCE_OK, PERSISTENCE_CHANGED, INPUT_OK, INPUT_SYNC,
    HELPERS_OK, HELPERS_MISSING, DISPLAY_OK, DISPLAY_CHECK, CONFIG_EXISTS, CONFIG_READY, CONFIG_UNAVAILABLE, NOT_CHECKED
}
data class DoctorFinding(val check: DoctorCheck, val grade: DoctorGrade, val reason: DoctorReason, val detail: String = "")
enum class TmuxAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }
data class DoctorAvailabilityRecord(val revision: String, val checkedAt: Long, val state: TmuxAvailability) {
    fun effective(currentRevision: String, now: Long): TmuxAvailability =
        if (revision == currentRevision && now >= checkedAt && now - checkedAt < TTL) state else TmuxAvailability.UNKNOWN
    companion object { const val TTL = 5 * 60_000L }
}

data class TmuxDoctorReport(val facts: Map<String, String>) {
    operator fun get(key: String) = facts[key].orEmpty()
    val availability: TmuxAvailability get() = when (this["path.status"]) {
        "ok" -> TmuxAvailability.AVAILABLE
        "missing", "not_executable", "failed" -> TmuxAvailability.UNAVAILABLE
        else -> TmuxAvailability.UNKNOWN
    }
    val canCreate: Boolean get() = availability == TmuxAvailability.AVAILABLE && this["server.status"] in setOf("ready", "none")
    val belowBaseline: Boolean get() = listOf(this["version.client"], this["version.server"]).any {
        TmuxRelease.parse(it)?.let { version -> version < TmuxRelease.BASELINE } == true
    }
    val canInitialize: Boolean get() = availability == TmuxAvailability.AVAILABLE &&
        TmuxRelease.parse(this["version.client"])?.let { it >= TmuxRelease.BASELINE } == true &&
        this["server.status"] == "none" && this["config.status"] == "absent" && this["home.safe"] == "yes" &&
        this["bootstrap.helpers"] == "yes" && this["kernel"] == "Linux" && this["config.loader"] == "standard"

    fun findings(): List<DoctorFinding> = buildList {
        fun row(check: DoctorCheck, grade: DoctorGrade, reason: DoctorReason, detail: String = "") {
            add(DoctorFinding(check, grade, reason, detail.take(2048)))
        }
        row(DoctorCheck.SSH, DoctorGrade.PASS, DoctorReason.VERIFIED)
        row(DoctorCheck.EXECUTABLE, when (availability) {
            TmuxAvailability.AVAILABLE -> DoctorGrade.PASS
            TmuxAvailability.UNAVAILABLE -> DoctorGrade.FAIL
            else -> DoctorGrade.UNKNOWN
        }, when (this@TmuxDoctorReport["path.status"]) {
            "ok" -> DoctorReason.EXECUTABLE_OK
            "missing" -> DoctorReason.PATH_MISSING
            "not_executable" -> DoctorReason.PATH_NOT_EXECUTABLE
            else -> DoctorReason.EXECUTABLE_FAILED
        }, this@TmuxDoctorReport["path"])
        val versions = listOf(this@TmuxDoctorReport["version.client"], this@TmuxDoctorReport["version.server"]).filter { it.isNotEmpty() }
        row(DoctorCheck.VERSION, if (versions.isEmpty() || versions.any { TmuxRelease.parse(it) == null } || belowBaseline) DoctorGrade.WARNING else DoctorGrade.PASS,
            when { belowBaseline -> DoctorReason.VERSION_OLD; versions.isEmpty() || versions.any { TmuxRelease.parse(it) == null } -> DoctorReason.VERSION_UNKNOWN; else -> DoctorReason.VERSION_OK }, versions.joinToString(" / "))
        val ready = this@TmuxDoctorReport["server.status"] == "ready"
        val deferred = if(this@TmuxDoctorReport["server.status"]=="none") DoctorReason.NO_SERVER else DoctorReason.NOT_CHECKED
        row(DoctorCheck.SESSIONS, when (this@TmuxDoctorReport["server.status"]) {"ready", "none" -> DoctorGrade.PASS; "error" -> DoctorGrade.FAIL; else -> DoctorGrade.UNKNOWN},
            when (this@TmuxDoctorReport["server.status"]) {"ready" -> DoctorReason.SESSIONS_OK; "none" -> DoctorReason.NO_SERVER; "error" -> DoctorReason.SERVER_INACCESSIBLE; else -> DoctorReason.NOT_CHECKED}, this@TmuxDoctorReport["sessions.sampled"])
        val frame = this@TmuxDoctorReport["server.frame"].split(':', limit = 2)
        val frameOk = frame.size == 2 && frame[0].toIntOrNull() == frame[1].toByteArray(Charsets.UTF_8).size && frame[1].matches(Regex("[0-9]+"))
        row(DoctorCheck.PROTOCOL, if (!ready) DoctorGrade.UNKNOWN else if (frameOk) DoctorGrade.PASS else DoctorGrade.FAIL,
            if (!ready) deferred else if (frameOk) DoctorReason.PROTOCOL_OK else DoctorReason.PROTOCOL_MISSING)
        val sessionMouse = facts.filterKeys { it.startsWith("session.") && it.endsWith(".mouse") }.values
        val mouseOk = this@TmuxDoctorReport["mouse"] == "on" && sessionMouse.all { it == "on" }
        row(DoctorCheck.MOUSE, if (!ready) DoctorGrade.UNKNOWN else if (mouseOk) DoctorGrade.PASS else DoctorGrade.WARNING,
            if (!ready) deferred else if (mouseOk) DoctorReason.MOUSE_ON else DoctorReason.MOUSE_OFF,
            (listOf(this@TmuxDoctorReport["mouse"]) + sessionMouse).filter { it.isNotEmpty() }.distinct().joinToString(" / "))
        // root WheelDownPane need not be bound: default unbound mouse forwarding is valid.
        val bindings = listOf("root.up", "copy.up", "copy.down", "vi.up", "vi.down").map { this@TmuxDoctorReport["binding.$it"] }
        val wheelReason = when { !ready -> deferred; bindings.any { it.isEmpty() || it == "missing" } -> DoctorReason.WHEEL_MISSING; bindings.any { it != "standard" } -> DoctorReason.WHEEL_CUSTOM; else -> DoctorReason.WHEEL_OK }
        row(DoctorCheck.WHEEL, if (!ready) DoctorGrade.UNKNOWN else if (wheelReason == DoctorReason.WHEEL_OK) DoctorGrade.PASS else DoctorGrade.WARNING, wheelReason,
            listOf(this@TmuxDoctorReport["step.copy.up"], this@TmuxDoctorReport["step.copy.down"], this@TmuxDoctorReport["step.vi.up"], this@TmuxDoctorReport["step.vi.down"]).filter { it.isNotEmpty() }.joinToString(" / "))
        val panes = facts.filterKeys { it.startsWith("pane.") }.values.map { it.split(':') }.filter { it.size == 6 }
        val limits = (listOf(this@TmuxDoctorReport["history"]) + panes.map { it[1] } + facts.filterKeys { it.startsWith("session.") && it.endsWith(".history") }.values).mapNotNull { it.toLongOrNull() }
        val small = limits.any { it < 2000 }
        row(DoctorCheck.HISTORY, if (!ready) DoctorGrade.UNKNOWN else if (limits.isEmpty() || small) DoctorGrade.WARNING else DoctorGrade.PASS,
            if (!ready) deferred else if (limits.isEmpty() || small) DoctorReason.HISTORY_SMALL else DoctorReason.HISTORY_OK,
            limits.distinct().sorted().joinToString(" / "))
        val persistent = this@TmuxDoctorReport["exit_unattached"] == "off" && this@TmuxDoctorReport["destroy_unattached"] == "off" &&
            facts.filterKeys { it.startsWith("session.") && it.endsWith(".destroy") }.values.all { it == "off" }
        row(DoctorCheck.PERSISTENCE, if (!ready) DoctorGrade.UNKNOWN else if (persistent) DoctorGrade.PASS else DoctorGrade.WARNING,
            if (!ready) deferred else if (persistent) DoctorReason.PERSISTENCE_OK else DoctorReason.PERSISTENCE_CHANGED)
        val sync = panes.any { it[3] == "1" || it[3] == "on" }
        row(DoctorCheck.INPUT, if (!ready || panes.isEmpty()) DoctorGrade.UNKNOWN else if (sync) DoctorGrade.WARNING else DoctorGrade.PASS,
            if (!ready || panes.isEmpty()) deferred else if (sync) DoctorReason.INPUT_SYNC else DoctorReason.INPUT_OK)
        row(DoctorCheck.DEPENDENCIES, if ("helpers.missing" !in facts) DoctorGrade.UNKNOWN else if (this@TmuxDoctorReport["helpers.missing"].isEmpty()) DoctorGrade.PASS else DoctorGrade.WARNING,
            if ("helpers.missing" !in facts) DoctorReason.NOT_CHECKED else if (this@TmuxDoctorReport["helpers.missing"].isEmpty()) DoctorReason.HELPERS_OK else DoctorReason.HELPERS_MISSING, this@TmuxDoctorReport["helpers.missing"])
        val utf8 = this@TmuxDoctorReport["locale"].lowercase().let { "utf-8" in it || "utf8" in it }
        row(DoctorCheck.DISPLAY, if ("locale" !in facts) DoctorGrade.UNKNOWN else if (utf8 && this@TmuxDoctorReport["terminfo"] == "yes") DoctorGrade.PASS else DoctorGrade.WARNING,
            if ("locale" !in facts) DoctorReason.NOT_CHECKED else if (utf8 && this@TmuxDoctorReport["terminfo"] == "yes") DoctorReason.DISPLAY_OK else DoctorReason.DISPLAY_CHECK, this@TmuxDoctorReport["locale"])
        val config = this@TmuxDoctorReport["config.status"]
        row(DoctorCheck.CONFIG, if (config == "exists") DoctorGrade.PASS else if (canInitialize) DoctorGrade.WARNING else DoctorGrade.UNKNOWN,
            when { config == "exists" -> DoctorReason.CONFIG_EXISTS; canInitialize -> DoctorReason.CONFIG_READY; else -> DoctorReason.CONFIG_UNAVAILABLE }, this@TmuxDoctorReport["home"])
    }
}

/** Uses shell byte framing, independent of tmux's n: modifier, so older versions can be diagnosed. */
object TmuxEnvironmentProbe {
    private const val PREFIX = "MTMUX_DOCTOR_1\n"
    fun command(path: String, detailed: Boolean = true): String {
        Tmux.version(path)
        return "sh -c " + Tmux.quote(script(path, detailed))
    }
    internal val safetyFunctions = """
        doctor_config_absent() {
            for doctor_file in "${'$'}doctor_home/.tmux.conf" "${'$'}doctor_home/.config/tmux/tmux.conf"; do
                [ ! -e "${'$'}doctor_file" ] && [ ! -L "${'$'}doctor_file" ] || return 1
            done
            for doctor_dir in "${'$'}doctor_home/.config" "${'$'}doctor_home/.config/tmux"; do
                if [ -e "${'$'}doctor_dir" ] || [ -L "${'$'}doctor_dir" ]; then
                    [ -d "${'$'}doctor_dir" ] && [ -x "${'$'}doctor_dir" ] && [ ! -L "${'$'}doctor_dir" ] || return 1
                fi
            done
            if [ -n "${'$'}{XDG_CONFIG_HOME-}" ]; then
                case "${'$'}XDG_CONFIG_HOME" in /*) ;; *) return 1 ;; esac
                [ ! -L "${'$'}XDG_CONFIG_HOME" ] || return 1
                [ ! -d "${'$'}XDG_CONFIG_HOME" ] || [ -x "${'$'}XDG_CONFIG_HOME" ] || return 1
                doctor_file="${'$'}XDG_CONFIG_HOME/tmux/tmux.conf"
                [ ! -e "${'$'}doctor_file" ] && [ ! -L "${'$'}doctor_file" ] || return 1
                doctor_dir="${'$'}XDG_CONFIG_HOME/tmux"
                if [ -e "${'$'}doctor_dir" ] || [ -L "${'$'}doctor_dir" ]; then
                    [ -d "${'$'}doctor_dir" ] && [ -x "${'$'}doctor_dir" ] && [ ! -L "${'$'}doctor_dir" ] || return 1
                fi
            fi
        }
        doctor_home_safe() {
            case "${'$'}doctor_home" in /*) ;; *) return 1 ;; esac
            [ -d "${'$'}doctor_home" ] && [ -w "${'$'}doctor_home" ] && [ -x "${'$'}doctor_home" ] || return 1
            doctor_owner=${'$'}(stat -c %u -- "${'$'}doctor_home" 2>/dev/null) || return 1
            doctor_uid=${'$'}(id -u) || return 1
            [ -n "${'$'}doctor_owner" ] && [ "${'$'}doctor_owner" = "${'$'}doctor_uid" ]
        }
        doctor_no_server() {
            doctor_error=${'$'}("${'$'}doctor_tmux" list-sessions -F '#{session_id}' 2>&1) && return 1
            case "${'$'}doctor_error" in
                *'no server running'*|*'error connecting'*'No such file or directory'*) return 0 ;;
                *) return 1 ;;
            esac
        }
    """.trimIndent()

    private fun script(path: String, detailed: Boolean) = buildString {
        append("doctor_locale=\${LC_ALL:-\${LC_CTYPE:-\${LANG-}}}; LC_ALL=C; export LC_ALL; set -f\n")
        append("emit() { printf '%s:%s:%s\\n' \"\$1\" \"\${#2}\" \"\$2\"; }\nprintf 'MTMUX_DOCTOR_1\\n'\n")
        append("doctor_tmux=${Tmux.quote(path)}\n")
        append("if [ \"\$doctor_tmux\" = tmux ]; then doctor_tmux=\$(command -v tmux 2>/dev/null) || doctor_tmux=; fi\n")
        append("emit path \"\$doctor_tmux\"\n")
        append("if [ -z \"\$doctor_tmux\" ] || [ ! -e \"\$doctor_tmux\" ]; then emit path.status missing; ")
        append("for candidate in /usr/bin/tmux /usr/local/bin/tmux /bin/tmux; do if [ -f \"\$candidate\" ] && [ -x \"\$candidate\" ]; then emit path.candidate \"\$candidate\"; break; fi; done\nprintf 'MTMUX_END\\n'; exit 0; fi\n")
        append("if [ ! -f \"\$doctor_tmux\" ] || [ ! -x \"\$doctor_tmux\" ]; then emit path.status not_executable; printf 'MTMUX_END\\n'; exit 0; fi\n")
        append("doctor_version=\$(\"\$doctor_tmux\" -V 2>/dev/null) || { emit path.status failed; printf 'MTMUX_END\\n'; exit 0; }\n")
        append("emit path.status ok; emit version.client \"\$doctor_version\"\n")
        append("doctor_sessions=\$(\"\$doctor_tmux\" list-sessions -F '#{session_id}' 2>&1); doctor_status=\$?\n")
        append("if [ \"\$doctor_status\" = 0 ]; then emit server.status ready\n")
        append("emit version.server \"\$(\"\$doctor_tmux\" display-message -p '#{version}' 2>/dev/null)\"\n")
        append("emit server.frame \"\$(\"\$doctor_tmux\" display-message -p '#{n:#{pid}}:#{pid}' 2>/dev/null)\"\n")
        append("emit server.identity \"\$(\"\$doctor_tmux\" display-message -p '#{pid}:#{start_time}' 2>/dev/null)\"\n")
        append("else case \"\$doctor_sessions\" in *'no server running'*|*'error connecting'*'No such file or directory'*) emit server.status none ;; *) emit server.status error ;; esac; fi\n")
        if (!detailed) { append("printf 'MTMUX_END\\n'\n"); return@buildString }
        append(safetyFunctions).append('\n')
        append("emit locale \"\$doctor_locale\"; emit kernel \"\$(uname -s 2>/dev/null)\"\n")
        append("if [ \"\$(head -c 4 \"\$doctor_tmux\" 2>/dev/null)\" = \"\$(printf '\\177ELF')\" ]; then emit config.loader standard; else emit config.loader unknown; fi\n")
        append("missing=; for helper in base64 head tail tr; do command -v \"\$helper\" >/dev/null 2>&1 || missing=\"\$missing \$helper\"; done; emit helpers.missing \"\$missing\"\n")
        append("if command -v infocmp >/dev/null 2>&1 && infocmp xterm-256color >/dev/null 2>&1; then emit terminfo yes; else emit terminfo unknown; fi\n")
        append("doctor_home=\${HOME-}; emit home \"\$doctor_home\"; emit uid \"\$(id -u 2>/dev/null)\"\n")
        append("if doctor_home_safe; then emit home.safe yes; else emit home.safe no; fi\n")
        append("if ! doctor_home_safe; then emit config.status unknown; elif doctor_config_absent; then emit config.status absent; else emit config.status exists; fi\n")
        append("bootstrap=yes; for helper in mktemp ln stat sha256sum id mv rm; do command -v \"\$helper\" >/dev/null 2>&1 || bootstrap=no; done\n")
        append("case \"\$(ln --help 2>&1)\" in *-T*) ;; *) bootstrap=no ;; esac; emit bootstrap.helpers \"\$bootstrap\"\n")
        append("if [ \"\$doctor_status\" = 0 ]; then\n")
        listOf("mouse" to "show-options -g -v mouse", "history" to "show-options -g -v history-limit", "destroy_unattached" to "show-options -g -v destroy-unattached", "exit_unattached" to "show-options -s -v exit-unattached").forEach { (key, cmd) ->
            append("emit $key \"\$(\"\$doctor_tmux\" $cmd 2>/dev/null)\"\n")
        }
        append("doctor_count=0; for sid in \$doctor_sessions; do case \"\$sid\" in \\$[0-9]*) ;; *) continue ;; esac; [ \$doctor_count -lt 16 ] || break\n")
        listOf("mouse" to "mouse", "history" to "history-limit", "destroy" to "destroy-unattached").forEach { (key, option) ->
            append("emit session.\$doctor_count.$key \"\$(\"\$doctor_tmux\" show-options -A -v -t \"\$sid\" $option 2>/dev/null)\"\n")
        }
        append("doctor_count=\$((doctor_count+1)); done; emit sessions.sampled \"\$doctor_count\"\n")
        append("\"\$doctor_tmux\" list-panes -a -F '#{pane_id}:#{history_limit}:#{history_size}:#{synchronize-panes}:#{alternate_on}:#{mouse_any_flag}' 2>/dev/null | { doctor_count=0; while IFS= read -r line; do [ \$doctor_count -lt 32 ] || break; emit pane.\$doctor_count \"\$line\"; doctor_count=\$((doctor_count+1)); done; }\n")
        append("""
            binding() {
                doctor_binding=missing; doctor_step=
                while IFS= read -r line; do case "${'$'}line" in *" ${'$'}2 "*)
                    doctor_binding=custom; payload=${'$'}{line#*"${'$'}2"}
                    if [ "${'$'}1" = root ]; then
                        case "${'$'}payload" in *if-shell*mouse_any_flag*copy-mode*) doctor_binding=standard ;; esac
                    else
                        valid=yes; has_send=no; has_x=no; has_action=no; next=no
                        for word in ${'$'}payload; do
                            if [ "${'$'}next" = yes ]; then
                                case "${'$'}word" in *[!0-9]*|'') valid=no ;; *) doctor_step=${'$'}word ;; esac
                                next=no; continue
                            fi
                            case "${'$'}word" in
                                send-keys|send) has_send=yes ;; -X) has_x=yes ;; "${'$'}3") has_action=yes ;;
                                select-pane|'\;'|-t|-t=|=) ;; -N) next=yes ;;
                                -N[0-9]*) candidate=${'$'}{word#-N}; case "${'$'}candidate" in *[!0-9]*) valid=no ;; *) doctor_step=${'$'}candidate ;; esac ;;
                                *) valid=no ;;
                            esac
                        done
                        if [ "${'$'}valid:${'$'}has_send:${'$'}has_x:${'$'}has_action:${'$'}next" = yes:yes:yes:yes:no ]; then doctor_binding=standard; fi
                    fi
                    [ "${'$'}doctor_binding" = standard ] || doctor_step=
                    ;; esac
                done
                emit binding.${'$'}4 "${'$'}doctor_binding"; emit step.${'$'}4 "${'$'}doctor_step"
            }
        """.trimIndent()).append('\n')
        listOf(Triple("root", "up", ""), Triple("root", "down", ""), Triple("copy-mode", "up", "scroll-up"), Triple("copy-mode", "down", "scroll-down"), Triple("copy-mode-vi", "up", "scroll-up"), Triple("copy-mode-vi", "down", "scroll-down")).forEach { (table, direction, action) ->
            val id = when (table) {"copy-mode" -> "copy"; "copy-mode-vi" -> "vi"; else -> "root"}
            append("\"\$doctor_tmux\" list-keys -T $table 2>/dev/null | binding $table Wheel${if (direction == "up") "Up" else "Down"}Pane ${Tmux.quote(action)} $id.$direction\n")
        }
        append("fi\nprintf 'MTMUX_END\\n'\n")
    }

    fun parse(result: ExecResult): TmuxDoctorReport {
        require(result.status == 0) { "doctor-command-failed" }
        val bytes = result.output.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 128 * 1024 && result.output.startsWith(PREFIX)) { "doctor-protocol-invalid" }
        var position = PREFIX.length
        val facts = linkedMapOf<String, String>()
        while (position < bytes.size) {
            if (String(bytes, position, bytes.size - position, Charsets.UTF_8) == "MTMUX_END\n") return TmuxDoctorReport(facts)
            val start = position
            while (position < bytes.size && bytes[position] != ':'.code.toByte()) position++
            require(position - start in 1..64 && position < bytes.size) { "doctor-key-invalid" }
            val key = String(bytes, start, position - start, Charsets.US_ASCII)
            require(key.matches(Regex("[a-z0-9_.]+")) && key !in facts && facts.size < 256) { "doctor-key-invalid" }
            val lengthStart = ++position
            while (position < bytes.size && bytes[position] in 48..57) position++
            require(position - lengthStart in 1..5 && position < bytes.size && bytes[position] == ':'.code.toByte()) { "doctor-length-invalid" }
            val length = String(bytes, lengthStart, position - lengthStart, Charsets.US_ASCII).toInt()
            position++
            require(length <= 65536 && length < bytes.size - position) { "doctor-length-invalid" }
            facts[key] = String(bytes, position, length, Charsets.UTF_8)
            position += length
            require(bytes[position++] == '\n'.code.toByte()) { "doctor-record-invalid" }
        }
        error("doctor-end-missing")
    }
}
