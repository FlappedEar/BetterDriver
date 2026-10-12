@file:Suppress("MagicNumber", "NestedBlockDepth") // Test geometry and simulation.

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.data.TimingGateType
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackKind
import com.flappedear.betterdriver.timing.track.TrackLine
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FBBD-155: the analysis's own lap detection on a point-to-point track. A winding road runs east;
 * runs go up it from the start line at 100 m to the finish at 1,100 m, and the drive back runs
 * west 8 m to the north, across both lines the wrong way.
 */
class PointToPointRunsTest {
    private val centerLat = 49.3
    private val centerLon = 19.9
    private val metersPerDegreeLat = 111_195.0
    private val metersPerDegreeLon = metersPerDegreeLat * cos(centerLat * PI / 180.0)

    private fun roadY(x: Double) = 40.0 * sin(x / 150.0)
    private fun geo(x: Double, y: Double) =
        GeoPoint(centerLat + y / metersPerDegreeLat, centerLon + x / metersPerDegreeLon)

    /** A 30 m line across the road at [x], crossed forwards driving east (left end to the north). */
    private fun lineAt(x: Double) = TrackLine(geo(x, roadY(x) + 15), geo(x, roadY(x) - 15))

    private val startX = 100.0
    private val finishX = 1_100.0

    private val hill = Track(
        id = "hill",
        name = "Hill climb",
        center = geo(600.0, 0.0),
        startFinish = lineAt(startX),
        kind = TrackKind.PointToPoint,
        finish = lineAt(finishX),
    )

    /** Drive along the road from [fromX] to [toX] at [speed] m/s: east on it, or west 8 m to the north. */
    private class Leg(val fromX: Double, val toX: Double, val speed: Double)

    private class Drive(val session: SessionData, val starts: List<Double>, val finishes: List<Double>)

    private fun drive(legs: List<Leg>, finishAt: Double = finishX): Drive {
        val sets = mutableListOf<SampleSet>()
        val starts = mutableListOf<Double>()
        val finishes = mutableListOf<Double>()
        var t = 0.0
        val dt = 0.04
        for (leg in legs) {
            val east = leg.toX > leg.fromX
            val offset = if (east) 0.0 else 8.0
            var x = leg.fromX
            while (if (east) x < leg.toX else x > leg.toX) {
                val y = roadY(x) + offset
                val g = geo(x, y)
                sets += SampleSet(
                    Math.round(t * 1e6),
                    listOf(
                        ChannelValue(GnssChannels.LAT.id, g.lat),
                        ChannelValue(GnssChannels.LON.id, g.lon),
                        ChannelValue(GnssChannels.SPEED_KMH.id, leg.speed * 3.6),
                    ),
                )
                // Step [speed * dt] metres along the road.
                val slope = 40.0 / 150.0 * cos(x / 150.0)
                val dx = leg.speed * dt / hypot(1.0, slope) * (if (east) 1 else -1)
                val next = x + dx
                if (east) {
                    if (x < startX && next >= startX) starts += t + dt * (startX - x) / dx
                    if (x < finishAt && next >= finishAt) finishes += t + dt * (finishAt - x) / dx
                }
                x = next
                t += dt
            }
        }
        val info = SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL)
        val data = SessionData(
            "hill", info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets,
            emptyList(), null, 0, 0,
        )
        return Drive(data, starts, finishes)
    }

    @Test
    fun `runs from start to finish are the laps, and aborted runs and drives back are not`() {
        val d = drive(
            listOf(
                Leg(0.0, 1_200.0, 30.0), Leg(1_200.0, 0.0, 12.0),
                // Aborted at 700 m, back down, then a faster run.
                Leg(0.0, 700.0, 30.0), Leg(700.0, 0.0, 12.0),
                Leg(0.0, 1_200.0, 33.0), Leg(1_200.0, 0.0, 12.0),
            ),
        )
        assertEquals(3, d.starts.size)
        val session = telemetrySessionOf(d.session, hill)
        assertEquals(listOf(TimingGateType.Start, TimingGateType.Finish), session.timingGates.map { it.type })
        val laps = deriveSourceLapSession(session)
        assertEquals(LapSessionStatus.Available, laps.status)
        val runs = laps.timedLaps
        assertEquals(2, runs.size, "runs ${runs.map { it.durationSeconds }}")
        assertEquals(d.finishes[0] - d.starts[0], runs[0].durationSeconds, 0.02)
        assertEquals(d.finishes[1] - d.starts[2], runs[1].durationSeconds, 0.02)
        assertEquals(1, laps.fastestLapIndex)
        assertTrue(runs.all { it.referenceEligible })
        val length = assertNotNull(runs[0].distanceMeters)
        assertTrue(length in 1_000.0..1_100.0, "run length $length")
        assertEquals(2, laps.lapTraces.size)

        // The whole analysis runs on the runs.
        val analysis = analyzeSession(session)
        assertEquals(2, analysis.laps.timedLaps.size)
        assertEquals(runs[1].number, analysis.axisLapNumber)
    }

    @Test
    fun `a short sprint is a run, though shorter than any circuit lap`() {
        // 150 m from start to finish: under the 200 m a circuit lap needs.
        val sprint = hill.copy(finish = lineAt(250.0))
        val d = drive(
            listOf(Leg(0.0, 400.0, 30.0), Leg(400.0, 0.0, 12.0), Leg(0.0, 400.0, 32.0), Leg(400.0, 0.0, 12.0)),
            finishAt = 250.0,
        )
        val laps = deriveSourceLapSession(telemetrySessionOf(d.session, sprint))
        assertEquals(LapSessionStatus.Available, laps.status)
        assertEquals(2, laps.timedLaps.size)
        assertTrue(laps.timedLaps.all { it.referenceEligible }, "runs ${laps.timedLaps}")
        assertEquals(d.finishes[1] - d.starts[1], laps.timedLaps[1].durationSeconds, 0.02)
        assertEquals(1, laps.fastestLapIndex)
    }

    @Test
    fun `the same session read as a circuit times the drive back too`() {
        val d = drive(listOf(Leg(0.0, 1_200.0, 30.0), Leg(1_200.0, 0.0, 12.0), Leg(0.0, 1_200.0, 30.0)))
        val laps = deriveSourceLapSession(telemetrySessionOf(d.session, hill.copy(kind = TrackKind.Circuit)))
        val lap = laps.timedLaps.single()
        assertEquals(d.starts[1] - d.starts[0], lap.durationSeconds, 0.02)
    }
}
