@file:Suppress("MagicNumber", "ReturnCount") // Test geometry and simulation.

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.compare.ComparisonStepMeters
import com.flappedear.betterdriver.analysis.compare.compareLaps
import com.flappedear.betterdriver.analysis.corners.cornerPasses
import com.flappedear.betterdriver.analysis.focus.sessionFocusAreas
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.map.lapMap
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.segmentLine
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FBBD-162: a point-to-point session analysed open, from the start line to the finish, with no
 * stretch back from the finish to the start. A hill climb with four corners (a right, a left
 * hairpin, a right and a fast left) is driven four times, each run a little quicker, and driven
 * back down after each run, across both lines the wrong way.
 */
class HillClimbTest {
    private val centerLat = 46.5
    private val centerLon = 11.3
    private val metersPerDegreeLat = 111_195.0
    private val metersPerDegreeLon = metersPerDegreeLat * cos(centerLat * PI / 180.0)

    private fun geo(x: Double, y: Double) =
        GeoPoint(centerLat + y / metersPerDegreeLat, centerLon + x / metersPerDegreeLon)

    private val step = 0.5

    /** The road every half metre from the start line, x east, y north; [headings] the direction of travel. */
    private val headings = mutableListOf<Double>()
    private val path: List<Pair<Double, Double>> = buildList {
        var x = 0.0
        var y = 0.0
        var heading = 0.0
        fun piece(meters: Double, curvature: Double) {
            repeat((meters / step).toInt()) {
                add(x to y)
                headings += heading
                x += step * cos(heading)
                y += step * sin(heading)
                heading += step * curvature
            }
        }
        piece(150.0, 0.0)
        piece(PI / 2 * 30.0, -1 / 30.0) // Right, 30 m radius.
        piece(120.0, 0.0)
        piece(PI * 20.0, 1 / 20.0) // Left hairpin, 20 m radius.
        piece(120.0, 0.0)
        piece(PI / 2 * 50.0, -1 / 50.0) // Right, 50 m radius.
        piece(100.0, 0.0)
        piece(PI / 3 * 40.0, 1 / 40.0) // Fast left, 40 m radius.
        piece(150.0, 0.0)
        add(x to y)
        headings += heading
    }
    private val roadMeters = (path.size - 1) * step

    private fun pointAt(s: Double): Pair<Double, Double> {
        if (s <= 0.0) return s to 0.0
        if (s >= roadMeters) {
            val (x, y) = path.last()
            val heading = headings.last()
            return x + (s - roadMeters) * cos(heading) to y + (s - roadMeters) * sin(heading)
        }
        val i = (s / step).toInt().coerceAtMost(path.size - 2)
        val u = s / step - i
        val (x0, y0) = path[i]
        val (x1, y1) = path[i + 1]
        return x0 + (x1 - x0) * u to y0 + (y1 - y0) * u
    }

    /** Fastest speed along the road: the corners' grip, and braking at 9 m/s² before them. */
    private val speedLimit: DoubleArray = run {
        val n = path.size
        val v = DoubleArray(n) { i ->
            val a = path[max(0, i - 4)]
            val b = path[i]
            val c = path[min(n - 1, i + 4)]
            val ab = hypot(b.first - a.first, b.second - a.second)
            val bc = hypot(c.first - b.first, c.second - b.second)
            val ca = hypot(a.first - c.first, a.second - c.second)
            val cross = abs((b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first))
            val radius = if (cross < 1e-9) Double.POSITIVE_INFINITY else ab * bc * ca / (2 * cross)
            min(45.0, sqrt(12.0 * radius))
        }
        for (k in n - 2 downTo 0) v[k] = min(v[k], sqrt(v[k + 1] * v[k + 1] + 2 * 9.0 * step))
        v
    }

    /** Where the car stops after the finish, and where it waits before a run. */
    private val runOut = 60.0
    private val waitAt = -40.0

    private fun limitAt(s: Double): Double = when {
        s < 0.0 -> 45.0
        s <= roadMeters -> speedLimit[(s / step).toInt().coerceIn(0, path.size - 1)]
        else -> min(45.0, sqrt(2 * 8.0 * max(0.0, roadMeters + runOut - s)))
    }

