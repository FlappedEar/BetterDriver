package com.flappedear.betterdriver.feature.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One name for one thing on every screen of the analysis (1.0 cleanup): the best actual lap, the
 * realistic optimal and the raw theoretical best as the summary names them (FBBD-178), one way to
 * every pass through a corner, and the laps of a composite lap written as its video writes them.
 */
class ConsistentNamesTest {
    @Test
    fun `where time goes names the lap that set each best, never as the best lap`() {
        assertEquals("Best actual lap 2 against the best of each corner", timeGoesHeading(PreviewUi))
        val row = GainRow("Corner 1", 0.2, bestLapNumber = 5)
        assertEquals("lap 5", setOnText(row))
        assertFalse(setOnText(row).contains("best"))
        assertEquals("", setOnText(row.copy(bestLapNumber = null)))
    }

    @Test
    fun `the realistic optimal lists its laps as its video does`() {
        assertEquals("lap 4", lapsText(listOf(4)))
        assertEquals("laps 2 and 4", lapsText(listOf(2, 4)))
        assertEquals("laps 2, 4 and 7", lapsText(listOf(2, 4, 7)))
        assertEquals("≈−0.34 s · from laps 2 and 3", realisticDetail(PreviewUi))
        val bestOnly = PreviewUi.copy(realisticLaps = listOf(2), realisticGainSeconds = 0.0)
        assertEquals("≈+0.00 s · no better combination", realisticDetail(bestOnly))
    }

    @Test
    fun `without a realistic optimal the summary says why, not which joints it would use`() {
        val noChain = PreviewUi.copy(realisticMillis = null, realisticGainSeconds = null, realisticLaps = emptyList())
        assertEquals("No compatible combination of laps", realisticDetail(noChain))
        val noRaw = noChain.copy(theoreticalMillis = null, gainSeconds = null)
        assertEquals("Needs every corner timed", realisticDetail(noRaw))
    }

    @Test
    fun `the raw theoretical best by corner has a compared lap column only with a compared lap`() {
        assertEquals("Lap ${PreviewUi.compare!!.otherNumber}", comparedColumn(PreviewUi))
        assertNull(comparedColumn(PreviewUi.copy(compare = null)))
    }

    @Test
    fun `every pass through a corner has one name, on its way in and on its screen`() {
        assertEquals("Every pass ›", EveryPassText)
        assertTrue(EveryPassText.startsWith(EveryPassTitle))
    }
}
