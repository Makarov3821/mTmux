package dev.mtmux

import dev.mtmux.core.Login
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class JumpHost(val id: String = UUID.randomUUID().toString(), val host: String, val port: Int,
                    val user: String, val keyAuthentication: Boolean) {
    fun json() = JSONObject().put("id", id).put("host", host).put("port", port).put("user", user).put("key", keyAuthentication)
    companion object {
        fun parse(j: JSONObject) = JumpHost(j.getString("id"), j.getString("host"), j.getInt("port"), j.getString("user"), j.getBoolean("key"))
    }
}
fun ServerProfile.routeContext(): String = if (jumps.isEmpty()) "" else JSONArray().also { a -> jumps.forEach { a.put(it.json()) } }.toString()
fun ServerProfiles.login(profile: ServerProfile): Login {
    val credentials = credentials(profile) ?: throw AppError(R.string.err_missing_credentials)
    fun auth(host: String, port: Int, user: String, key: Boolean, saved: SavedCredentials, jumps: List<Login> = emptyList()): Login {
        if (key && saved.privateKey == null) throw AppError(R.string.err_reimport_key)
        return Login(host, port, user, saved.password, if (key) saved.privateKey else null, saved.passphrase, jumps)
    }
    val hops = profile.jumps.map { hop -> auth(hop.host, hop.port, hop.user, hop.keyAuthentication,
        credentials.jumps[hop.id] ?: throw AppError(R.string.err_missing_jump_credentials)) }
    return auth(profile.host, profile.port, profile.user, profile.keyAuthentication, credentials, hops)
}
fun ServerProfile.trustEndpoint(): String = Login(host, port, user, "", jumps = jumps.map { Login(it.host, it.port, it.user, "") }).trustEndpoint

/** Include each prefix because a hop is trusted in the context of preceding hops. */
fun ServerProfile.trustEndpoints(): Set<String> {
    val hops = jumps.map { Login(it.host, it.port, it.user, "") }
    return hops.mapIndexed { index, hop -> Login(hop.host, hop.port, hop.user, "", jumps = hops.take(index)).trustEndpoint }.toSet() + trustEndpoint()
}
