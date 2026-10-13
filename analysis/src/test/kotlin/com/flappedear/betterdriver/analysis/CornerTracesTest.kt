package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.corners.CornerTraceLeadMeters
import com.flappedear.betterdriver.analysis.corners.CornerTraceTailMeters
import com.flappedear.betterdriver.analysis.corners.cornerTraces
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.ProjectedSample
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.Provenance
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every pass through a corner as speed, brake and throttle traces over its distance (FBBD-93). */
class CornerTracesTest {
    private val length = 1_000.0

    /** A lap of 60 s from [start], a sample every 10 m; samples whose progress is in [invalid] lost their fix. */
    private fun trace(start: Double, invalid: ClosedRange<Double>? = null): List<ProgressSegment> {
        val samples = (0..100).map { k ->
            val p = k * 10.0
            ProjectedSample(start + p * SecondsPerMeter, p, valid = invalid?.contains(p) != true)
        }
        return listOf(ProgressSegment(samples.toMutableList()))
    }

    private fun lap(number: Int, start: Double) = TimedLap(number, start, start + 60.0, 60.0)

    private fun analysis(traces: List<List<ProgressSegment>>, closed: Boolean = true) = SessionAnalysis(
        LapSession(LapSessionStatus.Available, timedLaps = listOf(lap(1, 10.0), lap(2, 70.0))),
        segments = ApprovedSegments(
            listOf(
                TrackSegment("c1", "Corner 1", TrackSegmentType.Corner, 400.0, 500.0),
                // Over the start/finish line.
                TrackSegment("c9", "Corner 9", TrackSegmentType.Corner, 950.0, 50.0),
            ),
            "r1",
        ),
        axis = ProgressAxis(lengthMeters = length, valid = true, closed = closed),
        traces = traces,
        provenance = AnalysisProvenance(speed = Provenance.MEASURED_GPS, brake = Provenance.MEASURED_OBD),
    )

    // Speed is 60 plus the session time, so a point says which moment it was read at.
    private val times = DoubleArray(301) { it * 0.5 }
    private val session = TelemetrySession(
        mapOf(
            "spd" to TelemetryChannel("spd", times, DoubleArray(times.size) { 60.0 + times[it] }, unit = "km/h"),
            "brk" to TelemetryChannel("brk", times, DoubleArray(times.size) { if (times[it] < 40.0) 0.0 else 30.0 }),
        ),
        mapOf(Aliases.SPEED to "spd", Aliases.BRAKE to "brk"),
    )

    @Test
    fun `every pass lies on one distance scale from before the entry to after the exit`() {
        val t = assertNotNull(cornerTraces(analysis(listOf(trace(10.0), trace(70.0))), session, "c1", listOf(0, 1)))
        assertEquals(CornerTraceLeadMeters + 100.0 + CornerTraceTailMeters, t.lengthMeters, 1e-9)
        assertEquals(CornerTraceLeadMeters, t.entryMeters, 1e-9)
        assertEquals(CornerTraceLeadMeters + 100.0, t.exitMeters, 1e-9)
        assertEquals("km/h", t.speedUnit)
        assertEquals(Provenance.MEASURED_GPS, t.speedProvenance)
        assertEquals(listOf(0, 1), t.passes.map { it.lapIndex })
        for (pass in t.passes) {
            val points = pass.speed.flatten()
            assertEquals(1, pass.speed.size)
            assertEquals(0.0, points.first().distanceMeters, 1e-9)
            assertEquals(t.lengthMeters, points.last().distanceMeters, 1e-9)
        }
        // Lap 2 reaches the corner entry 60 s after lap 1: the same place, a later moment.
        val entry1 = t.of(0)!!.speed.flatten().first { it.distanceMeters == t.entryMeters }.value
        val entry2 = t.of(1)!!.speed.flatten().first { it.distanceMeters == t.entryMeters }.value
        assertEquals(60.0 + 10.0 + 400.0 * SecondsPerMeter, entry1, 1e-6)
        assertEquals(60.0, entry2 - entry1, 1e-6)
        // Brake was recorded, throttle was not.
        assertTrue(t.hasBrake)
        assertFalse(t.hasThrottle)
        assertTrue(t.of(0)!!.throttle.isEmpty())
    }

    @Test
    fun `a corner over the line reads its part after the line on the next lap`() {
        val t = assertNotNull(cornerTraces(analysis(listOf(trace(10.0), trace(70.0))), session, "c9", listOf(0, 1)))
        assertEquals(CornerTraceLeadMeters + 100.0 + CornerTraceTailMeters, t.lengthMeters, 1e-9)
        assertEquals(CornerTraceLeadMeters + 100.0, t.exitMeters, 1e-9)
        // Lap 1 runs on into lap 2 without a break: its pass goes on over the line to the end of the window.
        val first = t.of(0)!!.speed.flatten()
        assertEquals(t.lengthMeters, first.maxOf { it.distanceMeters }, 1e-9)
        // Lap 2 is the last: its pass ends at the line.
        val last = t.of(1)!!.speed.flatten()
        assertEquals(length - (950.0 - CornerTraceLeadMeters), last.maxOf { it.distanceMeters }, 1e-9)
    }

    @Test
    fun `a sample without a fix splits the trace`() {
        val t = assertNotNull(
            cornerTraces(analysis(listOf(trace(10.0, invalid = 440.0..460.0), trace(70.0))), session, "c1", listOf(0)),
        )
        assertEquals(2, t.of(0)!!.speed.size)
        assertTrue(t.of(0)!!.speed.flatten().none { it.distanceMeters in 160.0..180.0 })
    }

    @Test
    fun `a point-to-point run ends at its finish`() {
        val a = analysis(listOf(trace(10.0), trace(70.0)), closed = false)
        val t = assertNotNull(cornerTraces(a, session, "c1", listOf(0)))
        assertEquals(CornerTraceLeadMeters + 100.0 + CornerTraceTailMeters, t.lengthMeters, 1e-9)
    }

    @Test
    fun `no traces for a corner of another revision, or without an axis`() {
        val a = analysis(listOf(trace(10.0), trace(70.0)))
        assertNull(cornerTraces(a, session, "c-old", listOf(0)))
        assertNull(cornerTraces(a.copy(axis = null), session, "c1", listOf(0)))
        // A lap the analysis has no trace for is left out.
        assertEquals(listOf(0), cornerTraces(a, session, "c1", listOf(0, 7))!!.passes.map { it.lapIndex })
    }

    private companion object {
        const val SecondsPerMeter = 0.06
    }
}
