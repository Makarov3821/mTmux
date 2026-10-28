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
class HostKeyRejected(val challenge: HostKeyChallenge) : Exception("host key not trusted or changed")

class PinnedHostKeys(private val endpoint: String, private val pins: PinStore,
    private val confirmUnknown: ((HostKeyChallenge) -> Boolean)? = null) : HostKeyRepository {
    var challenge: HostKeyChallenge? = null
        private set
    override fun check(host: String, key: ByteArray): Int {
        val encoded = Base64.getEncoder().encodeToString(key)
        val expected = pins.get(endpoint)
        challenge = null
        if (expected == encoded) return HostKeyRepository.OK
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        val candidate = HostKeyChallenge(endpoint, encoded, fingerprint, expected != null)
        // Only the interactive add-server flow opts in. Never override an existing key.
        if (expected == null && confirmUnknown?.invoke(candidate) == true) {
            val current = pins.get(endpoint)
            if (current == null || current == encoded) return HostKeyRepository.OK
            challenge = candidate.copy(changed = true)
            return HostKeyRepository.CHANGED
        }
        challenge = candidate
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

/** Explicitly accepted keys stay provisional until the entire route authenticates and is saved. */
class StagedHostKeys(private val saved: PinStore) : PinStore {
    private val staged = linkedMapOf<String, String>()
    @Synchronized override fun get(endpoint: String) = saved.get(endpoint) ?: staged[endpoint]
    @Synchronized fun approve(challenge: HostKeyChallenge): Boolean {
        if (challenge.changed) return false
        val existing = get(challenge.endpoint)
        if (existing != null && existing != challenge.key) return false
        staged[challenge.endpoint] = challenge.key
        return true
    }
    @Synchronized fun accepted(): Map<String, String> = staged.toMap()
}
