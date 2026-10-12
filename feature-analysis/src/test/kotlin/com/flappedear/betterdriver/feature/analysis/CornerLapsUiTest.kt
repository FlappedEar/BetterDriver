package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.corners.CornerPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerLapsUiTest {
    private fun pass(index: Int, seconds: Double?, braking: Double? = 40.0, inferred: Boolean = false) =
        CornerPass(index, index + 1, 10.0, 14.0, seconds, null, braking, inferred, 80.4, "km/h")

    private val passes = listOf(pass(1, 4.2), pass(0, 4.5, inferred = true), pass(3, null, braking = null))

    private val ui = cornerLapsUi(
        "c1",
        "Corner 1",
        passes,
        video = { if (it.lapIndex == 0) null else CornerVideoUi("/v/${it.lapNumber}.mp4", 1_000, 5_000) },
        reviewable = { it.lapIndex != 3 },
    )

    @Test
    fun `rows keep the passes' order, with the fastest highlighted and the others against it`() {
        assertEquals(listOf(1, 0, 3), ui.rows.map { it.lapIndex })
        assertEquals("km/h", ui.speedUnit)
        val (best, second, untimed) = ui.rows
        assertTrue(best.best)
        assertEquals(0.0, best.deltaSeconds!!, 1e-9)
        assertFalse(second.best)
        assertEquals(0.3, second.deltaSeconds!!, 1e-9)
        assertNull(untimed.deltaSeconds)
        assertFalse(untimed.best)
        assertTrue(ui.anyVideo)
        assertNull(ui.row(0)!!.video)
        assertEquals("/v/2.mp4", ui.row(1)!!.video!!.path)
        assertFalse(ui.row(3)!!.reviewable)
    }

    @Test
    fun `braking and minimum speed are written as on the corner details`() {
        assertEquals("40 m", brakingText(ui.rows[0]))
        assertEquals("≈40 m", brakingText(ui.rows[1]))
        assertEquals("–", brakingText(ui.rows[2]))
        assertEquals("80 km/h", minimumText(ui.rows[0], ui.speedUnit))
        assertEquals("80", minimumText(ui.rows[0], ""))
    }

    @Test
    fun `two laps are ticked at most, the faster first for the review`() {
        var ticked = toggleTicked(emptyList(), 3)
        ticked = toggleTicked(ticked, 0)
        assertEquals(0 to 3, comparedPair(ui, ticked))
        // A third tick drops the first.
        ticked = toggleTicked(ticked, 1)
        assertEquals(listOf(0, 1), ticked)
        assertEquals(1 to 0, comparedPair(ui, ticked))
        ticked = toggleTicked(ticked, 1)
        assertEquals(listOf(0), ticked)
        assertNull(comparedPair(ui, ticked))
        assertEquals(listOf(0, 1), decodeTicked(encodeTicked(listOf(0, 1))))
        assertEquals(emptyList<Int>(), decodeTicked(encodeTicked(emptyList())))
    }

    @Test
    fun `a corner with no timed pass has no best`() {
        val none = cornerLapsUi("c1", "Corner 1", listOf(pass(0, null)), { null }, { true })
        assertFalse(none.rows.single().best)
        assertFalse(none.anyVideo)
    }
}
