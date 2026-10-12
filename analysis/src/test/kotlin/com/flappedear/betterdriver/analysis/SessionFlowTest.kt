@file:Suppress("MagicNumber") // Test geometry and simulation.

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.corners.cornerClip
import com.flappedear.betterdriver.analysis.corners.cornerPasses
import com.flappedear.betterdriver.analysis.corners.sameLap
import com.flappedear.betterdriver.analysis.corners.sessionMicros
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.map.lapGg
import com.flappedear.betterdriver.analysis.map.lapMap
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.runs.AccelerationRunKind
import com.flappedear.betterdriver.analysis.runs.findAccelerationRuns
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.segmentLine
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackKind
import com.flappedear.betterdriver.timing.track.TrackLine
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A recorded session through every layer the lap analysis screen reads (FBBD-148, 149, 153, 154,
 * 155): the analysis, the coach's corner lines, the map and G-G diagram, acceleration runs, every
 * lap through a corner with its video, and the lap review's laps that Compare opens. The car stands
 * for a few seconds, then drives laps of a rounded rectangle with four corners of different radii;
 * the session clock does not start at the first sample, as on a phone.
 */
class SessionFlowTest {
    private val centerLat = 50.1
    private val centerLon = 14.4
    private val metersPerDegreeLat = 111_195.0
    private val metersPerDegreeLon = metersPerDegreeLat * cos(centerLat * PI / 180.0)

    private fun geo(x: Double, y: Double) =
        GeoPoint(centerLat + y / metersPerDegreeLat, centerLon + x / metersPerDegreeLon)

    /** The circuit's line every half metre, anticlockwise from the middle of its bottom straight, x east, y north. */
    private val path: List<Pair<Double, Double>> = buildList {
        val halfWidth = 300.0
        val halfHeight = 150.0
        // Corner radii: bottom right, top right, top left, bottom left.
        val radii = listOf(40.0, 25.0, 60.0, 30.0)
        val step = 0.5
        fun straight(x0: Double, y0: Double, x1: Double, y1: Double) {
            val n = (hypot(x1 - x0, y1 - y0) / step).toInt()
            for (i in 0 until n) add(x0 + (x1 - x0) * i / n to y0 + (y1 - y0) * i / n)
        }
        fun arc(cx: Double, cy: Double, r: Double, from: Double) {
            val n = (r * PI / 2 / step).toInt()
            for (i in 0 until n) {
                val a = from + PI / 2 * i / n
                add(cx + r * cos(a) to cy + r * sin(a))
            }
        }
        straight(0.0, -halfHeight, halfWidth - radii[0], -halfHeight)
        arc(halfWidth - radii[0], -halfHeight + radii[0], radii[0], -PI / 2)
        straight(halfWidth, -halfHeight + radii[0], halfWidth, halfHeight - radii[1])
        arc(halfWidth - radii[1], halfHeight - radii[1], radii[1], 0.0)
        straight(halfWidth - radii[1], halfHeight, -halfWidth + radii[2], halfHeight)
        arc(-halfWidth + radii[2], halfHeight - radii[2], radii[2], PI / 2)
        straight(-halfWidth, halfHeight - radii[2], -halfWidth, -halfHeight + radii[3])
        arc(-halfWidth + radii[3], -halfHeight + radii[3], radii[3], PI)
        straight(-halfWidth + radii[3], -halfHeight, 0.0, -halfHeight)
    }

    private val along = DoubleArray(path.size).also {
        for (i in 1 until path.size) {
            it[i] = it[i - 1] + hypot(path[i].first - path[i - 1].first, path[i].second - path[i - 1].second)
        }
    }
    private val lapMeters = along.last() + hypot(path.last().first - path[0].first, path.last().second - path[0].second)

