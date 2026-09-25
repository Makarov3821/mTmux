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
    val credentials = credentials(profile) ?: error("请编辑服务器，补充登录凭据")
    fun auth(host: String, port: Int, user: String, key: Boolean, saved: SavedCredentials, jumps: List<Login> = emptyList()): Login {
        check(!key || saved.privateKey != null) { "请重新导入私钥" }
        return Login(host, port, user, saved.password, if (key) saved.privateKey else null, saved.passphrase, jumps)
    }
    val hops = profile.jumps.map { hop -> auth(hop.host, hop.port, hop.user, hop.keyAuthentication,
        credentials.jumps[hop.id] ?: error("请补充跳板凭据")) }
    return auth(profile.host, profile.port, profile.user, profile.keyAuthentication, credentials, hops)
}
fun ServerProfile.trustEndpoint(): String = Login(host, port, user, "", jumps = jumps.map { Login(it.host, it.port, it.user, "") }).trustEndpoint

/** Include each prefix because a hop is trusted in the context of preceding hops. */
fun ServerProfile.trustEndpoints(): Set<String> {
    val hops = jumps.map { Login(it.host, it.port, it.user, "") }
    return hops.mapIndexed { index, hop -> Login(hop.host, hop.port, hop.user, "", jumps = hops.take(index)).trustEndpoint }.toSet() + trustEndpoint()
}
