package dev.mtmux.core

import com.jcraft.jsch.HostKeyRepository
import org.junit.Test
import kotlin.test.*

class InteractiveTrustTest {
    @Test fun `unknown approval continues handshake but remains provisional`() {
        val persisted = mutableMapOf<String, String>()
        val staged = StagedHostKeys(object : PinStore { override fun get(endpoint: String) = persisted[endpoint] })
        val verifier = PinnedHostKeys("host", staged) { staged.approve(it) }
        assertEquals(HostKeyRepository.OK, verifier.check("host", byteArrayOf(1,2,3)))
        assertNull(verifier.challenge, "authentication errors must not be mistaken for host-key errors")
        assertTrue(persisted.isEmpty())
        assertEquals(1, staged.accepted().size)
        assertEquals(staged.accepted()["host"], staged.get("host"))
    }
    @Test fun `declining does not stage or trust a key`() {
        val staged = StagedHostKeys(object : PinStore { override fun get(endpoint: String): String? = null })
        val verifier = PinnedHostKeys("host", staged) { false }
        assertEquals(HostKeyRepository.NOT_INCLUDED, verifier.check("host", byteArrayOf(1)))
        assertTrue(staged.accepted().isEmpty())
        assertNotNull(verifier.challenge)
    }
    @Test fun `changed saved keys never reach approval callback`() {
        var prompted = false
        val staged = StagedHostKeys(object : PinStore { override fun get(endpoint: String) = "old-key" })
        val verifier = PinnedHostKeys("host", staged) { prompted = true; true }
        assertEquals(HostKeyRepository.CHANGED, verifier.check("host", byteArrayOf(1)))
        assertFalse(prompted); assertTrue(verifier.challenge!!.changed)
        assertTrue(staged.accepted().isEmpty())
    }
    @Test fun `a saved pin changing while the dialog is open cannot be overwritten`() {
        var pin: String? = null
        val store = object : PinStore { override fun get(endpoint: String) = pin }
        val verifier = PinnedHostKeys("host", store) { pin = "another-key"; true }
        assertEquals(HostKeyRepository.CHANGED, verifier.check("host", byteArrayOf(1)))
        assertTrue(verifier.challenge!!.changed)
    }
    @Test fun `jump prefix approvals are independent and saved pins take precedence`() {
        val base = mutableMapOf<String,String>()
        val staged = StagedHostKeys(object : PinStore { override fun get(endpoint: String) = base[endpoint] })
        val first = HostKeyChallenge("jump", "a", "SHA256:a", false)
        val target = HostKeyChallenge("target|via:jump", "b", "SHA256:b", false)
        assertTrue(staged.approve(first)); assertTrue(staged.approve(target))
        assertEquals(2, staged.accepted().size)
        base["jump"] = "changed"
        assertEquals("changed", staged.get("jump"))
        assertFalse(staged.approve(first))
    }
}