    /** Fastest speed at each path point: the corners' grip, braking at 9 m/s² and speeding up at 5 m/s² between. */
    private val speedLimit: DoubleArray = run {
        val n = path.size
        val v = DoubleArray(n) { i ->
            val a = path[(i + n - 4) % n]
            val b = path[i]
            val c = path[(i + 4) % n]
            // Radius through three points: the circumradius.
            val ab = hypot(b.first - a.first, b.second - a.second)
            val bc = hypot(c.first - b.first, c.second - b.second)
            val ca = hypot(a.first - c.first, a.second - c.second)
            val cross = abs((b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first))
            val radius = if (cross < 1e-9) Double.POSITIVE_INFINITY else ab * bc * ca / (2 * cross)
            min(45.0, sqrt(12.0 * radius))
        }
        repeat(2) {
            for (k in 1..n) {
                val i = k % n
                val p = (i + n - 1) % n
                v[i] = min(v[i], sqrt(v[p] * v[p] + 2 * 5.0 * 0.5))
            }
            for (k in n - 1 downTo 0) {
                val nx = (k + 1) % n
                v[k] = min(v[k], sqrt(v[nx] * v[nx] + 2 * 9.0 * 0.5))
            }
        }
        v
    }

    private fun pointAt(s: Double): Pair<Double, Double> {
        val m = s.mod(lapMeters)
        var i = along.indexOfLast { it <= m }.coerceAtLeast(0)
        if (i >= path.size) i = path.size - 1
        val j = (i + 1) % path.size
        val span = (if (j == 0) lapMeters else along[j]) - along[i]
        val u = if (span <= 0.0) 0.0 else (m - along[i]) / span
        val (x0, y0) = path[i]
        val (x1, y1) = path[j]
        return x0 + (x1 - x0) * u to y0 + (y1 - y0) * u
    }

    private fun limitAt(s: Double): Double = speedLimit[(s.mod(lapMeters) / 0.5).toInt().coerceIn(0, path.size - 1)]

    /** The session clock at the first sample: the recording started 2.3 s before the receiver's first fix. */
    private val origin = 2_300_000L

