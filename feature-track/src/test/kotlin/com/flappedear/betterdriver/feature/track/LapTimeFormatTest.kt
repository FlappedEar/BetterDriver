package com.flappedear.betterdriver.feature.track

import org.junit.Assert.assertEquals
import org.junit.Test

class LapTimeFormatTest {
    @Test
    fun formatsLapTime() {
        assertEquals("1:42.317", formatLapTime(102_317))
        assertEquals("0:00.000", formatLapTime(0))
        assertEquals("10:05.009", formatLapTime(605_009))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLapTime() {
        formatLapTime(-1)
    }

    @Test
    fun formatsDeltaWithSign() {
        assertEquals("-0.42", formatDelta(-420))
        assertEquals("+1.07", formatDelta(1_068))
        assertEquals("+0.00", formatDelta(0))
    }
}
