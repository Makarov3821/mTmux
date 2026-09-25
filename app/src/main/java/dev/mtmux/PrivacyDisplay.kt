package dev.mtmux

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

class PrivacyDisplay(context: Context) {
    private val preferences = context.getSharedPreferences("privacy-display", Context.MODE_PRIVATE)
    fun enabled() = preferences.getBoolean("enabled", true)
    fun enabled(value: Boolean) { preferences.edit().putBoolean("enabled", value).apply() }
    fun label(profile: ServerProfile, enabled: Boolean): String {
        val alias = profile.explicitAlias()
        if (!enabled) return alias.takeIf { it.isNotBlank() }?.let { "$it · ${profile.addressLabel()}" } ?: profile.addressLabel()
        if (alias.isNotBlank()) return alias
        val key = "number-${profile.id}"
        var number = preferences.getInt(key, 0)
        if (number == 0) {
            number = preferences.getInt("next-number", 1)
            preferences.edit().putInt(key, number).putInt("next-number", number + 1).apply()
        }
        return "服务器 $number"
    }
}

// Older versions saved the address as the name when the alias field was empty.
// Treat that legacy generated name as unnamed without changing stored credentials.
fun ServerProfile.explicitAlias() = name.takeUnless { it == "$user@$host" || it == addressLabel() }.orEmpty()
fun ServerProfile.addressLabel() = "$user@$host" + if (port != 22) ":$port" else ""
val protectedDialogProperties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

private fun Context.mainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.mainActivity()
    else -> null
}

/** Protect both the sensitive overlay and the underlying Activity, including nested dialogs. */
@Composable fun ProtectSensitiveContent() {
    val activity = LocalContext.current.mainActivity()
    val owner = remember { Any() }
    DisposableEffect(activity, owner) {
        activity?.protectCapture(owner, true)
        onDispose { activity?.protectCapture(owner, false) }
    }
}
