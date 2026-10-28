package dev.mtmux

enum class ServerSaveOutcome { VERIFIED, UNVERIFIED, LOCAL }

/** Presentation-only edits stay local; new routes or authentication details are checked. */
internal fun needsServerVerification(previous: ServerProfile?, current: ServerProfile,
    before: SavedCredentials?, after: SavedCredentials): Boolean {
    if (previous == null || before == null) return true
    if (previous.host != current.host || previous.port != current.port || previous.user != current.user ||
        previous.keyAuthentication != current.keyAuthentication || previous.jumps != current.jumps) return true
    fun same(key: Boolean, a: SavedCredentials?, b: SavedCredentials?): Boolean {
        if (a == null || b == null) return false
        return if (key) a.privateKey.contentEquals(b.privateKey) && a.passphrase == b.passphrase
        else a.password == b.password
    }
    return !same(current.keyAuthentication, before, after) || current.jumps.any {
        !same(it.keyAuthentication, before.jumps[it.id], after.jumps[it.id])
    }
}