    /**
     * [laps] laps from 200 m before the line, after [standSeconds] at a standstill, sampled every
     * [stepSeconds]; each lap a little quicker than the one before.
     */
    private fun drive(laps: Int, stepSeconds: Double, standSeconds: Double = 3.0): SessionData {
        val sets = mutableListOf<SampleSet>()
        var s = -200.0
        var v = 0.0
        var t = 0.0
        var nextSample = 0.0
        val dt = 0.005
        val end = laps * lapMeters + 100.0
        while (s < end) {
            if (t + 1e-9 >= nextSample) {
                val (x, y) = pointAt(s)
                val g = geo(x, y)
                sets += SampleSet(
                    origin + Math.round(t * 1e6),
                    listOf(
                        ChannelValue(GnssChannels.LAT.id, g.lat),
                        ChannelValue(GnssChannels.LON.id, g.lon),
                        ChannelValue(GnssChannels.SPEED_KMH.id, v * 3.6),
                    ),
                )
                nextSample += stepSeconds
            }
            if (t >= standSeconds) {
                val lap = ((s + 200.0) / lapMeters).toInt()
                val pace = 0.94 + 0.01 * min(lap, 5)
                v = min(v + 5.0 * dt, limitAt(s) * pace)
                s += v * dt
            }
            t += dt
        }
        val info = SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL)
        return SessionData(
            "flow", info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets,
            emptyList(), null, 0, 0,
        )
    }

    /** A 30 m line across the bottom straight at x = [x], driven east (left end to the north). */
    private fun bottomLine(x: Double) = TrackLine(geo(x, -135.0), geo(x, -165.0))

    private val circuit = Track("flow", "Flow circuit", geo(0.0, 0.0), startFinish = bottomLine(0.0))

    /** Laps of the analysis (telemetry seconds) and of the lap review (session clock) are the same laps. */
    private fun assertReviewHasEveryLap(data: SessionData, track: Track, analysis: SessionAnalysis) {
        // As LapVideo builds them for Compare.
        val replay = replayLaps(data.id, data.sampleSets, track).laps
        val starts = replay.associate { it.lap.number to it.lap.startMicros }
        for (lap in analysis.laps.timedLaps) {
            val number = assertNotNull(
                sameLap(sessionMicros(origin, lap.startTelemetryTime), starts),
                "lap ${lap.number} at ${lap.startTelemetryTime} s is not in the review's laps $starts",
            )
            val review = replay.single { it.lap.number == number }.lap
            assertEquals(lap.durationSeconds, (review.endMicros - review.startMicros) / 1e6, 0.1)
        }
    }

    @Test
    fun `a 25 Hz circuit session shows on every tab, and Compare opens the same laps`() {
        val data = drive(laps = 6, stepSeconds = 0.04)
        val session = telemetrySessionOf(data, circuit)
        val analysis = analyzeSession(session)
        assertEquals(LapSessionStatus.Available, analysis.laps.status)
        // The out lap is no timed lap; the six laps from the line are.
        assertEquals(6, analysis.laps.timedLaps.size, "laps ${analysis.laps.timedLaps.map { it.durationSeconds }}")
        assertTrue(analysis.laps.timedLaps.all { it.referenceEligible })
        val fastest = assertNotNull(analysis.laps.fastestLapIndex)
        val axis = assertNotNull(analysis.axis)
        val corners = assertNotNull(analysis.segments).segments.filter { it.type == TrackSegmentType.Corner }
        assertTrue(corners.size >= 4, "corners ${corners.map { it.name }}")

        // Acceleration (FBBD-154): the standing start is a 0–100 run.
        val runs = findAccelerationRuns(session)
        val launch = runs.first { it.kind == AccelerationRunKind.ZeroTo100Kmh }
        // 27.8 m/s at 5 m/s²: 5.6 s.
        assertEquals(100 / 3.6 / 5.0, launch.timeSeconds, 0.1)

        // Map and G-G (FBBD-149): every lap is drawn, with finite points, on a closed outline.
        for (i in analysis.laps.timedLaps.indices) {
            val map = assertNotNull(lapMap(analysis, session, i))
            assertTrue(map.closed)
            assertTrue(map.runs.flatten().all { it.eastMeters.isFinite() && it.northMeters.isFinite() })
            val gg = assertNotNull(lapGg(analysis, session, i))
            assertTrue(gg.points.isNotEmpty())
            assertTrue(gg.points.all { it.lateralG.isFinite() && it.longitudinalG.isFinite() })
        }

        for (corner in corners) {
            // Coach picks (FBBD-148): each corner has a line to pick.
            assertTrue(segmentLine(axis, corner).isNotEmpty(), "${corner.name} has no line")
            // Every lap (FBBD-153): every counted lap passes through it, and its pass is on video.
            val passes = cornerPasses(analysis, corner.id)
            assertEquals(analysis.laps.timedLaps.size, passes.size, corner.name)
            val video = VideoTimeline(listOf(VideoClip("phone", "a.mp4", 0L, null)))
            for (p in passes) {
                val clip = assertNotNull(cornerClip(p, video, "phone", origin), "${corner.name} lap ${p.lapNumber}")
                assertTrue(clip.endMillis > clip.startMillis)
                assertTrue(clip.startMillis * 1_000 <= sessionMicros(origin, assertNotNull(p.entryTime)))
            }
        }
        assertReviewHasEveryLap(data, circuit, analysis)
        assertEquals(analysis.laps.timedLaps[fastest].number, analysis.axisLapNumber)
    }

    @Test
    fun `a 1 Hz session's laps are the review's laps too`() {
        val data = drive(laps = 5, stepSeconds = 1.0)
        val analysis = analyzeSession(telemetrySessionOf(data, circuit))
        assertEquals(LapSessionStatus.Available, analysis.laps.status)
        assertTrue(analysis.laps.timedLaps.size >= 3, "laps ${analysis.laps.timedLaps.map { it.durationSeconds }}")
        assertReviewHasEveryLap(data, circuit, analysis)
    }

    @Test
    fun `a point-to-point track's runs are the review's runs`() {
        // Runs from the bottom straight to the middle of the right one, driven north (left end to the west).
        val stage = Track(
            "stage", "Stage", geo(0.0, 0.0),
            startFinish = bottomLine(-100.0),
            kind = TrackKind.PointToPoint,
            finish = TrackLine(geo(285.0, 0.0), geo(315.0, 0.0)),
        )
        val data = drive(laps = 4, stepSeconds = 0.04)
        val session = telemetrySessionOf(data, stage)
        val analysis = analyzeSession(session)
        assertEquals(LapSessionStatus.Available, analysis.laps.status)
        assertEquals(4, analysis.laps.timedLaps.size, "runs ${analysis.laps.timedLaps.map { it.durationSeconds }}")
        assertReviewHasEveryLap(data, stage, analysis)
        // Split into corners on an open axis, from the start to the finish (FBBD-162): the bottom
        // right corner, and the theoretical best of the runs.
        val axis = assertNotNull(analysis.axis)
        assertFalse(axis.closed)
        val corners = assertNotNull(analysis.segments).segments.filter { it.type == TrackSegmentType.Corner }
        assertEquals(1, corners.size, "corners ${corners.map { it.name }}")
        for (corner in corners) {
            assertTrue(segmentLine(axis, corner).isNotEmpty(), "${corner.name} has no line")
            assertEquals(4, cornerPasses(analysis, corner.id).count { it.seconds != null }, corner.name)
        }
        val fastest = analysis.laps.timedLaps[assertNotNull(analysis.laps.fastestLapIndex)]
        val best = assertNotNull(assertNotNull(analysis.theoreticalBest).totalSeconds)
        assertTrue(best <= fastest.durationSeconds + 1e-6, "theoretical best $best")
        // The map's outline is the run's road, not a loop: it ends at the finish, across the map from the start.
        val map = assertNotNull(lapMap(analysis, session, assertNotNull(analysis.laps.fastestLapIndex)))
        assertFalse(map.closed)
        val ends = hypot(
            map.outline.last().eastMeters - map.outline.first().eastMeters,
            map.outline.last().northMeters - map.outline.first().northMeters,
        )
        // Start at (-100, -150), finish at (300, 0): 427 m apart.
        assertEquals(427.0, ends, 15.0)
    }

    @Test
    fun `a session at no track still has its acceleration runs`() {
        val data = drive(laps = 2, stepSeconds = 0.04)
        val session = telemetrySessionOf(data, null)
        assertTrue(session.timingGates.isEmpty())
        assertTrue(findAccelerationRuns(session).any { it.kind == AccelerationRunKind.ZeroTo100Kmh })
    }

    @Test
    fun `a long session's laps for the review are read as they are for the analysis`() {
        // 30 laps at 25 Hz: about 25 minutes, 40,000 samples.
        val data = drive(laps = 30, stepSeconds = 0.04)
        val analysis = analyzeSession(telemetrySessionOf(data, circuit))
        val replay = replayLaps(data.id, data.sampleSets, circuit)
        assertEquals(analysis.laps.timedLaps.size, replay.laps.count { it.lap.kind == LapKind.Timed })
        assertReviewHasEveryLap(data, circuit, analysis)
        // What each costs, best of three once warm (on a laptop: analysis about 170 ms, laps about 20 ms).
        fun best(work: () -> Unit) = (1..3).minOf {
            val start = System.nanoTime()
            work()
            System.nanoTime() - start
        } / 1_000_000
        val analysing = best { analyzeSession(telemetrySessionOf(data, circuit)) }
        val replaying = best { replayLaps(data.id, data.sampleSets, circuit) }
        println("${data.sampleSets.size} samples: analysis $analysing ms, review laps $replaying ms")
    }
}
