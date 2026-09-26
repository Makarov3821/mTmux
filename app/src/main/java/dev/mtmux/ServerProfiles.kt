package dev.mtmux

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Base64

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val host: String, val port: Int, val user: String, val path: String,
    val keyAuthentication: Boolean, val enableTmuxMouse: Boolean = true, val jumps: List<JumpHost> = emptyList()
)

class ServerProfiles(context: Context) {
    private val preferences = context.getSharedPreferences("server-profiles", Context.MODE_PRIVATE)
    private val cipher = CredentialCipher()
    private val pins = context.getSharedPreferences("host-pins", Context.MODE_PRIVATE)
    fun all(): List<ServerProfile> {
        val array = JSONArray(preferences.getString("profiles", "[]"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ServerProfile(item.getString("id"), item.getString("name"), item.getString("host"),
                item.getInt("port"), item.getString("user"), item.getString("path"), item.getBoolean("keyAuthentication"),
                item.optBoolean("enableTmuxMouse", true), item.optJSONArray("jumps")?.let { a -> (0 until a.length()).map { JumpHost.parse(a.getJSONObject(it)) } } ?: emptyList())
        }
    }
    fun last(): String? = preferences.getString("last", null)
    fun recent(): List<RecentTask> {
        val profiles = all()
        return runCatching {
            val array = JSONArray(preferences.getString("recent-tasks", "[]"))
            (0 until array.length()).mapNotNull { i -> runCatching { RecentTask.decode(array.getString(i)) }.getOrNull() }
                .filter { task -> profiles.any { task.matches(it) } }.sortedByDescending { it.usedAt }.take(10)
        }.getOrDefault(emptyList())
    }
    private fun encodeTasks(tasks: List<RecentTask>) = JSONArray().also { array -> tasks.forEach { array.put(it.encode()) } }.toString()
    fun rememberTask(task: RecentTask) {
        check(all().any { task.matches(it) }) { "profile changed; recent task not saved" }
        val tasks = listOf(task) + recent().filterNot {
            it.profileId == task.profileId && (it.identity == task.identity ||
                it.hostKey != task.hostKey || (it.binding != null && task.binding != null &&
                (it.binding!!.pane == task.binding!!.pane ||
                    it.identity!!.split(':').take(2) != task.identity!!.split(':').take(2))))
        }
        check(preferences.edit().putString("recent-tasks", encodeTasks(tasks.take(10))).putString("last", task.profileId).putLong("used:${task.profileId}", task.usedAt).commit()) { "recent task commit failed" }
    }
    fun credentials(profile: ServerProfile): SavedCredentials? {
        val encoded = preferences.getString("credential:${profile.id}", null) ?: return null
        val bytes = cipher.decrypt(profile, encoded)
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            fun decode(j: JSONObject) = SavedCredentials(j.getString("password"), j.optString("privateKey").takeIf { it.isNotEmpty() }
                ?.let { Base64.getDecoder().decode(it) }, j.getString("passphrase"))
            val root = decode(json)
            val hops = json.optJSONObject("jumps")
            return SavedCredentials(root.password, root.privateKey, root.passphrase,
                hops?.keys()?.asSequence()?.associateWith { decode(hops.getJSONObject(it)) } ?: emptyMap())
        } finally { bytes.fill(0) }
    }
    fun save(profile: ServerProfile, credentials: SavedCredentials? = null) {
        // Encryption must succeed before changing metadata. One commit stores both.
        val encrypted = credentials?.let {
            require(!profile.keyAuthentication || it.privateKey != null) { "private key required" }
            fun encodeCredential(key: Boolean, secret: SavedCredentials): JSONObject {
                require(!key || secret.privateKey != null) { "private key required" }
                return JSONObject().put("password", if (key) "" else secret.password)
                    .put("privateKey", if (key) Base64.getEncoder().encodeToString(secret.privateKey!!) else "")
                    .put("passphrase", if (key) secret.passphrase else "")
            }
            val hops = JSONObject()
            profile.jumps.forEach { hop -> hops.put(hop.id, encodeCredential(hop.keyAuthentication, it.jumps[hop.id] ?: error("missing jump credentials"))) }
            val bytes = JSONObject().put("jumps", hops).put("password", if (profile.keyAuthentication) "" else it.password)
                .put("privateKey", if (profile.keyAuthentication) Base64.getEncoder().encodeToString(it.privateKey!!) else "")
                .put("passphrase", if (profile.keyAuthentication) it.passphrase else "").toString().toByteArray(Charsets.UTF_8)
            try { cipher.encrypt(profile, bytes) } finally { bytes.fill(0) }
        }
        val existing = all()
        val profiles = if (existing.any { it.id == profile.id }) existing.map { if (it.id == profile.id) profile else it } else existing + profile
        val editor = preferences.edit().putString("profiles", encode(profiles)).putString("last", profile.id)
        val previous = all().firstOrNull { it.id == profile.id }
        if (previous != null && (previous.host != profile.host || previous.port != profile.port ||
                previous.user != profile.user || previous.path != profile.path || previous.routeContext() != profile.routeContext())) editor.remove("probe:${profile.id}").remove("cache:${profile.id}").putString("recent-tasks", encodeTasks(recent().filterNot { it.profileId == profile.id }))
        if (encrypted != null) editor.putString("credential:${profile.id}", encrypted)
        check(editor.commit()) { "profile commit failed" }
    }
    fun delete(id: String) {
        val profiles = all()
        val unusedPins = profiles.firstOrNull { it.id == id }?.trustEndpoints().orEmpty() -
            profiles.filterNot { it.id == id }.flatMap { it.trustEndpoints() }.toSet()
        check(preferences.edit().putString("profiles", encode(profiles.filterNot { it.id == id }))
            .putString("last", last()?.takeUnless { it == id }).remove("folder-of:$id").remove("used:$id").remove("credential:$id").remove("cache:$id").remove("probe:$id").remove("collapsed:$id").putString("recent-tasks", encodeTasks(recent().filterNot { it.profileId == id })).commit()) { "profile delete failed" }
        pins.edit().also { editor -> unusedPins.forEach { editor.remove(it) } }.apply()
    }
    private fun encode(profiles: List<ServerProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().put("id", profile.id).put("name", profile.name)
                .put("host", profile.host).put("port", profile.port).put("user", profile.user)
                .put("path", profile.path).put("keyAuthentication", profile.keyAuthentication)
                .put("enableTmuxMouse", profile.enableTmuxMouse).put("jumps", JSONArray().also { a -> profile.jumps.forEach { a.put(it.json()) } }))
        }
        return array.toString()
    }
}
