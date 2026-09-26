package dev.mtmux.core

/** Display text lives in the Android resources. */
enum class TaskState { UNKNOWN, ATTENTION, RUNNING, COMPLETED }

/** A snapshot hint, not Agent telemetry. Silence and an idle shell are never completion. */
object TaskStates {
    private val confirmation=Regex("(?i)(do you (?:want|wish) to (?:proceed|continue|allow)|would you like to (?:proceed|continue|allow)|allow (?:this|once|always)|approve (?:this|once)|是否(?:允许|继续|执行|确认)|确认(?:执行|继续)|\\[y/n\\]|\\(y/n\\))")
    private val error=Regex("(?i)(^(?:error|fatal|错误|失败)\\s*[:：]|rate limit (?:exceeded|reached)|insufficient (?:quota|capacity)|capacity (?:exceeded|unavailable)|authentication failed)")
    private val running=Regex("(?i)(^(?:thinking|working|running|processing|正在思考|正在运行|正在处理)(?:\\s*[.…]|\\s+for\\s+\\d)|(?:esc|ctrl[+-]c) to (?:interrupt|cancel|stop))")
    private val completed=Regex("(?i)^(?:task (?:completed|finished)(?: successfully)?[.!]?|任务(?:已)?(?:成功)?完成[。！!]?|(?:done in|worked for) \\d.*)$")
    fun inspect(text: String, command: String = "", title: String = ""): TaskState {
        val raw = text.takeLast(24576).replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
        val footer = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList().takeLast(32).joinToString("\n")
        val compact = footer.replace(Regex("\\s+"), " ")
        val kiro = command.contains("kiro", true) || Regex("(?i)kiro[_ -]|ask a question or describe a task").containsMatchIn(compact)
        val opencode = command.contains("opencode", true) || AgentTitle.isOpenCode(title) ||
            ("▣" in footer && Regex("(?i)ctrl[+]p").containsMatchIn(compact))
        val shell = command.substringAfterLast('/') in setOf("bash", "zsh", "sh", "fish", "dash", "ksh")
        val titleState = if (shell) TaskState.UNKNOWN else AgentTitle.inspect(title)
        // Codex's empty composer is also visible while working. Require its model footer,
        // and let current approval/busy/error controls veto the idle inference.
        val codexPrompt = Regex("(?im)^\\s*›\\s*Ask Codex to do anything\\s*$")
        val codexModel = Regex("(?im)^\\s*(?:gpt[- ]\\d|o[134](?:[- .]|$))[^\n]*[·•]")
        val codexTail = footer.lineSequence().toList().takeLast(8).joinToString("\n")
        val codexReady = codexPrompt.containsMatchIn(codexTail) && codexModel.containsMatchIn(codexTail)
        if (codexReady) {
            val controls = footer.lineSequence().toList().takeLast(16)
            if (controls.any { confirmation.containsMatchIn(it) || error.containsMatchIn(it) } ||
                Regex("(?i)permission required|approval required|press enter to confirm").containsMatchIn(controls.joinToString(" "))) return TaskState.ATTENTION
            if (controls.any { running.containsMatchIn(it.trimStart('•','●',' ').trim()) } ||
                Regex("(?i)(?:esc|ctrl[+-]c)\\s+(?:(?:again\\s+)?to\\s+)?(?:interrupt|cancel|stop)|tab\\s+to queue").containsMatchIn(controls.joinToString(" "))) return TaskState.RUNNING
            if (titleState == TaskState.ATTENTION || titleState == TaskState.RUNNING) return titleState
            if (!shell) return TaskState.COMPLETED
        }
        // Current input/footer controls outrank a completed message higher on the screen.
        if (kiro || opencode) {
            if (Regex("(?i)permission required|allow once|allow always|yes, single permission|trust, (?:always allow|allow all)|awaiting (?:your )?approval|goal paused").containsMatchIn(compact)) return TaskState.ATTENTION
            if (Regex("(?i)esc\\s+(?:(?:again\\s+)?to\\s+)?interrupt|kiro is working|type to (?:queue|steer)|running shell command|goal active:").containsMatchIn(compact)) return TaskState.RUNNING
            if (titleState == TaskState.ATTENTION || titleState == TaskState.RUNNING) return titleState
            if (kiro && Regex("(?i)ask a question or describe a task").containsMatchIn(compact)) {
                // The placeholder is only rendered when Kiro is no longer processing/awaiting approval.
                return if (confirmation.containsMatchIn(footer) || footer.lineSequence().any { error.containsMatchIn(it) }) TaskState.ATTENTION else TaskState.COMPLETED
            }
            if (opencode && Regex("▣[^\n]*[·•]\\s*\\d+(?:\\.\\d+)?(?:s|m|h)\\b").containsMatchIn(footer)) {
                return if (confirmation.containsMatchIn(footer) || footer.lineSequence().any { error.containsMatchIn(it) }) TaskState.ATTENTION else TaskState.COMPLETED
            }
        }
        if (titleState == TaskState.RUNNING || titleState == TaskState.ATTENTION) return titleState
        val lines=raw.takeLast(12000).lineSequence().map { it.trim().trimStart('•','●','✦','✗','❯','>').trim() }
            .filter {it.isNotBlank()}.toList().takeLast(12)
        // The most recent explicit cue wins; do not keep an old error after completion.
        for(line in lines.asReversed()) {
            if(line.startsWith("$") || line.startsWith("#") || line.startsWith("```") || line.startsWith("\"")) continue
            if(confirmation.containsMatchIn(line) || error.containsMatchIn(line)) return TaskState.ATTENTION
            if(completed.matches(line)) return TaskState.COMPLETED
            if(running.containsMatchIn(line)) return TaskState.RUNNING
        }
        return titleState
    }
    fun aggregate(states: List<TaskState>): TaskState = when {
        states.isEmpty() -> TaskState.UNKNOWN
        TaskState.ATTENTION in states -> TaskState.ATTENTION
        TaskState.RUNNING in states -> TaskState.RUNNING
        TaskState.UNKNOWN in states -> TaskState.UNKNOWN
        else -> TaskState.COMPLETED
    }
}
