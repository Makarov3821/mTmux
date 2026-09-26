package dev.mtmux

import android.content.Context
import dev.mtmux.core.*
import org.json.JSONArray
import org.json.JSONObject

data class ServerSnapshot(val tasks: List<RecentTask>, val updatedAt: Long, val states: Map<String,TaskState> = emptyMap())
class ServerCache(context: Context) {
    private val prefs = context.getSharedPreferences("server-profiles", Context.MODE_PRIVATE)
    private fun context(profile: ServerProfile) = "${profile.host}:${profile.port}/${profile.user}/${profile.path}/${profile.routeContext()}"
    fun read(profile: ServerProfile): ServerSnapshot? = runCatching {
        val j = JSONObject(prefs.getString("cache:${profile.id}", null) ?: return null)
        if (j.getString("context") != context(profile)) return null
        val a = j.getJSONArray("tasks")
        val tasks=(0 until a.length()).map { RecentTask.decode(a.getString(it)) }
        val states=j.optJSONObject("states")
        ServerSnapshot(tasks,j.getLong("time"),tasks.mapNotNull { task -> task.identity?.let { id ->
            id to (TaskState.entries.firstOrNull {it.name==states?.optString(id)} ?: TaskState.UNKNOWN)
        } }.toMap())
    }.getOrNull()
    fun write(profile: ServerProfile, tasks: List<RecentTask>, states: Map<String,TaskState> = emptyMap()): ServerSnapshot {
        require(tasks.all { it.matches(profile) })
        require(states.keys.all { id -> tasks.any { it.identity==id } })
        val time = System.currentTimeMillis()
        val data = JSONObject().put("context", context(profile)).put("time", time)
            .put("tasks", JSONArray().also { a -> tasks.forEach { a.put(it.encode()) } })
            .put("states",JSONObject().also { j -> states.forEach { (id,state) -> j.put(id,state.name) } })
        check(prefs.edit().putString("cache:${profile.id}", data.toString()).commit()) { "cache commit failed" }
        return ServerSnapshot(tasks, time, states)
    }
    fun clearTaskStates(profile: ServerProfile): ServerSnapshot? {
        val snapshot=read(profile) ?: return null
        val key="cache:${profile.id}"
        val data=JSONObject(prefs.getString(key,null) ?: return null).put("states",JSONObject())
        check(prefs.edit().putString(key,data.toString()).commit()) { "state cache commit failed" }
        return snapshot.copy(states=emptyMap())
    }
    // Attempts, including failures, are throttled across page/app recreation.
    fun shouldRefresh(profile: ServerProfile, now: Long = System.currentTimeMillis()): Boolean {
        val attempt = runCatching { JSONObject(prefs.getString("probe:${profile.id}", null) ?: return true) }.getOrNull() ?: return true
        if (attempt.optString("context") != context(profile)) return true
        val last = attempt.optLong("time", 0)
        return last <= 0 || now < last || now - last >= 30 * 60 * 1000L
    }
    fun markAttempt(profile: ServerProfile, now: Long = System.currentTimeMillis()) {
        check(prefs.edit().putString("probe:${profile.id}", JSONObject().put("context", context(profile)).put("time", now).toString()).commit()) { "probe time commit failed" }
    }
    fun collapsed(id: String) = prefs.getBoolean("collapsed:$id", false)
    fun collapse(id: String, value: Boolean) { prefs.edit().putBoolean("collapsed:$id", value).apply() }
}

fun SshClient.discoverTasks(profile: ServerProfile, hostKey: String): List<RecentTask> = discoverPanes(profile.path).mapNotNull { pane ->
    val format = "#{pid}:#{start_time}:#{session_id}:#{window_id}:#{pane_id}:#{pane_pid}"
    val result = exec("${Tmux.quote(profile.path)} -u display-message -p -t ${Tmux.quote("${pane.session}:${pane.window}.${pane.id}")} ${Tmux.quote(format + ":#{pane_dead}")}")
    if (result.status != 0) throw MtmuxException(ErrorCode.TMUX_TARGET_CHANGED)
    val raw = result.output.trim()
    if (!raw.endsWith(":0")) return@mapNotNull null
    val binding = PaneBinding.parse(pane.session, raw.removeSuffix(":0"))
    RecentTask(profile.id, profile.host, profile.port, profile.user, profile.path, hostKey, binding.identity,
        pane.sessionName, "${pane.windowIndex} · ${pane.windowName}", route = profile.routeContext())
}

/** Persist only the resulting enum, never captured text. A pane read failure is unknown. */
fun SshClient.discoverTaskStates(tasks: List<RecentTask>, path: String): Map<String,TaskState> = tasks.mapNotNull { task ->
    task.binding?.let { binding ->
        val state=runCatching {
            verifyResume(binding,path)
            val screen=exec(Tmux.taskScreen(binding,path))
            check(screen.status==0)
            verifyResume(binding,path)
            TaskStates.inspect(screen.output)
        }.getOrDefault(TaskState.UNKNOWN)
        binding.identity to state
    }
}.toMap()

fun ServerSnapshot.taskState(task: RecentTask, now: Long = System.currentTimeMillis()): TaskState =
    if(now<updatedAt || now-updatedAt>=30*60*1000L) TaskState.UNKNOWN else states[task.identity] ?: TaskState.UNKNOWN

fun List<TaskProbe.Snapshot>.recentTasks(profile: ServerProfile, hostKey: String): List<RecentTask> = map { pane ->
    RecentTask(profile.id, profile.host, profile.port, profile.user, profile.path, hostKey, pane.binding.identity,
        pane.sessionName, "${pane.windowIndex} · ${pane.windowName}", route = profile.routeContext())
}
