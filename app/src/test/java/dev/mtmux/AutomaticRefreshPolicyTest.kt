package dev.mtmux

import org.junit.Assert.*
import org.junit.Test

class AutomaticRefreshPolicyTest {
    @Test fun returningServerRefreshesEvenAfterRecentProbe() {
        assertTrue(AutomaticRefreshPolicy.allowsRefresh(false, false, true))
        assertFalse(AutomaticRefreshPolicy.allowsRefresh(false, false, false))
    }

    @Test fun foldedGroupsStayQuietEvenForReturningOrOverdueServers() {
        for (due in listOf(false, true)) for (forced in listOf(false, true)) {
            assertFalse(AutomaticRefreshPolicy.allowsRefresh(true, due, forced))
        }
    }

    @Test fun otherVisibleServersKeepNormalAutomaticRefresh() {
        assertTrue(AutomaticRefreshPolicy.allowsRefresh(false, true, false))
    }

    @Test fun fiveMinuteBoundaryAndClockRollback() {
        val last = 1_000_000L
        assertFalse(AutomaticRefreshPolicy.intervalElapsed(last, last + 299_999L))
        assertTrue(AutomaticRefreshPolicy.intervalElapsed(last, last + 300_000L))
        assertTrue(AutomaticRefreshPolicy.intervalElapsed(last, last - 1))
        assertTrue(AutomaticRefreshPolicy.intervalElapsed(0, last))
    }
}
