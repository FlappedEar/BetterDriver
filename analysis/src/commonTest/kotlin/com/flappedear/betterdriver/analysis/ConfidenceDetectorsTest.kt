@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.confidence.Codes
import com.flappedear.betterdriver.analysis.confidence.LapFix
import com.flappedear.betterdriver.analysis.confidence.adjacentTrack
import com.flappedear.betterdriver.analysis.confidence.differentLine
import com.flappedear.betterdriver.analysis.confidence.freshTimes
import com.flappedear.betterdriver.analysis.confidence.gnssGap
import com.flappedear.betterdriver.analysis.confidence.hairpinCrossover
import com.flappedear.betterdriver.analysis.confidence.hairpinLegs
import com.flappedear.betterdriver.analysis.confidence.pitLane
import com.flappedear.betterdriver.analysis.confidence.poorHeading
import com.flappedear.betterdriver.analysis.confidence.projectionAmbiguity
import com.flappedear.betterdriver.analysis.confidence.veryLowSpeed
import com.flappedear.betterdriver.analysis.confidence.weakInterpolation
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.recorder.Confidence
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun distance(a: MetricPoint, b: MetricPoint) = hypot(a.eastMeters - b.eastMeters, a.northMeters - b.northMeters)

/** Fixture tests of each detector of weak position evidence (FBBD-177): one that finds it, one that does not. */
class ConfidenceDetectorsTest {
    /**
     * A stadium: [straight] metres east along y = 0, a left half-circle of [radius], the straight back
     * west at y = 2 [radius], and a half-circle back to the start; a point every metre or so.
     */
    private class Stadium(val straight: Double, val radius: Double) {
        val points: List<MetricPoint> = buildList {
            for (i in 0 until straight.toInt()) add(MetricPoint(i.toDouble(), 0.0))
            val n = (PI * radius).toInt()
            for (k in 0 until n) {
                val a = -PI / 2 + PI * k / n
                add(MetricPoint(straight + radius * cos(a), radius + radius * sin(a)))
            }
            for (i in 0 until straight.toInt()) add(MetricPoint(straight - i, 2 * radius))
            for (k in 0 until n) {
                val a = PI / 2 + PI * k / n
                add(MetricPoint(radius * cos(a), radius + radius * sin(a)))
            }
        }

        val axis: ProgressAxis = run {
            val cumulative = DoubleArray(points.size)
            for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
            val length = cumulative.last() + distance(points.last(), points.first())
            ProgressAxis(points, cumulative, length, length / points.size, GeoCoordinate(50.0, 14.0), true, true)
        }

        /** The first turn, from the end of the first straight to the start of the second. */
        val turnStart = straight
        val turnEnd = straight + PI * radius

        /** A lap's fixes along the axis from [from] to [to] metres, [step] metres apart, each where it is. */
        fun fixes(from: Double, to: Double, step: Double = 2.0): List<LapFix> {
            val out = mutableListOf<LapFix>()
            var s = from
            var t = 0.0
            while (s <= to) {
                out += LapFix(t, pointAt(s), s)
                s += step
                t += 0.04
            }
            return out
        }

        fun pointAt(s: Double): MetricPoint {
            val i = axis.cumulative.indexOfLast { it <= s }.coerceAtLeast(0)
            return points[i]
        }
    }

    @Test
    fun `a GNSS gap is found where positions stop - not where they come steadily or slowly`() {
        val steady = (0..50).map { it * 0.04 }
        assertNull(gnssGap(steady, 0.0, 2.0, 0.04))
        val gap = gnssGap(steady.filter { it < 0.8 || it > 1.6 }, 0.0, 2.0, 0.04)
        assertEquals(Codes.GNSS_GAP, assertNotNull(gap).code)
        assertEquals(Confidence.LOW, gap.cap)
        // A 1 Hz receiver's second between positions is its rate, not a gap.
        assertNull(gnssGap(listOf(0.0, 1.0, 2.0, 3.0), 0.0, 3.0, 1.0))
    }

    @Test
    fun `a held position counts as no position - so the dropout behind it is a gap`() {
        val moving = Stadium(200.0, 60.0).fixes(0.0, 40.0)
        assertEquals(moving.map { it.time }, freshTimes(moving))
        val held = moving.mapIndexed { i, f -> if (i in 5..20) f.copy(point = moving[5].point) else f }
        val fresh = freshTimes(held)
        assertNotNull(gnssGap(fresh, 0.0, held.last().time, 0.04))
        assertNull(gnssGap(freshTimes(moving), 0.0, moving.last().time, 0.04))
    }

