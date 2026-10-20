package dev.mtmux

import kotlin.math.roundToInt

/** One persisted integer range shared by settings, tools and the WebView bridge. */
object ScrollSpeed {
    fun normalize(value: Float): Float = if (value.isFinite()) value.roundToInt().coerceIn(1, 10).toFloat() else 1f
}

fun savedScrollSpeed(preferences: android.content.SharedPreferences): Float {
    val old = preferences.getFloat("scrollSensitivity",1f)
    return ScrollSpeed.normalize(old).also { value ->
        if (value != old) preferences.edit().putFloat("scrollSensitivity",value).apply()
    }
}
