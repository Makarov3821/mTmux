package dev.mtmux.audiotest

import org.junit.Assert.*
import org.junit.Test

class PunctuationWindowsTest {
    @Test fun longInstructionsKeepTheirWholeContextWhenTheyFit() {
        for (size in listOf(129, 200, 350, 512)) assertEquals(listOf(0), PunctuationWindows.starts(size, 512))
    }
    @Test fun originalSrfWindowsAreUnchanged() {
        assertEquals(listOf(0, 96, 129), PunctuationWindows.starts(257, 128))
    }
    @Test fun longInputHasNoMissingOrTruncatedTail() {
        for (window in listOf(128, 512)) for (size in listOf(1, 129, 513, 1025, 4096)) {
            val coverage = BooleanArray(size)
            for (start in PunctuationWindows.starts(size, window)) {
                for (i in start until minOf(start + window, size)) coverage[i] = true
            }
            assertTrue("$window / $size", coverage.all { it })
            assertTrue(PunctuationWindows.starts(size, window).all { it >= 0 && it < size })
        }
    }
}
