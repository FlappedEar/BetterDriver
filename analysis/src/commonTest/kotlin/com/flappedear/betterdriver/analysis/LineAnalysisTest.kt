package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.line.LinePass
import com.flappedear.betterdriver.analysis.line.advise
import com.flappedear.betterdriver.analysis.line.beside
import com.flappedear.betterdriver.analysis.line.cornerWindows
import com.flappedear.betterdriver.analysis.line.signedSpan
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineAnalysisTest {
    private fun pass(brake: Double?, slowest: Double?, kmh: Double?, pickup: Double?, offset: Double?) =
        LinePass("", 1, 100.0, 10.0, brake, slowest, kmh, pickup, offset, emptyList())

    @Test
    fun `advice leaves out what the receiver repeats and says tighter toward the inside`() {
        val reference = pass(100.0, 150.0, 50.0, 160.0, 0.0)
        // Right turn: the inside is to the right (negative offsets).
        val target = pass(102.0, 145.0, 50.6, 155.0, -1.9)
        val advice = advise(reference, target, turnsLeft = false, axisLengthMeters = 2000.0)
        assertNull(advice.brakeMeters, "2 m is within 3 m")
        assertEquals(-5.0, advice.apexMeters!!, 1e-9)
        assertEquals(1.9, advice.tighterMeters!!, 1e-9)
        assertNull(advice.minimumSpeedKmh, "0.6 km/h is within 1 km/h")
        assertEquals(-5.0, advice.pickupMeters!!, 1e-9)
        // The same line on a left turn is wider; 1.4 m is the same line.
        assertEquals(-1.9, advise(reference, target, true, 2000.0).tighterMeters!!, 1e-9)
        assertNull(advise(reference, pass(100.0, 150.0, 50.0, 160.0, 1.4), true, 2000.0).tighterMeters)
    }

    @Test
    fun `distances along a loop go the short way across the line`() {
        assertEquals(10.0, signedSpan(1995.0, 5.0, 2000.0), 1e-9)
        assertEquals(-10.0, signedSpan(5.0, 1995.0, 2000.0), 1e-9)
        assertEquals(1990.0, signedSpan(5.0, 1995.0, 0.0), 1e-9)
    }

    @Test
    fun `corner windows reach to the middle of the straights and wrap on a loop`() {
        val axis = ProgressAxis(
            points = listOf(MetricPoint(0.0, 0.0), MetricPoint(1.0, 0.0)),
            cumulative = doubleArrayOf(0.0, 1.0),
            lengthMeters = 1000.0,
            spacingMeters = 1.0,
            valid = true,
        )
        val s = listOf(
            TrackSegment("s1", "S1", TrackSegmentType.Straight, 900.0, 100.0),
            TrackSegment("c1", "C1", TrackSegmentType.Corner, 100.0, 200.0),
            TrackSegment("c2", "C2", TrackSegmentType.Corner, 200.0, 300.0),
            TrackSegment("s2", "S2", TrackSegmentType.Straight, 300.0, 700.0),
            TrackSegment("c3", "C3", TrackSegmentType.Corner, 700.0, 900.0),
        )
        val w = cornerWindows(s, axis).map { it.window.startProgressMeters to it.window.endProgressMeters }
        assertEquals(listOf(0.0 to 200.0, 200.0 to 500.0, 500.0 to 0.0), w)
    }

    @Test
    fun `a point beside a loop's closing stretch moves toward the start`() {
        // A 40 m square loop sampled at its corners: the last 10 m run from (0, 10) back to (0, 0).
        val axis = ProgressAxis(
            points = listOf(
                MetricPoint(0.0, 0.0), MetricPoint(10.0, 0.0), MetricPoint(10.0, 10.0), MetricPoint(0.0, 10.0),
            ),
            cumulative = doubleArrayOf(0.0, 10.0, 20.0, 30.0),
            lengthMeters = 40.0,
            spacingMeters = 10.0,
            valid = true,
        )
        val p = beside(axis, 35.0, 1.0)!!
        // Halfway down the closing stretch, heading south; 1 m left of travel is east.
        assertEquals(1.0, p.eastMeters, 1e-9)
        assertEquals(5.0, p.northMeters, 1e-9)
    }
}
