package com.flappedear.betterdriver.videoexport

import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.overlay.OverlayFrame
import com.flappedear.betterdriver.timing.overlay.OverlayLap
import org.junit.Assert.assertEquals
import org.junit.Test

/** The words of the Watch video screen (FBBD-341). */
class WatchVideoUiTest {
    @Test
    fun clockShowsMinutesAndSecondsThenHours() {
        assertEquals("0:00", clockText(0))
        assertEquals("0:00", clockText(-5))
        assertEquals("4:05", clockText(245_900_000))
        assertEquals("59:59", clockText(3_599_000_000))
        assertEquals("1:02:03", clockText(3_723_000_000))
    }

    @Test
    fun bandDescriptionSaysSpeedAndALapOnlyWhereThereIsOne() {
        val noTrack = OverlayFrame(speedKmh = 53.6, lap = null, position = null, g = null)
        assertEquals("Data: 54 km/h", overlayDescription(noTrack))
        val lap = OverlayLap(3, LapKind.Timed, 83_450_000, deltaMicros = null, isBest = false)
        assertEquals(
            "Data: 120 km/h, lap 3 1:23.4",
            overlayDescription(noTrack.copy(speedKmh = 120.0, lap = lap)),
        )
        assertEquals("Data: no speed", overlayDescription(noTrack.copy(speedKmh = null)))
        assertEquals("Data: none", overlayDescription(null))
    }
}
