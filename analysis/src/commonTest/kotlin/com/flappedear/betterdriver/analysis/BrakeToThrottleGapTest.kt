@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceInferred
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceMeasured
import com.flappedear.betterdriver.analysis.driving.DrivingState
import com.flappedear.betterdriver.analysis.driving.DrivingStateInterval
import com.flappedear.betterdriver.analysis.driving.LapDrivingStates
import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.learning.GapComparisonTooFewCorners
import com.flappedear.betterdriver.analysis.learning.GapExcludedDataGap
import com.flappedear.betterdriver.analysis.learning.GapExcludedLapNotCounted
import com.flappedear.betterdriver.analysis.learning.GapExcludedNoBraking
import com.flappedear.betterdriver.analysis.learning.GapUnavailableNoSegments
import com.flappedear.betterdriver.analysis.learning.GapUnavailableNotMeasured
import com.flappedear.betterdriver.analysis.learning.brakeToThrottleGaps
import com.flappedear.betterdriver.analysis.learning.logLines
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The brake-to-throttle gap detector (FBBD-417) on hand-made laps: a 1,000 m lap at 20 m/s with four
 * corners, braked for before each and back on the accelerator after a known time off both pedals.
 */
class BrakeToThrottleGapTest {
    private val speed = 20.0
    private val step = 0.1
    private val length = 1000.0

    /** Corners 100 m long starting at these metres. */
    private val starts = listOf(100.0, 350.0, 600.0, 850.0)

    private val segments = ApprovedSegments(
        starts.mapIndexed { i, s ->
            TrackSegment("c${i + 1}", "Corner ${i + 1}", TrackSegmentType.Corner, s, s + 100.0)
        },
        "test",
    )

    /**
     * One lap starting at [t0]: full throttle, braking from 60 m to 10 m before each corner, then
     * [gaps] seconds off both pedals (one per corner) and on the accelerator again. Stretches whose
     * start falls in [holes] (metres) have no state, as where a pedal channel had no value.
     */
    private fun lap(
        number: Int,
        t0: Double,
        gaps: List<Double>,
        holes: ClosedFloatingPointRange<Double>? = null,
        provenance: String = BrakingProvenanceMeasured,
    ): LapDrivingStates {
        @Suppress("ReturnCount")
        fun state(m: Double): DrivingState {
            for ((i, s) in starts.withIndex()) {
                val releaseAt = s - 10.0
                if (m >= s - 60.0 && m < releaseAt) return DrivingState.Braking
                if (m >= releaseAt && m < releaseAt + gaps[i] * speed - 1e-9) return DrivingState.Coasting
                if (m >= releaseAt && m < s + 100.0) return DrivingState.Accelerating
            }
            return DrivingState.FullThrottle
        }
        val n = (length / (speed * step)).toInt()
        val intervals = (0 until n).mapNotNull { k ->
            val m = k * speed * step
            if (holes != null && m in holes) return@mapNotNull null
            DrivingStateInterval(t0 + k * step, t0 + (k + 1) * step, m, m + speed * step, state(m))
        }
        return LapDrivingStates(number, intervals, provenance, lengthMeters = length)
    }

    private val lapSeconds = length / speed

    private fun analysis(
        laps: List<LapDrivingStates>,
        issues: Map<Int, LapReferenceIssue> = emptyMap(),
        segments: ApprovedSegments? = this.segments,
    ): SessionAnalysis {
        val timed = laps.indices.map { i ->
            val t0 = i * lapSeconds
            TimedLap(i + 1, t0, t0 + lapSeconds, lapSeconds, referenceIssue = issues[i + 1] ?: LapReferenceIssue.None)
        }
        return SessionAnalysis(
            LapSession(LapSessionStatus.Available, timedLaps = timed, fastestLapIndex = 0),
            segments = segments,
            drivingStates = laps,
        )
    }

    /** Six laps, Corner 3 with a 1 s gap on every one of them, the others 0.2 s. No pass is better. */
    private val everyLap = List(6) { i -> lap(i + 1, i * lapSeconds, listOf(0.2, 0.2, 1.0, 0.2)) }

    @Test
    fun `a gap on every lap is found with no better pass to compare with - and said as observed`() {
        val result = brakeToThrottleGaps(analysis(everyLap))
        assertTrue(result.available)
        assertFalse(result.validated)
        val c3 = result.corners.single { it.segmentId == "c3" }
        assertTrue(c3.flagged)
        assertEquals(6, c3.passes.size)
        assertEquals(6, c3.passesWithGap)
        assertEquals(1.0, c3.medianSeconds!!, 0.051)
        // Release at 590 m, throttle 20 m later.
        assertEquals(590.0, c3.passes.first().releaseMeters, 1e-6)
        assertEquals(610.0, c3.passes.first().throttleMeters, 1e-6)
        assertEquals(listOf("c3"), result.observations.map { it.segmentId })
        val o = result.observations.single()
        assertEquals(0.2, o.othersMedianSeconds, 0.051)
        assertEquals((1..6).toList(), o.lapNumbers)
        assertTrue(o.text.startsWith("Observed in Corner 3: 1.0 s off both pedals"), o.text)
        assertFalse(o.text.contains("mistake", ignoreCase = true), o.text)
        assertTrue(result.corners.filter { it.segmentId != "c3" }.none { it.flagged })
        val summary = result.logLines().first()
        assertTrue(summary.startsWith("gap.summary corners=4 judged=4 flagged=1"), summary)
        assertTrue(result.logLines().any { it.startsWith("gap.corner id=c3 FLAG median=1.00 used=6 with_gap=6") })
    }

