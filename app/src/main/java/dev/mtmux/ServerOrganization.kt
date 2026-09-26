package dev.mtmux

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.UUID

data class ServerFolder(val id: String = UUID.randomUUID().toString(), val name: String)
enum class ServerSort(@androidx.annotation.StringRes val label: Int) { ADDED(R.string.sort_added), NAME(R.string.sort_name), RECENT(R.string.sort_recent) }

/** Presentation metadata is separate from credentials and SSH target identity. */
class ServerOrganization(context: Context) {
    private val prefs = context.getSharedPreferences("server-profiles", Context.MODE_PRIVATE)
    fun folders(): List<ServerFolder> {
        val a = JSONArray(prefs.getString("folders", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { j -> ServerFolder(j.getString("id"), j.getString("name")) } }
    }
    fun saveFolder(id: String? = null, name: String): ServerFolder {
        val value = name.trim()
        if (value.isEmpty() || value.length > 60) throw AppError(R.string.folder_name_length)
        val existing = folders()
        if (existing.any { it.id != id && it.name.equals(value, ignoreCase = true) }) throw AppError(R.string.folder_name_exists)
        if (id != null && existing.none { it.id == id }) throw AppError(R.string.folder_gone)
        val folder = ServerFolder(id ?: UUID.randomUUID().toString(), value)
        val updated = if (id == null) existing + folder else existing.map { if (it.id == id) folder else it }
        check(prefs.edit().putString("folders", encode(updated)).commit()) { "folder commit failed" }
        return folder
    }
    fun deleteFolder(id: String) {
        val editor = prefs.edit().putString("folders", encode(folders().filterNot { it.id == id })).remove("folder-collapsed:$id")
        prefs.all.filter { (key,value) -> key.startsWith("folder-of:") && value == id }.keys.forEach { editor.remove(it) }
        check(editor.commit()) { "folder delete failed" }
    }
    fun folderOf(profileId: String): String? = prefs.getString("folder-of:$profileId", null)?.takeIf { id -> folders().any { it.id == id } }
    fun move(profileId: String, folderId: String?) {
        if (folderId != null && folders().none { it.id == folderId }) throw AppError(R.string.folder_gone)
        val profiles = JSONArray(prefs.getString("profiles", "[]"))
        require((0 until profiles.length()).any { profiles.getJSONObject(it).getString("id") == profileId }) { "server no longer exists" }
        check(prefs.edit().putString("folder-of:$profileId", folderId).commit()) { "move commit failed" }
    }
    fun collapsed(id: String) = prefs.getBoolean("folder-collapsed:$id", false)
    fun collapse(id: String, collapsed: Boolean) { check(prefs.edit().putBoolean("folder-collapsed:$id", collapsed).commit()) }
    fun sort(): ServerSort = runCatching { ServerSort.valueOf(prefs.getString("server-sort", "ADDED")!!) }.getOrDefault(ServerSort.ADDED)
    fun sort(value: ServerSort) { check(prefs.edit().putString("server-sort", value.name).commit()) }
    fun ordered(profiles: List<ServerProfile>, sort: ServerSort, recent: List<RecentTask>): List<ServerProfile> {
        val collator = Collator.getInstance()
        return when (sort) {
            ServerSort.ADDED -> profiles
            ServerSort.NAME -> profiles.sortedWith { a,b -> collator.compare(a.displayName(), b.displayName()) }
            ServerSort.RECENT -> profiles.sortedByDescending { profile ->
                maxOf(prefs.getLong("used:${profile.id}", 0), recent.filter { it.profileId == profile.id }.maxOfOrNull { it.usedAt } ?: 0)
            }
        }
    }
    private fun encode(folders: List<ServerFolder>) = JSONArray().also { a -> folders.forEach { a.put(JSONObject().put("id",it.id).put("name",it.name)) } }.toString()
}
fun ServerProfile.displayName() = name.ifBlank { "$user@$host" + if(port != 22) ":$port" else "" }
fun ServerProfile.matchesSearch(query: String) = listOf(name,host,user,"$user@$host:$port").any { it.contains(query,ignoreCase=true) }
fun RecentTask.matchesSearch(query: String) = listOf(sessionName,windowName).any { it.contains(query,ignoreCase=true) }
