package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.AnalysisProvenance
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.corners.BrakingMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapObservation
import com.flappedear.betterdriver.analysis.corners.CornerPass
import com.flappedear.betterdriver.analysis.corners.CornerSpeedValue
import com.flappedear.betterdriver.analysis.corners.CornerSpeeds
import com.flappedear.betterdriver.analysis.corners.ExitMetrics
import com.flappedear.betterdriver.analysis.corners.ThrottlePickup
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A value that is not measured is never shown as measured (FBBD-174). */
class ProvenanceMarkTest {
    @Test
    fun `every kind that is not measured is marked, every measured kind is not`() {
        for (kind in Provenance.entries) {
            val expected = if (kind.measured) "61 m" else "≈61 m"
            assertEquals(kind.name, expected, marked("61 m", kind))
            assertEquals(kind.name, !kind.measured, kind.notMeasured)
        }
        for (kind in listOf(Provenance.CALCULATED, Provenance.INFERRED, Provenance.FUSED, Provenance.INTERPOLATED)) {
            assertTrue(marked("1:23.456", kind).startsWith(NotMeasuredMark))
        }
        // Not known (a session from elsewhere) is not marked, and a missing value is never marked.
        assertEquals("61 m", marked("61 m", null))
        assertEquals("–", marked("–", Provenance.CALCULATED))
        assertTrue(ProvenanceLegendText.startsWith(NotMeasuredMark))
    }

    private fun metrics(lap: Int, method: String) = CornerLapMetrics(
        lap,
        CornerSpeeds(minimum = CornerSpeedValue(70.0), exit = CornerSpeedValue(75.0), unit = "km/h"),
        BrakingMetrics(distanceBeforeEntryMeters = 60.0, peakDeceleration = 0.8, provenance = method),
        ExitMetrics(pickup = ThrottlePickup(provenance = method, progressMeters = 980.0)),
        CornerLapObservation(lap),
    )

    private fun analysis(method: String, sources: AnalysisProvenance) = SessionAnalysis(
        LapSession(
            LapSessionStatus.entries.first(),
            timedLaps = listOf(TimedLap(1, 0.0, 80.0, 80.0), TimedLap(2, 80.0, 161.0, 81.0)),
            fastestLapIndex = 0,
        ),
        segments = ApprovedSegments(
            listOf(TrackSegment("s1", "Corner 1", TrackSegmentType.Corner, 950.0, 50.0)),
            "r",
        ),
        axis = ProgressAxis(lengthMeters = 1000.0),
        cornerMetrics = mapOf("s1" to listOf(metrics(1, method), metrics(2, method))),
        provenance = sources,
    )

    @Test
    fun `corner details carry the provenance of each value`() {
        // Braking and pickup inferred from a measured accelerometer; speed calculated from positions.
        val sources = AnalysisProvenance(
            position = Provenance.MEASURED_GPS,
            speed = Provenance.CALCULATED,
            acceleration = Provenance.MEASURED_ACCELEROMETER,
        )
        val rows = cornerDetails(analysis("inferred", sources), 0, 1).single().rows.associateBy { it.label }
        assertEquals(Provenance.INFERRED, rows.getValue("Brakes").provenance)
        assertEquals(Provenance.INFERRED, rows.getValue("Throttle").provenance)
        assertEquals(Provenance.MEASURED_ACCELEROMETER, rows.getValue("Peak braking").provenance)
        assertEquals(Provenance.CALCULATED, rows.getValue("Min speed").provenance)
        assertEquals(Provenance.CALCULATED, rows.getValue("Exit speed").provenance)
        val marked = rows.values.filter { it.marked }.map { it.label }
        assertEquals(listOf("Brakes", "Min speed", "Exit speed", "Throttle"), marked)
    }

    @Test
    fun `a session measured throughout marks nothing in its corners`() {
        val sources = AnalysisProvenance(
            position = Provenance.MEASURED_GNSS_RECEIVER,
            speed = Provenance.MEASURED_GNSS_RECEIVER,
            acceleration = Provenance.MEASURED_ACCELEROMETER,
            brake = Provenance.MEASURED_CAN,
            throttle = Provenance.MEASURED_OBD,
        )
        val rows = cornerDetails(analysis("measured", sources), 0, 1).single().rows
        assertTrue(rows.none { it.marked })
        assertEquals(Provenance.MEASURED_CAN, rows.first { it.label == "Brakes" }.provenance)
        assertEquals(Provenance.MEASURED_OBD, rows.first { it.label == "Throttle" }.provenance)
    }

    @Test
    fun `the theoretical best and the time to gain are always marked as calculated`() {
        val ui = analysisUi("t", analysis("measured", AnalysisProvenance()), null)!!
            .copy(theoreticalMillis = 79_500L, gainSeconds = 0.5)
        assertEquals("≈79500", theoreticalText(ui) { it.toString() })
        assertEquals("≈-0.5", gainText(ui) { it.toString() })
        assertEquals("–", theoreticalText(ui.copy(theoreticalMillis = null)) { it.toString() })
        assertEquals(Provenance.CALCULATED, TheoreticalProvenance)
        assertEquals(Provenance.CALCULATED, DeltaProvenance)
    }

    @Test
    fun `the analysis screen and corner laps know where the speeds come from`() {
        val sources = AnalysisProvenance(speed = Provenance.SIMULATED)
        val ui = analysisUi("t", analysis("measured", sources), null)!!
        assertEquals(sources, ui.sources)

        val pass = CornerPass(0, 1, 10.0, 14.0, 4.2, null, 40.0, true, 80.4, "km/h")
        val laps = cornerLapsUi("s1", "Corner 1", listOf(pass), { null }, { true }, Provenance.CALCULATED)
        val row = laps.rows.single()
        assertEquals("≈40 m", brakingText(row))
        assertEquals("≈80 km/h", minimumText(row, laps.speedUnit, laps.speedProvenance))
        assertTrue(laps.anyMarked)
        val measured = cornerLapsUi("s1", "Corner 1", listOf(pass.copy(brakingInferred = false)), { null }, { true })
        assertEquals("80 km/h", minimumText(measured.rows.single(), measured.speedUnit, measured.speedProvenance))
        assertFalse(measured.anyMarked)
    }
}
