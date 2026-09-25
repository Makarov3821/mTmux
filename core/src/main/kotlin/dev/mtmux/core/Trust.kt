package dev.mtmux.core

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

interface PinStore {
    fun get(endpoint: String): String?
}

data class HostKeyChallenge(val endpoint: String, val key: String, val fingerprint: String, val changed: Boolean)
class HostKeyRejected(val challenge: HostKeyChallenge) : Exception("服务器主机密钥尚未信任或已改变")

class PinnedHostKeys(private val endpoint: String, private val pins: PinStore) : HostKeyRepository {
    var challenge: HostKeyChallenge? = null
        private set
    override fun check(host: String, key: ByteArray): Int {
        val encoded = Base64.getEncoder().encodeToString(key)
        val expected = pins.get(endpoint)
        if (expected == encoded) return HostKeyRepository.OK
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        challenge = HostKeyChallenge(endpoint, encoded, fingerprint, expected != null)
        return if (expected == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
    }
    // Trust is only changed explicitly by the UI, never by the SSH library.
    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
    override fun remove(host: String, type: String?) = Unit
    override fun remove(host: String, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "mtmux explicit pins"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
