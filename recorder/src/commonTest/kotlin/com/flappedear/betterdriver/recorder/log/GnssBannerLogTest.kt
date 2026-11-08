package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The GNSS-lost banner's changes, logged the same on Android and iPhone (FBBD-64). */
class GnssBannerLogTest {
    private fun snapshot(fixAgeMicros: Long?) = LiveSnapshot(
        sessionId = "s", startUtcMillis = 0, elapsedMicros = 60_000_000, fixes = 100, fixRateHz = 0,
        lastFixAgeMicros = fixAgeMicros, sats = null, speedKmh = null, storageLowBytes = null,
        stoppedReason = null, droppedSamples = 0, receiverRateHz = 25,
    )

    @Test
    fun logsEachShowAndClearOnce() {
        val log = GnssBannerLog()
        assertNull(log.follow(snapshot(null)), "waiting for the first fix shows no banner")
        assertNull(log.follow(snapshot(40_000)))
        assertEquals("state=shown fix_age_us=1500000", log.follow(snapshot(1_500_000)))
        assertNull(log.follow(snapshot(20_000_000)), "still lost: no second event")
        assertEquals("state=cleared fix_age_us=40000", log.follow(snapshot(40_000)))
        assertNull(log.follow(snapshot(0)))
    }

    @Test
    fun aNewSessionStartsHidden() {
        val log = GnssBannerLog()
        log.follow(snapshot(5_000_000))
        log.reset()
        assertEquals("state=shown fix_age_us=5000000", log.follow(snapshot(5_000_000)))
    }
}
