package com.flappedear.betterdriver.coach

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutputDelayTest {
    @Test
    fun `frames handed over but not yet heard are the delay`() {
        // 48 kHz: the head is 9 600 frames (200 ms) past the frame heard 20 ms ago, so 180 ms are still to come.
        val ms = OutputDelay.readingMillis(
            headFrames = 19_600, presentedFrame = 10_000, presentedNanos = 1_000_000_000,
            nowNanos = 1_020_000_000, sampleRate = 48_000,
        )
        assertEquals(180.0, ms, 1e-9)
    }

    @Test
    fun `the median of the readings, rounded and kept within the coach's range`() {
        assertEquals(190, OutputDelay.of(listOf(181.0, 400.0, 188.0, 192.0, 10.0)))
        assertEquals(0, OutputDelay.of(listOf(-4.0, -2.0, 3.0)))
        assertEquals(CoachSettings.MaxAudioDelayMillis, OutputDelay.of(listOf(5_000.0, 5_000.0, 5_000.0)))
        assertNull(OutputDelay.of(listOf(180.0, 190.0)), "too few readings")
    }

    @Test
    fun `a session uses the measured delay, and the set one only when nothing was measured`() {
        assertEquals(AudioDelayUsed(190, AudioDelayUsed.Measured), OutputDelay.effective(300, 190))
        assertEquals(AudioDelayUsed(0, AudioDelayUsed.Measured), OutputDelay.effective(300, 0))
        assertEquals(AudioDelayUsed(300, AudioDelayUsed.Set), OutputDelay.effective(300, null))
    }
}
