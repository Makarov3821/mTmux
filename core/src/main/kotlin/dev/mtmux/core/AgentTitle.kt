package dev.mtmux.core

/** Adapted from Orca's OpenCode title recognition (MIT; see THIRD_PARTY_NOTICES.md).
 * Read pane_title, never the user-assigned session/window name. No name-only completion.
 */
object AgentTitle {
    private val openCode = Regex("^\\s*(?:(?![▣\\u2800-\\u28ff])[^|]+? \\| )?(?:[▣\\u2800-\\u28ff] )?OC \\|[ \\t]+\\S")
    private val spinner = Regex("[\\u2800-\\u28ff\\u25d0-\\u25d3]")
    private val owner = Regex("(?i)^(?:[\\u2800-\\u28ff\\u25d0-\\u25d3]\\s+)?(?:codex|kiro(?:-cli)?)(?:\\s|$)")
    private val attention = Regex("(?i)\\b(?:action required|permission|waiting|approval|error)\\b")
    private val idle = Regex("(?i)(?<![\\w./\\\\-])(?:ready|idle|done)(?![\\w-])")
    private val working = Regex("(?i)(?<![\\w./\\\\-])(?:working|thinking|running)(?![\\w-])")
    fun isOpenCode(title: String) = openCode.containsMatchIn(title)
    fun inspect(title: String): TaskState {
        if (isOpenCode(title)) {
            // Only the envelope's decoration is state; task names may contain arbitrary glyphs/words.
            val envelope = title.substringBefore("OC |")
            return if (spinner.containsMatchIn(envelope)) TaskState.RUNNING else TaskState.COMPLETED
        }
        if (!owner.containsMatchIn(title)) return TaskState.UNKNOWN
        if (attention.containsMatchIn(title)) return TaskState.ATTENTION
        if (spinner.containsMatchIn(title.substringBefore(' ')) || working.containsMatchIn(title)) return TaskState.RUNNING
        if (idle.containsMatchIn(title)) return TaskState.COMPLETED
        return TaskState.UNKNOWN
    }
}