    @Test
    fun `the same gap everywhere is not flagged`() {
        val laps = List(6) { i -> lap(i + 1, i * lapSeconds, listOf(1.0, 1.0, 1.0, 1.0)) }
        val result = brakeToThrottleGaps(analysis(laps))
        assertTrue(result.observations.isEmpty())
        assertTrue(result.corners.none { it.flagged })
    }

    @Test
    fun `a gap on a minority of laps is not flagged`() {
        val laps = List(6) { i -> lap(i + 1, i * lapSeconds, listOf(0.2, 0.2, if (i < 2) 1.5 else 0.2, 0.2)) }
        assertTrue(brakeToThrottleGaps(analysis(laps)).observations.isEmpty())
    }

    @Test
    fun `without measured pedals the detector is off and logs why`() {
        val laps = List(6) { i ->
            lap(i + 1, i * lapSeconds, listOf(0.2, 0.2, 1.0, 0.2), provenance = BrakingProvenanceInferred)
        }
        val result = brakeToThrottleGaps(analysis(laps))
        assertFalse(result.available)
        assertEquals(GapUnavailableNotMeasured, result.unavailableReason)
        assertTrue(result.observations.isEmpty())
        assertEquals(listOf("gap.off reason=pedalsNotMeasured algorithm=brake-to-throttle-gap-v1"), result.logLines())
    }

    @Test
    fun `without corners the detector is off`() {
        val result = brakeToThrottleGaps(analysis(everyLap, segments = null))
        assertEquals(GapUnavailableNoSegments, result.unavailableReason)
    }

    @Test
    fun `a pass whose pedal data stops between release and throttle is excluded with its reason`() {
        // On laps 2 and 4 the pedals go silent for 8 m (0.4 s) right after the brake at Corner 3.
        val laps = everyLap.mapIndexed { i, l ->
            if (i == 1 || i == 3) lap(i + 1, i * lapSeconds, listOf(0.2, 0.2, 1.0, 0.2), holes = 592.0..599.9) else l
        }
        val c3 = brakeToThrottleGaps(analysis(laps)).corners.single { it.segmentId == "c3" }
        assertEquals(listOf(1, 3, 5, 6), c3.passes.map { it.lapNumber })
        assertEquals(listOf(2, 4), c3.excluded.filter { it.reason == GapExcludedDataGap }.map { it.lapNumber })
        assertTrue(c3.flagged)
    }

    @Test
    fun `a stale channel on most laps leaves too few passes - and nothing is claimed`() {
        val laps = everyLap.mapIndexed { i, l ->
            if (i < 3) lap(i + 1, i * lapSeconds, listOf(0.2, 0.2, 1.0, 0.2), holes = 592.0..599.9) else l
        }
        val result = brakeToThrottleGaps(analysis(laps))
        val c3 = result.corners.single { it.segmentId == "c3" }
        assertFalse(c3.flagged)
        assertEquals(3, c3.passes.size)
        assertTrue(result.observations.isEmpty())
    }

    @Test
    fun `laps that do not count and corners taken flat give no gap`() {
        val issues = mapOf(1 to LapReferenceIssue.entries.first { it != LapReferenceIssue.None })
        // No braking before Corner 4 on any lap: the corner is not judged, and too few remain to compare.
        val flat = List(6) { i ->
            val l = lap(i + 1, i * lapSeconds, listOf(0.2, 0.2, 1.0, 0.2))
            val flatOut = l.intervals.map {
                if (it.startMeters in 790.0..860.0) it.copy(state = DrivingState.FullThrottle) else it
            }
            l.copy(intervals = flatOut)
        }
        val result = brakeToThrottleGaps(analysis(flat, issues))
        val c4 = result.corners.single { it.segmentId == "c4" }
        assertTrue(c4.passes.isEmpty())
        assertEquals((2..6).toList(), c4.excluded.filter { it.reason == GapExcludedNoBraking }.map { it.lapNumber })
        assertEquals(listOf(1), c4.excluded.filter { it.reason == GapExcludedLapNotCounted }.map { it.lapNumber })
        // Three judged corners: Corner 3 is still compared with the two others.
        assertTrue(result.corners.single { it.segmentId == "c3" }.flagged)
        assertEquals("", result.comparisonReason)
        // With two corners left there is nothing to compare with.
        val two = analysis(flat, issues, ApprovedSegments(segments.segments.drop(2), "test"))
        assertEquals(GapComparisonTooFewCorners, brakeToThrottleGaps(two).comparisonReason)
    }
}
