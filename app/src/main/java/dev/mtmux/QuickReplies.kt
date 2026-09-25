package dev.mtmux

import android.content.Context
import org.json.JSONArray

class QuickReplies(context: Context) {
    private val prefs=context.getSharedPreferences("terminal-settings",0)
    private val defaults=listOf("继续", "请总结当前进展和剩余问题。", "请先解释你的方案，暂时不要执行。", "请运行相关测试并报告结果。")
    fun all(): List<String> = runCatching {
        val raw=prefs.getString("quick-replies",null) ?: return defaults
        val values=JSONArray(raw)
        (0 until values.length()).map { values.getString(it) }.filter { it.isNotBlank() && it.length<=1000 }.take(12)
    }.getOrDefault(defaults)
    fun save(values: List<String>) {
        require(values.size<=12 && values.all { it.isNotBlank() && it.length<=1000 && it.none { c -> c.isISOControl() && c!='\n' && c!='\t' } }) { "最多 12 条，每条 1–1000 字，不能含控制字符" }
        check(prefs.edit().putString("quick-replies",JSONArray(values).toString()).commit()) { "快捷回复保存失败" }
    }
}
