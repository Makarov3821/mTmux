package dev.mtmux

import org.junit.Assert.*
import org.junit.Test

class ServerSavePolicyTest {
    private val profile = ServerProfile(name="fixture", host="fixture.invalid", port=22, user="fixture", path="tmux", keyAuthentication=false)
    @Test fun newServerAndChangedAuthenticationMustVerify() {
        assertTrue(needsServerVerification(null,profile,null,SavedCredentials("one")))
        assertTrue(needsServerVerification(profile,profile,SavedCredentials("one"),SavedCredentials("two")))
        assertTrue(needsServerVerification(profile,profile.copy(host="other.invalid"),SavedCredentials("one"),SavedCredentials("one")))
    }
    @Test fun aliasMouseAndTmuxPathChangesDoNotRequireAnExtraLogin() {
        assertFalse(needsServerVerification(profile,profile.copy(name="renamed",path="/usr/bin/tmux",enableTmuxMouse=false),SavedCredentials("one"),SavedCredentials("one")))
    }
    @Test fun modifiedJumpCredentialsMustVerifyTheWholeRoute() {
        val hop=JumpHost(host="jump.invalid",port=22,user="fixture",keyAuthentication=false)
        val routed=profile.copy(jumps=listOf(hop))
        assertTrue(needsServerVerification(routed,routed,SavedCredentials("one",jumps=mapOf(hop.id to SavedCredentials("old"))),SavedCredentials("one",jumps=mapOf(hop.id to SavedCredentials("new")))))
    }
    @Test fun keyMaterialIsComparedByContent() {
        val keyed=profile.copy(keyAuthentication=true)
        assertFalse(needsServerVerification(keyed,keyed,SavedCredentials("",byteArrayOf(1),"phrase"),SavedCredentials("",byteArrayOf(1),"phrase")))
        assertTrue(needsServerVerification(keyed,keyed,SavedCredentials("",byteArrayOf(1),"phrase"),SavedCredentials("",byteArrayOf(2),"phrase")))
    }
}
