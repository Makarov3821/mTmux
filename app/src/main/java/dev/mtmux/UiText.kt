package dev.mtmux

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.mtmux.core.*

/**
 * Text that is resolved against the current locale at display time. State holders keep
 * UiText rather than formatted strings so a language change never leaves stale text.
 */
sealed interface UiText {
    fun resolve(context: Context): String

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(context: Context) =
            if (args.isEmpty()) context.getString(id) else context.getString(id, *args.map { it.resolveArg(context) }.toTypedArray())
    }
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText {
        override fun resolve(context: Context) =
            context.resources.getQuantityString(id, count, *args.map { it.resolveArg(context) }.toTypedArray())
    }
    /** User or remote content (names, paths); never translated. */
    data class Raw(val value: String) : UiText { override fun resolve(context: Context) = value }
    data class Joined(val parts: List<UiText>) : UiText {
        override fun resolve(context: Context) = parts.joinToString("") { it.resolve(context) }
    }
    operator fun plus(other: UiText): UiText = Joined(listOf(this, other))
}

private fun Any.resolveArg(context: Context): Any = if (this is UiText) resolve(context) else this

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

@Composable @ReadOnlyComposable
fun UiText.string(): String {
    LocalConfiguration.current // recompose when the configuration (locale) changes
    return resolve(LocalContext.current)
}

/** App-layer failure with a fixed, localized message. */
class AppError(@StringRes val res: Int) : IllegalStateException("app-error")

fun hopText(hop: Int): UiText = if (hop == 0) uiText(R.string.hop_target) else uiText(R.string.hop_jump, hop)

fun progressText(progress: ConnectProgress): UiText = uiText(when (progress.stage) {
    ConnectProgress.Stage.PREPARING -> R.string.progress_preparing
    ConnectProgress.Stage.AUTHENTICATING -> R.string.progress_authenticating
}, hopText(progress.hop))

@StringRes fun FailureReason.text(): Int = when (this) {
    FailureReason.UNKNOWN_HOST -> R.string.reason_unknown_host
    FailureReason.TIMEOUT -> R.string.reason_timeout
    FailureReason.AUTH -> R.string.reason_auth
    FailureReason.PRIVATE_KEY -> R.string.reason_private_key
    FailureReason.REFUSED -> R.string.reason_refused
    FailureReason.UNREACHABLE -> R.string.reason_unreachable
    FailureReason.FORWARDING -> R.string.reason_forwarding
    FailureReason.ALGORITHM -> R.string.reason_algorithm
    FailureReason.CONNECT -> R.string.reason_connect
    FailureReason.OTHER -> R.string.reason_other
}

@StringRes fun ErrorCode.text(): Int = when (this) {
    ErrorCode.INVALID_LOGIN -> R.string.err_invalid_login
    ErrorCode.INVALID_JUMP_CHAIN -> R.string.err_invalid_jump_chain
    ErrorCode.CANCELLED -> R.string.err_cancelled
    ErrorCode.NOT_CONNECTED -> R.string.err_not_connected
    ErrorCode.REMOTE_TIMEOUT -> R.string.err_remote_timeout
    ErrorCode.REMOTE_OUTPUT_LIMIT -> R.string.err_remote_output_limit
    ErrorCode.TMUX_UNAVAILABLE -> R.string.err_tmux_unavailable
    ErrorCode.TMUX_DISCOVERY_FAILED -> R.string.err_tmux_discovery_failed
    ErrorCode.TOO_MANY_PANES -> R.string.err_too_many_panes
    ErrorCode.TMUX_TARGET_CHANGED -> R.string.err_tmux_target_changed
    ErrorCode.TMUX_METADATA_INVALID -> R.string.err_tmux_metadata_invalid
    ErrorCode.TMUX_IDENTITY_INVALID -> R.string.err_tmux_identity_invalid
    ErrorCode.TMUX_SESSION_CHANGED -> R.string.err_tmux_session_changed
    ErrorCode.TMUX_PATH_INVALID -> R.string.err_tmux_path_invalid
    ErrorCode.TERMINAL_NOT_CONNECTED -> R.string.err_terminal_not_connected
    ErrorCode.BIND_FAILED -> R.string.err_bind_failed
    ErrorCode.TASK_GONE -> R.string.err_task_gone
    ErrorCode.CONNECTION_CHANGED -> R.string.err_connection_changed
    ErrorCode.SEND_UNCERTAIN -> R.string.err_send_uncertain
    ErrorCode.TERMINAL_CLOSED -> R.string.err_terminal_closed
    ErrorCode.STATUS_READ_FAILED -> R.string.err_status_read_failed
    ErrorCode.STATUS_PARSE_FAILED -> R.string.err_status_parse_failed
    ErrorCode.PRIVATE_KEY_TOO_LARGE -> R.string.err_private_key_too_large
    ErrorCode.PRIVATE_KEY_INVALID -> R.string.err_private_key_invalid
}

/** Never display arbitrary exception text: it can contain private paths or data. */
fun errorText(error: Throwable): UiText = when (error) {
    is ConnectionFailure -> uiText(R.string.err_connection_failure, hopText(error.hop), uiText(error.reason.text()))
    is CodedError -> uiText(error.code.text())
    is AppError -> uiText(error.res)
    is IllegalStateException, is IllegalArgumentException -> uiText(R.string.err_verification_failed)
    else -> uiText(connectionFailureReason(error).text())
}

/** Stable, English, non-sensitive name for diagnostics. */
fun diagnosticReason(error: Throwable): String = when (error) {
    is ConnectionFailure -> "${error.reason}@hop${error.hop}"
    is CodedError -> error.code.name
    is AppError -> "APP_ERROR"
    else -> connectionFailureReason(error).name
}

@StringRes fun TaskState.text(): Int = when (this) {
    TaskState.UNKNOWN -> R.string.task_state_unknown
    TaskState.ATTENTION -> R.string.task_state_attention
    TaskState.RUNNING -> R.string.task_state_running
    TaskState.COMPLETED -> R.string.task_state_completed
}