    @Test
    fun `projection ambiguity is found when many positions could not be placed`() {
        assertEquals(Codes.AMBIGUOUS, assertNotNull(projectionAmbiguity(50, 35)).code)
        assertNull(projectionAmbiguity(50, 48))
        assertNull(projectionAmbiguity(2, 0), "too few positions to say")
    }

    @Test
    fun `adjacent track is found where another part of the track runs close`() {
        val narrow = Stadium(200.0, 8.0)
        val near = assertNotNull(adjacentTrack(narrow.axis, 60.0, 140.0))
        assertEquals(Codes.ADJACENT, near.code)
        assertEquals(Confidence.MEDIUM, near.cap)
        val wide = Stadium(200.0, 60.0)
        assertNull(adjacentTrack(wide.axis, 60.0, 140.0))
    }

    @Test
    fun `a hairpin crossover is a position on the other leg - only in a hairpin`() {
        val hairpin = Stadium(200.0, 15.0)
        assertTrue(hairpinLegs(hairpin.axis, hairpin.turnStart, hairpin.turnEnd))
        val clean = hairpin.fixes(hairpin.turnStart - 20, hairpin.turnEnd + 20)
        assertNull(hairpinCrossover(hairpin.axis, hairpin.turnStart, hairpin.turnEnd, clean))
        // A position on the way out of the turn put on the way in, 30 m across.
        val i = clean.indexOfFirst { it.progress!! > hairpin.turnEnd + 10 }
        val crossed = clean.toMutableList().also {
            val f = it[i]
            it[i] = f.copy(point = MetricPoint(f.point.eastMeters, 0.0))
        }
        val found = assertNotNull(hairpinCrossover(hairpin.axis, hairpin.turnStart, hairpin.turnEnd, crossed))
        assertEquals(Codes.HAIRPIN, found.code)
        assertEquals(Confidence.LOW, found.cap)
        // A wide turn has no legs to cross between.
        val wide = Stadium(200.0, 60.0)
        assertTrue(!hairpinLegs(wide.axis, wide.turnStart, wide.turnEnd))
        val far = wide.fixes(wide.turnStart, wide.turnEnd)
            .map { it.copy(point = MetricPoint(it.point.eastMeters, 0.0)) }
        assertNull(hairpinCrossover(wide.axis, wide.turnStart, wide.turnEnd, far))
    }

    @Test
    fun `poor heading agreement is found when the car moves across the track`() {
        val track = Stadium(300.0, 60.0)
        assertNull(poorHeading(track.axis, 50.0, 150.0, track.fixes(50.0, 150.0)))
        val across = (0..20).map { k -> LapFix(k * 0.1, MetricPoint(60.0 + k * 1.5, k * 1.5 - 10.0), null) }
        val found = assertNotNull(poorHeading(track.axis, 50.0, 150.0, across))
        assertEquals(Codes.HEADING, found.code)
        assertEquals(Confidence.MEDIUM, found.cap)
    }

    @Test
    fun `very low speed is a stop or a spin`() {
        assertEquals(Codes.VERY_LOW_SPEED, assertNotNull(veryLowSpeed(3.0)).code)
        assertNull(veryLowSpeed(15.0))
        assertNull(veryLowSpeed(null))
    }

    @Test
    fun `the pit lane is far off the line for much of the segment`() {
        assertEquals(Codes.PIT_LANE, assertNotNull(pitLane(List(10) { 22.0 })).code)
        assertNull(pitLane(List(10) { 2.0 }))
        assertNull(pitLane(List(9) { 2.0 } + 30.0), "one stray position is not the pit lane")
    }

    @Test
    fun `a very different line is several metres off the fastest lap's - but not the pit lane`() {
        val found = assertNotNull(differentLine(List(10) { 7.0 }))
        assertEquals(Codes.DIFFERENT_LINE, found.code)
        assertEquals(Confidence.MEDIUM, found.cap)
        assertNull(differentLine(List(10) { 2.0 }))
        assertNull(differentLine(List(10) { 25.0 }), "the pit lane is its own finding")
    }

    @Test
    fun `weak interpolation is positions too far apart or too few`() {
        assertNull(weakInterpolation((0..25).map { it * 0.04 }))
        val slow = assertNotNull(weakInterpolation((0..8).map { it * 0.25 }))
        assertEquals(Confidence.MEDIUM, slow.cap)
        val oneHz = assertNotNull(weakInterpolation((0..5).map { it * 1.0 }))
        assertEquals(Confidence.LOW, oneHz.cap)
        assertEquals(Confidence.LOW, assertNotNull(weakInterpolation(listOf(0.0, 0.1))).cap)
        assertEquals(Codes.WEAK_INTERPOLATION, oneHz.code)
    }
}
