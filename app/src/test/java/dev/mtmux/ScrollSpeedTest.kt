package dev.mtmux

import org.junit.Assert.*
import org.junit.Test

class ScrollSpeedTest {
    @Test fun oldValuesAndInvalidPreferencesNormalizeToValidIntegerSteps() {
        for ((input,expected) in listOf(.5f to 1f,1f to 1f,1.49f to 1f,1.5f to 2f,5f to 5f,10f to 10f,
            100f to 10f,-1f to 1f,Float.NaN to 1f,Float.POSITIVE_INFINITY to 1f)) {
            assertEquals(expected,ScrollSpeed.normalize(input))
        }
        for(step in 1..10) assertEquals(step.toFloat(),ScrollSpeed.normalize(step.toFloat()))
    }
}
