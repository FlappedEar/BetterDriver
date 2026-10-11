package com.flappedear.betterdriver.core.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {
    @Test
    fun lapTime() {
        assertEquals("1:52.07", formatLapTime(112_070))
        assertEquals("0:00.00", formatLapTime(0))
        assertEquals("0:00.00", formatLapTime(-5))
        assertEquals("1:00.00", formatLapTime(59_996))
        assertEquals("10:05.10", formatLapTime(605_100))
    }

    @Test
    fun runningTime() {
        assertEquals("0:47.3", formatRunningTime(47_399))
        assertEquals("1:00.0", formatRunningTime(60_000))
    }

    @Test
    fun delta() {
        assertEquals("${Minus}0.34", formatDelta(-0.34))
        assertEquals("+0.08", formatDelta(0.08))
        assertEquals("+0.00", formatDelta(-0.001))
        assertEquals("+99.99", formatDelta(250.0))
        assertEquals("${Minus}99.99", formatDelta(-250.0))
    }

    @Test
    fun deltaGainFollowsDisplayedSign() {
        assertTrue(isDeltaGain(-0.34))
        assertTrue(isDeltaGain(-0.006))
        assertFalse(isDeltaGain(-0.001))
        assertFalse(isDeltaGain(0.0))
        assertFalse(isDeltaGain(0.08))
    }
}
