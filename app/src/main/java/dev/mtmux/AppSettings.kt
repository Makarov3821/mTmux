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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.mtmux.core.SshClient
import dev.mtmux.core.WarmConnectionPool

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AppSettings(onBack: () -> Unit, pool: WarmConnectionPool<SshClient>,
    appearance: AppAppearance, onAppearance: (AppAppearance) -> Unit, palette: AppPalette, onPalette: (AppPalette) -> Unit,
    privacy: Boolean, onPrivacy: (Boolean) -> Unit, font: Int, onFont: (Int) -> Unit,
    speed: Float, onSpeed: (Float) -> Unit, touch: Boolean, onTouch: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=12.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            AppIconButton(UiSymbol.BACK,stringResource(R.string.common_back),onBack,Modifier.testTag("settings-back"),tone=IconTone.PLAIN,iconSize=22.dp)
            Text(stringResource(R.string.settings_title),style=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.Bold))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=4.dp)) {
            BentoCard(title=stringResource(R.string.settings_appearance)) {
                SegmentedChoice(AppAppearance.entries,appearance,{stringResource(it.label)},{"theme-${it.name}"},onAppearance)
                PaletteSettings(palette,onPalette)
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                LanguageSettings()
            }
            BentoCard(title=stringResource(R.string.settings_connection_terminal)) {
                SshRetentionSettings(pool)
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                SettingRow(stringResource(R.string.settings_font_size),icon=UiSymbol.TERMINAL) {
                    Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(10.dp)),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={onFont((font-1).coerceAtLeast(8))},contentPadding=PaddingValues(0.dp),modifier=Modifier.size(40.dp)) { Text("−",style=MaterialTheme.typography.titleMedium) }
                        Text(font.toString(),Modifier.widthIn(min=24.dp),style=MaterialTheme.typography.titleSmall.copy(fontFamily=FontFamily.Monospace),textAlign=TextAlign.Center)
                        TextButton(onClick={onFont((font+1).coerceAtMost(28))},contentPadding=PaddingValues(0.dp),modifier=Modifier.size(40.dp)) { Text("+",style=MaterialTheme.typography.titleMedium) }
                    }
                }
                SettingRow(stringResource(R.string.settings_touch),icon=UiSymbol.HAND,help=stringResource(R.string.settings_touch_help)) { Switch(touch,onTouch) }
                ScrollSpeedControl(speed,onSpeed,"settings-speed")
            }
            BentoCard(title=stringResource(R.string.settings_privacy)) {
                SettingRow(stringResource(R.string.settings_privacy_display),icon=UiSymbol.EYE) { Switch(privacy,onPrivacy,modifier=Modifier.testTag("privacy-display")) }
                Text(stringResource(R.string.settings_privacy_help),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                VoiceSettings()
            }
            BentoCard(title=stringResource(R.string.log_title)) { DebugLogSettings() }
            Spacer(Modifier.height(24.dp))
        }
    }
}