    private fun drive(runs: Int): SessionData {
        val sets = mutableListOf<SampleSet>()
        var t = 0.0
        var nextSample = 0.0
        val dt = 0.005
        fun sample(s: Double, v: Double) {
            if (t + 1e-9 < nextSample) return
            val (x, y) = pointAt(s)
            val g = geo(x, y)
            sets += SampleSet(
                Math.round(t * 1e6),
                listOf(
                    ChannelValue(GnssChannels.LAT.id, g.lat),
                    ChannelValue(GnssChannels.LON.id, g.lon),
                    ChannelValue(GnssChannels.SPEED_KMH.id, v * 3.6),
                ),
            )
            nextSample += 0.04
        }
        for (run in 0 until runs) {
            var s = waitAt
            var v = 0.0
            val standUntil = t + 3.0
            while (t < standUntil) {
                sample(s, 0.0)
                t += dt
            }
            val pace = 0.94 + 0.01 * run
            while (s < roadMeters || v > 1.0) {
                sample(s, v)
                v = min(v + 5.0 * dt, limitAt(s) * pace)
                s += v * dt
                t += dt
            }
            // Back down the same road at 15 m/s.
            while (s > waitAt) {
                sample(s, 15.0)
                s -= 15.0 * dt
                t += dt
            }
        }
        val info = SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL)
        return SessionData(
            "hill", info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets,
            emptyList(), null, 0, 0,
        )
    }

    /** A 30 m line across the road at [s], crossed forwards uphill (left end A). */
    private fun lineAt(s: Double): TrackLine {
        val (x, y) = pointAt(s)
        val heading = headings[(s / step).toInt().coerceIn(0, headings.size - 1)]
        val leftX = -sin(heading) * 15.0
        val leftY = cos(heading) * 15.0
        return TrackLine(geo(x + leftX, y + leftY), geo(x - leftX, y - leftY))
    }

    private val hill = Track(
        "hill", "Hill climb", geo(200.0, -50.0),
        startFinish = lineAt(0.0),
        kind = TrackKind.PointToPoint,
        finish = lineAt(roadMeters),
    )

    private val data = drive(runs = 4)
    private val session = telemetrySessionOf(data, hill)
    private val analysis = analyzeSession(session)
    private val corners = analysis.segments?.segments.orEmpty().filter { it.type == TrackSegmentType.Corner }

    @Test
    fun `the runs are split into the hill's corners on an open axis`() {
        assertEquals(LapSessionStatus.Available, analysis.laps.status)
        assertEquals(4, analysis.laps.timedLaps.size, "runs ${analysis.laps.timedLaps.map { it.durationSeconds }}")
        val axis = assertNotNull(analysis.axis)
        assertFalse(axis.closed)
        // The axis is the road from start to finish, with nothing back to the start.
        assertEquals(roadMeters, axis.lengthMeters, roadMeters * 0.02)
        assertFalse(analysis.consensus.disagrees, "line ${analysis.consensus}")
        val segments = assertNotNull(analysis.segments).segments
        assertEquals(4, corners.size, "corners ${segments.map { "${it.name} ${it.startProgressMeters}" }}")
        // In driving order, none across the line, together from the start to the finish.
        assertEquals(0.0, segments.first().startProgressMeters, 1e-9)
        assertEquals(axis.lengthMeters, segments.last().endProgressMeters, 1e-9)
        for ((a, b) in segments.zipWithNext()) assertEquals(a.endProgressMeters, b.startProgressMeters, 1e-9)
        assertTrue(segments.all { it.endProgressMeters > it.startProgressMeters })
        // Where the road turns: the middle of the first right and of the hairpin, give or take.
        fun middle(i: Int) = (corners[i].startProgressMeters + corners[i].endProgressMeters) / 2
        assertEquals(150.0 + PI / 2 * 30 / 2, middle(0), 15.0)
        assertEquals(150.0 + PI / 2 * 30 + 120 + PI * 20 / 2, middle(1), 15.0)
        // Every run is timed through every segment.
        assertTrue(analysis.lapTimes.all { it.sumSeconds != null }, "${analysis.lapTimes.map { it.sectors }}")
    }

    @Test
    fun `the theoretical best is no slower than the best run, and time goes somewhere`() {
        val fastest = analysis.laps.timedLaps[assertNotNull(analysis.laps.fastestLapIndex)]
        // The last run is the quickest.
        assertEquals(4, fastest.number)
        val best = assertNotNull(assertNotNull(analysis.theoreticalBest).totalSeconds)
        assertTrue(best <= fastest.durationSeconds + 1e-6, "$best vs ${fastest.durationSeconds}")
        assertNotNull(analysis.summary)
        val losses = assertNotNull(analysis.losses)
        assertTrue(losses.valid)
        assertTrue(losses.losses.isNotEmpty())
        // No window runs on from the finish into the start.
        assertTrue(losses.losses.all { it.window.endProgressMeters > it.window.startProgressMeters })
        assertTrue(sessionFocusAreas(analysis).isNotEmpty())
        assertEquals(corners.map { it.id }.toSet(), analysis.variability.keys)
    }

    @Test
    fun `every run passes through every corner, and the coach can pick each`() {
        val axis = assertNotNull(analysis.axis)
        for (corner in corners) {
            val passes = cornerPasses(analysis, corner.id)
            assertEquals(4, passes.size, corner.name)
            for (p in passes) {
                val seconds = assertNotNull(p.seconds, "${corner.name} run ${p.lapNumber}")
                assertEquals(seconds, assertNotNull(p.exitTime) - assertNotNull(p.entryTime), 1e-9)
                assertNotNull(p.minimumSpeed)
            }
            // The quickest run through the corner is first.
            assertEquals(passes.mapNotNull { it.seconds }.sorted(), passes.mapNotNull { it.seconds })
            val line = segmentLine(axis, corner)
            assertTrue(line.size >= 2, corner.name)
        }
        // The last corner's line ends where the corner does, not back at the start.
        val last = segmentLine(axis, corners.last())
        val (endX, endY) = pointAt(corners.last().endProgressMeters)
        val end = geo(endX, endY)
        val north = (last.last().latitudeDegrees - end.lat) * metersPerDegreeLat
        val east = (last.last().longitudeDegrees - end.lon) * metersPerDegreeLon
        assertTrue(hypot(north, east) < 15.0, "${hypot(north, east)} m from the corner's end")
    }

    @Test
    fun `Compare runs from the start line to the finish`() {
        val fastest = assertNotNull(analysis.laps.fastestLapIndex)
        val other = 0
        val comparison = assertNotNull(compareLaps(analysis, session, fastest, other))
        val axis = assertNotNull(analysis.axis)
        assertEquals(axis.lengthMeters, comparison.lengthMeters)
        assertEquals(1, comparison.delta.size, "one unbroken run of the delta")
        val delta = comparison.delta.single()
        assertEquals(0.0, delta.first().distanceMeters, 1e-9)
        assertEquals(0.0, delta.first().value, 0.05)
        // To the finish, where the delta is the difference of the run times.
        assertEquals(axis.lengthMeters, delta.last().distanceMeters, 1e-9)
        val laps = analysis.laps.timedLaps
        assertEquals(laps[other].durationSeconds - laps[fastest].durationSeconds, delta.last().value, 0.05)
        val steps = delta.zipWithNext { a, b -> b.distanceMeters - a.distanceMeters }
        assertTrue(steps.all { it <= ComparisonStepMeters + 1e-6 })
        // Both speed traces reach the finish end of the chart.
        for (speed in listOf(comparison.referenceSpeed, comparison.otherSpeed)) {
            assertTrue(speed.flatten().maxOf { it.distanceMeters } >= axis.lengthMeters - 15.0)
            assertTrue(speed.flatten().minOf { it.distanceMeters } <= 15.0)
        }
    }

    @Test
    fun `the map draws the road from start to finish with its corners`() {
        val map = assertNotNull(lapMap(analysis, session, 0))
        assertFalse(map.closed)
        assertEquals(4, map.corners.size)
        // Metres around the start line's centre, where the road starts: the outline ends at the finish.
        val start = map.outline.first()
        val end = map.outline.last()
        val (finishX, finishY) = path.last()
        assertTrue(hypot(start.eastMeters, start.northMeters) < 5.0, "outline starts at $start")
        assertTrue(hypot(end.eastMeters - finishX, end.northMeters - finishY) < 10.0, "outline ends at $end")
    }
}
