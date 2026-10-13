package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.AnalysisProvenance
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.driving.DrivingState
import com.flappedear.betterdriver.analysis.driving.DrivingStateInterval
import com.flappedear.betterdriver.analysis.driving.LapDrivingStates
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.analysis.corners.BrakingMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapObservation
import com.flappedear.betterdriver.analysis.corners.CornerSpeedValue
import com.flappedear.betterdriver.analysis.corners.CornerSpeeds
import com.flappedear.betterdriver.analysis.corners.CornerVariability
import com.flappedear.betterdriver.analysis.corners.ExitMetrics
import com.flappedear.betterdriver.analysis.corners.ThrottlePickup
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.ConsistencySummary
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerDetailsTest {
    private fun metrics(lap: Int, brakes: Double, minimum: Double, pickup: Double?, provenance: String = "measured") =
        CornerLapMetrics(
            lap,
            CornerSpeeds(minimum = CornerSpeedValue(minimum), exit = CornerSpeedValue(minimum + 5), unit = "km/h"),
            BrakingMetrics(distanceBeforeEntryMeters = brakes, peakDeceleration = 0.8, provenance = provenance),
            ExitMetrics(pickup = ThrottlePickup(provenance = provenance, progressMeters = pickup)),
            CornerLapObservation(lap),
        )

    private val analysis = SessionAnalysis(
        LapSession(LapSessionStatus.entries.first()),
        segments = ApprovedSegments(
            listOf(
                TrackSegment("s0", "Straight 1", TrackSegmentType.Straight, 0.0, 950.0),
                TrackSegment("s1", "Corner 1", TrackSegmentType.Corner, 950.0, 50.0),
            ),
            "r",
        ),
        axis = ProgressAxis(lengthMeters = 1000.0),
        cornerMetrics = mapOf(
            "s1" to listOf(metrics(1, 60.0, 70.0, 980.0), metrics(2, 55.0, 68.0, 10.0, "inferred")),
        ),
        variability = mapOf(
            "s1" to CornerVariability(
                minimumSpeed = ConsistencySummary(count = 3, interquartileRange = 1.5, available = true),
                lineOffset = ConsistencySummary(count = 3, interquartileRange = 0.4, available = true),
                typicalGpsAccuracyMeters = 0.5,
                lineSpreadResolvable = false,
            ),
        ),
    )

    @Test
    fun `each corner compares the best lap with the compared lap`() {
        val corners = cornerDetails(analysis, bestIndex = 0, otherIndex = 1)
        assertEquals(listOf("Corner 1"), corners.map { it.name })
        val rows = corners.single().rows.associateBy { it.label }
        assertEquals(60.0, rows.getValue("Brakes").best!!, 1e-9)
        assertEquals(55.0, rows.getValue("Brakes").lap!!, 1e-9)
        assertEquals(1.5, rows.getValue("Min speed").spread!!, 1e-9)
        // The pickup is counted from the corner start, across the start line.
        assertEquals(30.0, rows.getValue("Throttle").best!!, 1e-9)
        assertEquals(60.0, rows.getValue("Throttle").lap!!, 1e-9)
        assertTrue(corners.single().lineWithinGps)
    }

    @Test
    fun `a line spread is not within GPS accuracy when no accuracy was recorded`() {
        val unknown = analysis.variability.getValue("s1").copy(typicalGpsAccuracyMeters = null)
        val corner = cornerDetails(analysis.copy(variability = mapOf("s1" to unknown)), 0, 1).single()
        assertFalse(corner.lineWithinGps)
        assertEquals(0.4, corner.lineSpreadMeters!!, 1e-9)
    }

    @Test
    fun `without a compared lap only the best lap is shown`() {
        val rows = cornerDetails(analysis, bestIndex = 0, otherIndex = null).single().rows
        assertTrue(rows.all { it.lap == null })
    }

    /** A lap at 20 m/s round the 1000 m axis, coasting from [from] to [to] metres, cornering elsewhere. */
    private fun states(lap: Int, from: Double, to: Double, provenance: String) = LapDrivingStates(
        lap,
        (0 until 100).map { k ->
            val start = k * 10.0
            val state = if (start >= from && start < to) DrivingState.Coasting else DrivingState.Cornering
            DrivingStateInterval(start / 20.0, (start + 10.0) / 20.0, start, start + 10.0, state)
        },
        provenance,
        lengthMeters = 1000.0,
    )

    /**
     * Coasting through each corner (FBBD-175) on the best and the compared lap, by the corner's
     * CornerId, across the start line; measured from the pedals it carries the less measured pedal's
     * provenance, inferred from the acceleration it is marked and at most Medium.
     */
    @Test
    fun `coasting in the corner is shown with where it comes from and how far it can be trusted`() {
        val sources = AnalysisProvenance(brake = Provenance.MEASURED_CAN, throttle = Provenance.CALCULATED)
        val measured = analysis.copy(
            drivingStates = listOf(states(1, 950.0, 970.0, "measured"), states(2, 980.0, 1030.0, "measured")),
            provenance = sources,
        )
        val row = cornerDetails(measured, 0, 1).single().rows.single { it.label == CoastingLabel }
        assertEquals(CornerMeasure.Seconds, row.measure)
        assertEquals(1.0, row.best!!, 1e-9)
        // 980 m to 1000 m: the axis wraps at the start line, so the rest of that coast is not in it.
        assertEquals(1.0, row.lap!!, 1e-9)
        assertEquals(Provenance.CALCULATED, row.provenance)
        assertTrue(row.marked)
        assertEquals("≈1.00 s", marked(formatMeasure(row.best, row.measure, ""), row.provenance))

        val allMeasured = measured.copy(provenance = sources.copy(throttle = Provenance.MEASURED_OBD))
        val plain = cornerDetails(allMeasured, 0, 1).single().rows.single { it.label == CoastingLabel }
        assertFalse(plain.marked)
        assertEquals(Confidence.HIGH, plain.confidence!!.level)

        val inferred = analysis.copy(drivingStates = listOf(states(1, 950.0, 970.0, "inferred")))
        val guess = cornerDetails(inferred, 0, 1).single().rows.single { it.label == CoastingLabel }
        assertEquals(Provenance.INFERRED, guess.provenance)
        assertEquals(Confidence.MEDIUM, guess.confidence!!.level)
        // No states for the compared lap: nothing for it.
        assertNull(guess.lap)
        // A session without driving states shows the row empty, unmarked.
        val none = cornerDetails(analysis, 0, 1).single().rows.single { it.label == CoastingLabel }
        assertNull(none.best)
        assertFalse(none.marked)
    }

    @Test
    fun `values are written in their measure`() {
        assertEquals("0.47 s", formatMeasure(0.466, CornerMeasure.Seconds, ""))
        assertEquals("61 m", formatMeasure(60.6, CornerMeasure.Meters, ""))
        assertEquals("0.83 g", formatMeasure(0.826, CornerMeasure.G, ""))
        assertEquals("63.9 km/h", formatMeasure(63.89, CornerMeasure.Speed, "km/h"))
        assertEquals("–", formatMeasure(null, CornerMeasure.Speed, "km/h"))
        assertNull(cornerDetails(analysis.copy(cornerMetrics = emptyMap()), 0, 1).firstOrNull())
    }
}
