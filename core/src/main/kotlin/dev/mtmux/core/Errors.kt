package dev.mtmux.core

/**
 * Stable, non-sensitive failure codes. The Android layer maps them to localized text;
 * diagnostics log only [name]. Never attach library messages, hosts or credentials.
 */
enum class ErrorCode {
    INVALID_LOGIN, INVALID_JUMP_CHAIN, CANCELLED, NOT_CONNECTED, REMOTE_TIMEOUT, REMOTE_OUTPUT_LIMIT,
    TMUX_UNAVAILABLE, TMUX_DISCOVERY_FAILED, TOO_MANY_PANES, TMUX_TARGET_CHANGED, TMUX_METADATA_INVALID,
    TMUX_IDENTITY_INVALID, TMUX_SESSION_CHANGED, TMUX_PATH_INVALID, TERMINAL_NOT_CONNECTED, BIND_FAILED,
    TASK_GONE, CONNECTION_CHANGED, SEND_UNCERTAIN, TERMINAL_CLOSED, STATUS_READ_FAILED, STATUS_PARSE_FAILED,
    PRIVATE_KEY_TOO_LARGE, PRIVATE_KEY_INVALID
}

interface CodedError { val code: ErrorCode }

/** Runtime/remote state problem (formerly `check`). */
class MtmuxException(override val code: ErrorCode, cause: Throwable? = null) :
    IllegalStateException(code.name, cause), CodedError

/** Invalid input or unparseable data (formerly `require`). */
class InvalidInput(override val code: ErrorCode) : IllegalArgumentException(code.name), CodedError

internal fun ensure(value: Boolean, code: ErrorCode) { if (!value) throw MtmuxException(code) }
internal fun requireValid(value: Boolean, code: ErrorCode) { if (!value) throw InvalidInput(code) }
internal fun fail(code: ErrorCode): Nothing = throw MtmuxException(code)

/** Connection progress for one hop. [hop] is 0 for the target server, otherwise the 1-based jump index. */
data class ConnectProgress(val hop: Int, val stage: Stage) {
    enum class Stage { PREPARING, AUTHENTICATING }
}
