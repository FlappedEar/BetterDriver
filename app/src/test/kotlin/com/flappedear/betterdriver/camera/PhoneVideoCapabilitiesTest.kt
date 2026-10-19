package com.flappedear.betterdriver.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneVideoCapabilitiesTest {
    @Test
    fun `frame durations become frames per second`() {
        assertEquals(60, maxFps(16_666_666L))
        assertEquals(30, maxFps(33_333_333L))
        assertEquals(0, maxFps(null))
        assertEquals(0, maxFps(0L))
    }

    @Test
    fun `sizes are listed largest first with their frame rate`() {
        val line = encoderSizesLine(listOf((1920 to 1080) to 60, (3840 to 2160) to 60, (2560 to 1440) to 0))
        assertEquals("3840x2160@60 2560x1440@? 1920x1080@60", line)
        assertEquals("none", encoderSizesLine(emptyList()))
    }
}
