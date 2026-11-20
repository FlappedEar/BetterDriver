package com.flappedear.betterdriver.feature.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FBBD-455: Android writes the same LapReview line as iOS (FBBD-429) each time playback stops. */
class LapReviewLogTest {
    @Test
    fun oneLinePerStretchOfPlaybackWithSpeedAndPlatform() {
        val written = mutableListOf<String>()
        val log = LapReviewLog { written += it }
        // Never played: nothing to say.
        assertNull(log.stopped(nowNanos = 5_000_000_000, speed = 1.0))
        log.frame(workNanos = 2_000_000, nowNanos = 10_000_000_000)
        log.frame(workNanos = 20_500_000, nowNanos = 10_016_000_000)
        log.stats.rateChanged()
        log.stats.seekedWhilePlaying()
        log.stats.drift(-45)
        val line = log.stopped(nowNanos = 12_500_000_000, speed = 0.5)
        assertEquals(
            "played 2.5 s: 2 frames, work mean 11.2 max 20.5 ms, 1 slow, 1 rate changes, " +
                "1 seeks while playing, 0 synced starts, max drift 45 ms, speed 0.5, Android",
            line,
        )
        assertEquals(listOf(line), written)
        // Written once: a second stop, or the review closing after a pause, adds nothing.
        assertNull(log.stopped(nowNanos = 13_000_000_000, speed = 0.5))
        // The next stretch starts its count again.
        log.frame(workNanos = 1_000_000, nowNanos = 20_000_000_000)
        assertEquals(
            "played 1.0 s: 1 frames, work mean 1.0 max 1.0 ms, 0 slow, 0 rate changes, " +
                "0 seeks while playing, 0 synced starts, max drift 0 ms, speed 1.0, Android",
            log.stopped(nowNanos = 21_000_000_000, speed = 1.0),
        )
        assertEquals(2, written.size)
    }
}
