package dev.mtmux

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mtmux.core.SshClient
import dev.mtmux.core.WarmConnectionPool

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AppSettings(onBack: () -> Unit, pool: WarmConnectionPool<SshClient>,
    appearance: AppAppearance, onAppearance: (AppAppearance) -> Unit, palette: AppPalette, onPalette: (AppPalette) -> Unit,
    privacy: Boolean, onPrivacy: (Boolean) -> Unit, font: Int, onFont: (Int) -> Unit,
    speed: Float, onSpeed: (Float) -> Unit, touch: Boolean, onTouch: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=16.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onBack) { Text(stringResource(R.string.common_back)) }
            Text(stringResource(R.string.settings_title),style=MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            BentoCard(title=stringResource(R.string.settings_appearance_language)) {
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    AppAppearance.entries.forEach { option -> FilterChip(selected=appearance==option,onClick={onAppearance(option)},
                        label={Text(stringResource(option.label),style=MaterialTheme.typography.labelMedium)},modifier=Modifier.testTag("theme-${option.name}")) }
                }
                PaletteSettings(palette,onPalette)
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                LanguageSettings()
            }
            BentoCard { SshRetentionSettings(pool) }
            BentoCard(title=stringResource(R.string.settings_terminal_display)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_font_size),Modifier.weight(1f))
                    TextButton(onClick={onFont((font-1).coerceAtLeast(8))}) { Text("−") }
                    Text(font.toString(),style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick={onFont((font+1).coerceAtMost(28))}) { Text("＋") }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_touch),Modifier.weight(1f))
                    Switch(touch,onTouch)
                }
                Text(stringResource(R.string.settings_touch_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                ScrollSpeedControl(speed,onSpeed,"settings-speed")
            }
            BentoCard(title=stringResource(R.string.settings_privacy)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_privacy_display),Modifier.weight(1f))
                    Switch(privacy,onPrivacy,modifier=Modifier.testTag("privacy-display"))
                }
                Text(stringResource(R.string.settings_privacy_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                VoiceSettings()
            }
            BentoCard { DebugLogSettings() }
            Spacer(Modifier.height(16.dp))
        }
    }
}
