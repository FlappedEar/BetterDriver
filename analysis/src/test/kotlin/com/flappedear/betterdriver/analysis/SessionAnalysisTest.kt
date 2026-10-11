package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionAnalysisTest {
    @Test
    fun `a clean session gets segments, sector times and a theoretical best`() {
        val analysis = analyzeSession(VboFixture.read("segments_mixed.vbo"))
        val segments = assertNotNull(analysis.segments)
        assertTrue(segments.segments.any { it.type == TrackSegmentType.Corner })
        assertTrue(segments.segments.any { it.type == TrackSegmentType.Straight })
        assertEquals(analysis.laps.timedLaps.size, analysis.lapTimes.size)
        for ((lap, times) in analysis.laps.timedLaps.zip(analysis.lapTimes)) {
            assertEquals(lap.number, times.lapReference)
            assertTrue(times.completePartition)
            val sum = assertNotNull(times.sumSeconds)
            assertTrue(abs(sum - lap.durationSeconds) < 1e-3, "lap ${lap.number}: $sum vs ${lap.durationSeconds}")
        }
        val best = assertNotNull(analysis.theoreticalBest?.totalSeconds)
        val fastest = analysis.laps.timedLaps[analysis.laps.fastestLapIndex!!].durationSeconds
        assertTrue(best <= fastest + 1e-9, "theoretical best $best is slower than the fastest lap $fastest")

        // What the fastest lap leaves in each segment adds up to the gap to the theoretical best.
        val summary = assertNotNull(analysis.summary)
        val left = summary.sectors.sumOf { assertNotNull(it.lossSeconds) }
        assertEquals(assertNotNull(summary.differenceSeconds), left, 1e-9)
        assertTrue(summary.sectors.all { it.lossSeconds!! >= -1e-12 })
        // Every loss is another lap's, largest first.
        val losses = assertNotNull(analysis.losses).losses
        assertTrue(losses.isNotEmpty())
        val fastestNumber = analysis.laps.timedLaps[analysis.laps.fastestLapIndex!!].number
        assertTrue(losses.none { it.lapReference == fastestNumber })
        assertEquals(losses.sortedByDescending { it.lossSeconds }, losses)
    }

    @Test
    fun `every corner is measured on every lap, with braking from the brake channel`() {
        val analysis = analyzeSession(VboFixture.read("corners_measured.vbo"))
        val corners = assertNotNull(analysis.segments).segments.filter { it.type == TrackSegmentType.Corner }
        assertTrue(corners.isNotEmpty())
        assertEquals(corners.map { it.id }.toSet(), analysis.cornerMetrics.keys)
        assertEquals(corners.map { it.id }.toSet(), analysis.variability.keys)
        for (laps in analysis.cornerMetrics.values) {
            assertEquals(analysis.laps.timedLaps.size, laps.size)
        }
        val braking = analysis.cornerMetrics.values.flatten().map { it.braking }
        assertTrue(braking.any { it.method == "measuredBrake" && it.brakingPointMeters != null })
    }

    @Test
    fun `a session without laps has no segments`() {
        val analysis = analyzeSession(VboFixture.read("event-laps.vbo"))
        assertNull(analysis.segments)
        assertNull(analysis.theoreticalBest)
    }
}
