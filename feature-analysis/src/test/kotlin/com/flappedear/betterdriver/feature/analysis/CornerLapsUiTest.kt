package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.corners.CornerPass
import com.flappedear.betterdriver.analysis.corners.CornerTraces
import com.flappedear.betterdriver.analysis.corners.PassTrace
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.Provenance
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

    /** No number below High confidence (FBBD-177): the pass shows, its numbers do not, and it is never the fastest. */
    @Test
    fun `a pass below High confidence shows no numbers and is never the fastest`() {
        val medium = ConfidenceUi(Confidence.MEDIUM, "positions weak here")
        val gated = cornerLapsUi(
            "c1",
            "Corner 1",
            passes,
            video = { null },
            reviewable = { true },
            marks = PassMarks(confidence = { if (it.lapIndex == 1) medium else ConfidenceUi.High }),
        )
        val weak = gated.row(1)!!
        assertNull(weak.seconds)
        assertNull(weak.deltaSeconds)
        assertFalse(weak.best)
        assertEquals(medium, weak.confidence)
        assertEquals("–", passSecondsText(weak))
        assertEquals("Brakes – · Min –", passDetailText(gated, weak))
        // The next precise pass is the fastest, and the others are measured against it.
        assertTrue(gated.row(0)!!.best)
        assertEquals("4.50 s", passSecondsText(gated.row(0)!!))
        assertEquals("Brakes ≈40 m · Min 80 km/h", passDetailText(gated, gated.row(0)!!))
    }

    @Test
    fun `the best lap's and the realistic optimal's passes are marked`() {
        val marked = cornerLapsUi(
            "c1", "Corner 1", passes, { null }, { true }, marks = PassMarks(bestLapIndex = 0, optimalLapIndex = 1),
        )
        assertTrue(marked.row(0)!!.bestLap)
        assertTrue(marked.row(1)!!.optimal)
        assertEquals("Brakes ≈40 m · Min 80 km/h · Best actual lap", passDetailText(marked, marked.row(0)!!))
        assertEquals("Brakes 40 m · Min 80 km/h · In the realistic optimal", passDetailText(marked, marked.row(1)!!))
        assertEquals("Lap 1 · best actual lap", passName(marked.row(0)!!))
        assertEquals("Lap 2 · fastest here", passName(marked.row(1)!!))
        // A speed in no known unit is no number.
        val noUnit = marked.copy(speedUnit = "")
        assertEquals("Brakes 40 m · Min – · In the realistic optimal", passDetailText(noUnit, marked.row(1)!!))
    }

    @Test
    fun `the traces draw the ticked passes strongly, else the fastest and the best lap's`() {
        val marked = cornerLapsUi("c1", "Corner 1", passes, { null }, { true }, marks = PassMarks(bestLapIndex = 0))
        // Rows run fastest first: lap index 1, 0, then 3.
        assertEquals(Highlight(1, 0), highlightedPasses(marked, emptyList()))
        assertEquals(Highlight(0, 3), highlightedPasses(marked, listOf(3, 0)))
        assertEquals(Highlight(3, null), highlightedPasses(marked, listOf(3)))
        // The best lap is also the fastest through the corner: one strong line.
        val same = cornerLapsUi("c1", "Corner 1", passes, { null }, { true }, marks = PassMarks(bestLapIndex = 1))
        assertEquals(Highlight(1, null), highlightedPasses(same, emptyList()))
    }

    @Test
    fun `the compare bar says whether the two go side by side on video`() {
        assertEquals("Tick two laps to compare", compareText(ui, null))
        assertEquals("Lap 2 against lap 4, videos side by side", compareText(ui, 1 to 3))
        // Lap index 0 has no video: the lap review still compares them on its charts.
        assertEquals("Lap 2 against lap 1", compareText(ui, 1 to 0))
    }

    @Test
    fun `the speed trace carries numbers only for a measured speed in a known unit`() {
        val run = listOf(ChartPoint(0.0, 92.4), ChartPoint(50.0, 61.6), ChartPoint(100.0, 88.0))
        val pass = PassTrace(0, listOf(run))
        val traces = CornerTraces(180.0, 120.0, 160.0, listOf(pass), "km/h", Provenance.MEASURED_GPS)
        assertTrue(speedNumbersShown(traces))
        assertEquals("Speed · 62–92 km/h", speedLabel(ui, traces))
        assertEquals("120 m", approachText(traces))
        assertEquals("≈Speed", speedLabel(ui, traces.copy(speedProvenance = Provenance.CALCULATED)))
        assertEquals("Speed", speedLabel(ui, traces.copy(speedUnit = "")))
        assertEquals("Speed", speedLabel(ui, traces.copy(speedProvenance = null)))
    }

    @Test
    fun `the speed range leaves out a doubtful pass, whose numbers are shown nowhere`() {
        val trusted = PassTrace(1, listOf(listOf(ChartPoint(0.0, 92.4), ChartPoint(50.0, 61.6))))
        val doubtful = PassTrace(0, listOf(listOf(ChartPoint(0.0, 131.0), ChartPoint(50.0, 40.0))))
        val traces = CornerTraces(180.0, 120.0, 160.0, listOf(trusted, doubtful), "km/h", Provenance.MEASURED_GPS)
        val medium = PassMarks(
            confidence = { if (it.lapIndex == 0) ConfidenceUi(Confidence.MEDIUM) else ConfidenceUi.High },
        )
        val graded = cornerLapsUi("c1", "Corner 1", passes, { null }, { true }, marks = medium)
        assertEquals("Speed · 62–92 km/h", speedLabel(graded, traces))
        // Every pass doubtful: the trace is a shape, with no numbers.
        val low = PassMarks(confidence = { ConfidenceUi(Confidence.LOW) })
        val none = cornerLapsUi("c1", "Corner 1", passes, { null }, { true }, marks = low)
        assertEquals("Speed", speedLabel(none, traces))
    }

    @Test
    fun `a corner of another revision says so, with no passes`() {
        val other = cornerLapsUi(SessionAnalysis(LapSession(LapSessionStatus.Available)), "c-old", { null }, { true })
        assertTrue(other.notInSession)
        assertTrue(other.rows.isEmpty())
        assertNull(other.traces)
        assertFalse(ui.notInSession)
    }

    @Test
    fun `a corner with no timed pass has no best`() {
        val none = cornerLapsUi("c1", "Corner 1", listOf(pass(0, null)), { null }, { true })
        assertFalse(none.rows.single().best)
        assertFalse(none.anyVideo)
    }
}
