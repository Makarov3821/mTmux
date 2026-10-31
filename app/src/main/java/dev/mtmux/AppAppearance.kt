package dev.mtmux

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

enum class AppAppearance(@androidx.annotation.StringRes val label: Int) { SYSTEM(R.string.follow_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark);
    fun dark(systemDark: Boolean) = this == DARK || (this == SYSTEM && systemDark)
    companion object { fun read(value: String?) = entries.firstOrNull { it.name == value } ?: DARK }
}

enum class AppPalette(val label: String) {
    TOKYO("Tokyo Night"), CATPPUCCIN("Catppuccin"), EMERALD("Emerald"), ZINC("Zinc"), NORD("Nord"), PAPER("Paper");
    companion object {
        fun read(value: String?) = entries.firstOrNull { it.name == value }
        fun saved(context: Context): AppPalette {
            val prefs = context.getSharedPreferences("terminal-settings", 0)
            read(prefs.getString("palette", null))?.let { return it }
            val existing = prefs.all.isNotEmpty() || context.getSharedPreferences("server-profiles", 0).contains("profiles")
            return (if (existing) EMERALD else TOKYO).also { prefs.edit().putString("palette", it.name).apply() }
        }
    }
}

private data class PaletteColors(val bg: Long, val surface: Long, val raised: Long, val fg: Long, val muted: Long, val accent: Long)
private fun paletteColors(palette: AppPalette, dark: Boolean): PaletteColors = when (palette) {
    AppPalette.TOKYO -> if (dark) PaletteColors(0xFF13141C,0xFF1C1E2B,0xFF26293C,0xFFD5D9F8,0xFF9DA6CA,0xFF7AA2F7)
        else PaletteColors(0xFFF1F3FA,0xFFFFFFFF,0xFFE5EAF6,0xFF24283B,0xFF586581,0xFF345CAB)
    AppPalette.CATPPUCCIN -> if (dark) PaletteColors(0xFF11111B,0xFF1E1E2E,0xFF2A2B3D,0xFFCDD6F4,0xFFA6ADC8,0xFFCBA6F7)
        else PaletteColors(0xFFEFF1F5,0xFFFFFFFF,0xFFE6E9EF,0xFF4C4F69,0xFF626880,0xFF8839B9)
    AppPalette.EMERALD -> if (dark) PaletteColors(0xFF111916,0xFF17221C,0xFF213029,0xFFE9EFEC,0xFFA3B6AA,0xFF79D5B0)
        else PaletteColors(0xFFF7FAF8,0xFFFFFFFF,0xFFE8F0EB,0xFF18251E,0xFF46594D,0xFF176B4D)
    AppPalette.ZINC -> if (dark) PaletteColors(0xFF09090B,0xFF18181D,0xFF27272F,0xFFFAFAFA,0xFFA1A1AA,0xFFE4E4E7)
        else PaletteColors(0xFFF4F4F5,0xFFFFFFFF,0xFFE9E9ED,0xFF18181B,0xFF62626E,0xFF3F3F46)
    AppPalette.NORD -> if (dark) PaletteColors(0xFF1E222A,0xFF282E39,0xFF343C4A,0xFFECEFF4,0xFFB0BACC,0xFF88C0D0)
        else PaletteColors(0xFFECEFF4,0xFFFAFBFD,0xFFE0E6EF,0xFF2E3440,0xFF526079,0xFF356777)
    AppPalette.PAPER -> if (dark) PaletteColors(0xFF14171B,0xFF1D2228,0xFF2A323B,0xFFE6EBF0,0xFFA6B2BF,0xFF70CFC4)
        else PaletteColors(0xFFF2F4F7,0xFFFFFFFF,0xFFE8ECF2,0xFF0F172A,0xFF59677C,0xFF087E74)
}

fun appColors(dark: Boolean, palette: AppPalette = AppPalette.EMERALD): ColorScheme {
    val p = paletteColors(palette, dark)
    val bg = Color(p.bg); val surface = Color(p.surface); val raised = Color(p.raised)
    val fg = Color(p.fg); val muted = Color(p.muted); val accent = Color(p.accent)
    val border = if (dark) Color(p.accent).copy(alpha = .18f) else Color(p.fg).copy(alpha = .14f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(primary = accent, onPrimary = if (dark) bg else Color.White,
        primaryContainer = raised, onPrimaryContainer = fg, secondary = accent,
        onSecondary = if (dark) bg else Color.White, secondaryContainer = raised, onSecondaryContainer = fg,
        tertiary = accent, background = bg, onBackground = fg, surface = surface, onSurface = fg,
        surfaceVariant = raised, onSurfaceVariant = muted, outline = muted, outlineVariant = border,
        surfaceTint = accent, surfaceDim = bg, surfaceBright = raised,
        surfaceContainerLowest = bg, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        error = if (dark) Color(0xFFFF9AA8) else Color(0xFFB3261E),
        onError = if (dark) Color(0xFF3D1420) else Color.White,
        errorContainer = if (dark) Color(0xFF42242D) else Color(0xFFFFE9EC),
        onErrorContainer = if (dark) Color(0xFFFFC8CF) else Color(0xFF8B1730))
}

val appShapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(24.dp))
val appTypography = Typography().let { it.copy(
    headlineSmall = it.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = it.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = it.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = it.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = it.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = it.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = it.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp)) }

fun terminalTheme(dark: Boolean, palette: AppPalette): JSONObject {
    val colors = appColors(dark, palette)
    fun hex(color: Color) = "#%06x".format(java.util.Locale.ROOT, color.toArgb() and 0xFFFFFF)
    val ansi = if (dark) listOf("#26313d","#f38ba8","#9ece6a","#e0c277","#83adf2","#cba6f7","#7dcfd8","#d5d9e8",
        "#8792a6","#ffacb9","#b4ed91","#f5df9e","#adcaff","#e0bafa","#a1e5ec","#ffffff")
        else listOf("#263041","#af2525","#176b39","#795900","#245caf","#823f9b","#086b76","#586b70",
        "#637080","#a82d35","#206d37","#806000","#355eb0","#8c459a","#176875","#374350")
    return JSONObject().apply {
        put("background", hex(colors.background)); put("foreground", hex(colors.onBackground))
        put("cursor", hex(colors.primary)); put("cursorAccent", hex(colors.background))
        put("selectionBackground", hex(colors.primary.copy(alpha=.32f).compositeOver(colors.background))); put("selectionForeground", hex(colors.onSurface))
        val names = listOf("black","red","green","yellow","blue","magenta","cyan","white",
            "brightBlack","brightRed","brightGreen","brightYellow","brightBlue","brightMagenta","brightCyan","brightWhite")
        names.zip(ansi).forEach { (name, color) -> put(name, color) }
    }
}

@Composable fun PaletteSettings(selected: AppPalette, onSelect: (AppPalette) -> Unit) {
    SettingRow(stringResource(R.string.settings_palette), icon = UiSymbol.PALETTE)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        AppPalette.entries.forEach { palette ->
            val on = selected == palette
            val dark = appColors(true, palette)
            Column(Modifier.selectable(on, role = Role.RadioButton, onClick = { onSelect(palette) }).testTag("palette-${palette.name}").padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(40.dp).then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier).padding(if (on) 4.dp else 0.dp)
                    .background(Brush.linearGradient(0f to dark.surface, .5f to dark.surface, .5f to dark.primary, 1f to dark.primary), RoundedCornerShape(if (on) 10.dp else 12.dp)))
                Text(palette.label.substringBefore(' '), style = MaterialTheme.typography.labelSmall,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
