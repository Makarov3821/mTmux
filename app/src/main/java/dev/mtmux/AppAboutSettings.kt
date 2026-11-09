package dev.mtmux

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable internal fun AppAboutSettings() {
    val context = LocalContext.current
    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }
    BentoCard(title = stringResource(R.string.settings_about)) {
        SettingRow(stringResource(R.string.settings_version), icon = UiSymbol.INFO) {
            Text(BuildConfig.VERSION_NAME, Modifier.testTag("app-version"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingRow(stringResource(R.string.settings_install_source), icon = UiSymbol.LATEST) {
            Text(if (BuildConfig.DISTRIBUTION_CHANNEL == "play") "Google Play" else "GitHub",
                Modifier.testTag("app-install-source"), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingRow(stringResource(R.string.settings_project_feedback)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIconButton(UiSymbol.LINK, stringResource(R.string.settings_project),
                    { open("https://github.com/Makarov3821/mTmux") },
                    Modifier.testTag("app-project-link"), tone = IconTone.PLAIN, size = 48.dp, iconSize = 20.dp)
                AppIconButton(UiSymbol.ALERT_CIRCLE, stringResource(R.string.settings_issues),
                    { open("https://github.com/Makarov3821/mTmux/issues") },
                    Modifier.testTag("app-issues-link"), tone = IconTone.PLAIN, size = 48.dp, iconSize = 20.dp)
            }
        }
    }
}
