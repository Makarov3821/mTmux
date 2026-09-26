package dev.mtmux

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat

/**
 * Per-app UI language. Stored by AndroidX (system per-app language on Android 13+,
 * app-private storage via `autoStoreLocales` on older versions). User content, remote
 * output and diagnostics are never translated.
 */
enum class AppLanguage(val tag: String, @StringRes val label: Int) {
    SYSTEM("", R.string.follow_system), CHINESE("zh-CN", R.string.language_chinese), ENGLISH("en", R.string.language_english);

    companion object {
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return SYSTEM
            return when (locales[0]?.language) { "zh" -> CHINESE; "en" -> ENGLISH; else -> SYSTEM }
        }

        @Volatile private var switching = false

        /** Changing the language recreates the Activity; the home page then skips its entry refresh. */
        fun select(language: AppLanguage) {
            if (language == current()) return
            switching = true
            AppCompatDelegate.setApplicationLocales(
                if (language == SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language.tag))
        }

        /** True once for the Activity recreated by [select]. */
        fun consumeRecreation(): Boolean = switching.also { switching = false }
    }
}

@Composable fun LanguageSettings() {
    val selected = AppLanguage.current()
    Text(stringResource(R.string.settings_language), color = MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.entries.forEach { option ->
            FilterChip(selected = selected == option, onClick = { AppLanguage.select(option) },
                label = { Text(stringResource(option.label)) }, modifier = Modifier.testTag("language-${option.name}"))
        }
    }
}
