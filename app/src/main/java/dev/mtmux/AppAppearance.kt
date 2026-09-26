package dev.mtmux

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color

enum class AppAppearance(@androidx.annotation.StringRes val label: Int) { SYSTEM(R.string.follow_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark);
    fun dark(systemDark: Boolean) = this == DARK || (this == SYSTEM && systemDark)
    companion object { fun read(value: String?) = entries.firstOrNull { it.name == value } ?: DARK }
}
fun appColors(dark: Boolean) = if(dark) darkColorScheme(
    primary=Color(0xFF79D5B0),onPrimary=Color(0xFF103B2C),primaryContainer=Color(0xFF214B3A),onPrimaryContainer=Color(0xFFD7F5E5),
    secondaryContainer=Color(0xFF214B3A),onSecondaryContainer=Color(0xFFD7F5E5),background=Color(0xFF111916),surface=Color(0xFF111916),
    surfaceVariant=Color(0xFF19231E),onSurface=Color(0xFFE9EFEC),onBackground=Color(0xFFE9EFEC),onSurfaceVariant=Color(0xFF9CAEA3),outlineVariant=Color(0xFF29342E)
) else lightColorScheme(
    primary=Color(0xFF176B4D),onPrimary=Color.White,primaryContainer=Color(0xFFCCEEDF),onPrimaryContainer=Color(0xFF103B2C),
    secondary=Color(0xFF426454),onSecondary=Color.White,secondaryContainer=Color(0xFFE0EEE6),onSecondaryContainer=Color(0xFF173D2B),
    background=Color(0xFFF7FAF8),onBackground=Color(0xFF18251E),surface=Color(0xFFF7FAF8),onSurface=Color(0xFF18251E),
    surfaceVariant=Color(0xFFE8F0EB),onSurfaceVariant=Color(0xFF46594D),outline=Color(0xFF6C7E72),outlineVariant=Color(0xFFCBD7CF),
    error=Color(0xFFB3261E),onError=Color.White,errorContainer=Color(0xFFF9DEDC),onErrorContainer=Color(0xFF410E0B)
)
