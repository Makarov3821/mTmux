package dev.mtmux

import dev.mtmux.core.PaneBinding
import org.json.JSONObject

/** References an existing credential profile; never stores credentials or terminal text. */
data class RecentTask(
    val profileId: String, val host: String, val port: Int, val user: String, val path: String,
    val hostKey: String, val identity: String?, val sessionName: String, val windowName: String,
    val usedAt: Long = System.currentTimeMillis(), val route: String = ""
) {
    val binding: PaneBinding? get() = identity?.let { PaneBinding.parse(it.split(':')[2], it) }
    val label: String get() = if (identity == null) "普通 shell（新连接）" else "$sessionName · $windowName · ${binding!!.pane}"
    fun matches(profile: ServerProfile) = profileId == profile.id && host == profile.host && port == profile.port &&
        user == profile.user && path == profile.path && route == profile.routeContext()
    fun encode(): String = JSONObject().put("profile", profileId).put("host", host).put("port", port)
        .put("user", user).put("path", path).put("hostKey", hostKey).put("identity", identity ?: "")
        .put("sessionName", sessionName).put("windowName", windowName).put("usedAt", usedAt).put("route", route).toString()
    companion object {
        fun decode(text: String): RecentTask {
            val j = JSONObject(text)
            return RecentTask(j.getString("profile"), j.getString("host"), j.getInt("port"), j.getString("user"),
                j.getString("path"), j.getString("hostKey"), j.getString("identity").takeIf { it.isNotEmpty() },
                j.getString("sessionName"), j.getString("windowName"), j.getLong("usedAt"), j.optString("route", "")).also { it.binding }
        }
    }
}
