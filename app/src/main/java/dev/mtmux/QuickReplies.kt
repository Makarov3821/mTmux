package dev.mtmux

import android.content.Context
import org.json.JSONArray

class QuickReplies(context: Context) {
    // Defaults follow the UI language until the user saves their own list; saved text is user content.
    private val prefs=context.getSharedPreferences("terminal-settings",0)
    private val defaults=context.resources.getStringArray(R.array.quick_reply_defaults).toList()
    fun all(): List<String> = runCatching {
        val raw=prefs.getString("quick-replies",null) ?: return defaults
        val values=JSONArray(raw)
        (0 until values.length()).map { values.getString(it) }.filter { it.isNotBlank() && it.length<=1000 }.take(12)
    }.getOrDefault(defaults)
    fun save(values: List<String>) {
        if (!(values.size<=12 && values.all { it.isNotBlank() && it.length<=1000 && it.none { c -> c.isISOControl() && c!='\n' && c!='\t' } })) throw AppError(R.string.quick_reply_invalid)
        if (!prefs.edit().putString("quick-replies",JSONArray(values).toString()).commit()) throw AppError(R.string.quick_reply_save_failed)
    }
}
