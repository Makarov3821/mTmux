package dev.mtmux

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

private const val VOICE_SEND_KEY = "voiceSendWithEnter"

internal fun savedVoiceSendWithEnter(context: Context) =
    context.getSharedPreferences("terminal-settings", 0).getBoolean(VOICE_SEND_KEY, true)

@Composable internal fun rememberVoiceSendWithEnter(): Boolean {
    if (BuildConfig.FLAVOR != "voice") return false
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("terminal-settings", 0) }
    var enabled by remember(preferences) { mutableStateOf(preferences.getBoolean(VOICE_SEND_KEY, true)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == VOICE_SEND_KEY) enabled = preferences.getBoolean(VOICE_SEND_KEY, true)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

@Composable internal fun VoiceSendSettings() {
    if (BuildConfig.FLAVOR != "voice") return
    val context = LocalContext.current
    val enabled = rememberVoiceSendWithEnter()
    BentoCard(title = stringResource(R.string.voice_title)) {
        SettingRow(stringResource(R.string.voice_send_direct), icon = UiSymbol.MIC) {
            Switch(enabled, { value ->
                context.getSharedPreferences("terminal-settings", 0).edit().putBoolean(VOICE_SEND_KEY, value).apply()
            }, modifier = Modifier.testTag("voice-send-direct"))
        }
    }
}
