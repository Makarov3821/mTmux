package dev.mtmux

import org.junit.Assert.*
import org.junit.Test

class TerminalImeRecoveryTest {
    @Test fun resumeBeforeWindowFocusRetainsRestartUntilReady() {
        val recovery = TerminalImeRecovery()
        recovery.connected()
        recovery.invalidate() // ON_RESUME retires the previous input connection.
        assertNull(recovery.take(ready = false))
        assertNull(recovery.take(ready = false)) // The first posted attempt may also be too early.
        assertEquals(TerminalImeRecovery.Request(true, false), recovery.take(ready = true))
        recovery.connected()
        assertNull(recovery.take(ready = true))
    }

    @Test fun explicitTapRepairsMissingConnectionAndShowsKeyboardInOrder() {
        val recovery = TerminalImeRecovery()
        recovery.connected()
        recovery.invalidate()
        recovery.keyboard(hasActiveConnection = false)
        assertNull(recovery.take(ready = false))
        assertEquals(TerminalImeRecovery.Request(true, true), recovery.take(ready = true))
        recovery.connected()
        // A second tap on an active connection must preserve its uncommitted composition.
        recovery.keyboard(hasActiveConnection = true)
        assertEquals(TerminalImeRecovery.Request(false, true), recovery.take(ready = true))
    }

    @Test fun focusMovingToDraftCancelsKeyboardRequestWithoutReplayingOldInput() {
        val recovery = TerminalImeRecovery()
        recovery.keyboard(hasActiveConnection = false)
        recovery.suspend() // An editor sheet or another app now owns focus.
        assertNull(recovery.take(ready = false))
        assertEquals(TerminalImeRecovery.Request(true, false), recovery.take(ready = true))
        assertNull(recovery.take(ready = true))
    }
}
