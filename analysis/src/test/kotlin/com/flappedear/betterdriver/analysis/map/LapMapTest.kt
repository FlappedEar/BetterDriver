package com.flappedear.betterdriver.analysis.map

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.compare.compareLaps
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.StandardGravity
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.recorded.ForwardAcceleration
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.TrackLine
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LapMapTest {
    private val centerLat = 50.0
    private val centerLon = 19.0
    private val metersPerDegreeLat = 111_195.0
    private val metersPerDegreeLon = metersPerDegreeLat * cos(centerLat * PI / 180.0)
    private val radius = 200.0
    private val accelX = ChannelDef(110, "accel_x", "g", "racebox", 3)
    private val accelY = ChannelDef(111, "accel_y", "g", "racebox", 3)

    /** A RaceChrono DIY receiver's accelerometer (RcChannels), also in g. */
    private val rcAccelX = ChannelDef(500, "rc_accel_x", "g", "nmea", 3)
    private val rcAccelY = ChannelDef(501, "rc_accel_y", "g", "nmea", 3)

    /**
     * Laps of a 200 m radius circle at 25 Hz, anticlockwise from the south; lap k is 1 m/s slower.
     * With [lateralSign], a RaceBox's accelerations too (a RaceChrono DIY receiver's with [rc]): Y
     * across the car, of that sign for a left turn.
     */
    private fun circleSession(laps: Int, lateralSign: Double? = null, rc: Boolean = false): SessionData {
        val (x, y) = if (rc) rcAccelX to rcAccelY else accelX to accelY
        val sets = mutableListOf<SampleSet>()
        var t = 0L
        for (lap in 0 until laps) {
            val speed = 30.0 - lap
            val steps = (2 * PI * radius / speed / 0.04).toInt()
            for (i in 0 until steps) {
                val angle = -PI / 2 + 2 * PI * i / steps
                val values = mutableListOf(
                    ChannelValue(GnssChannels.LAT.id, centerLat + radius * sin(angle) / metersPerDegreeLat),
                    ChannelValue(GnssChannels.LON.id, centerLon + radius * cos(angle) / metersPerDegreeLon),
                    ChannelValue(GnssChannels.SPEED_KMH.id, speed * 3.6),
                )
                if (lateralSign != null) {
                    values += ChannelValue(x.id, 0.0)
                    values += ChannelValue(y.id, lateralSign * speed * speed / radius / StandardGravity)
                }
                sets += SampleSet(t, values)
                t += 40_000L
            }
        }
        val channels = GnssChannels.ALL + listOf(x, y)
        return SessionData(
            "s", SessionInfo(startUtcMillis = 0, channels = channels), SessionState.FINISHED,
            channels.associateBy { it.id }, sets, emptyList(), null, 0, 0,
        )
    }

    private val line = TrackLine(
        left = GeoPoint(centerLat + (-radius + 15) / metersPerDegreeLat, centerLon),
        right = GeoPoint(centerLat + (-radius - 15) / metersPerDegreeLat, centerLon),
    )

    @Test
    fun `a lap is drawn on the circle it drove, with its speed`() {
        val session = telemetrySessionOf(circleSession(4), line)
        val analysis = analyzeSession(session)
        val lap = assertNotNull(analysis.laps.fastestLapIndex)
        val map = assertNotNull(lapMap(analysis, session, lap))
        val points = map.runs.flatten()
        assertTrue(points.size in 100..MaxMapPoints, "points ${points.size}")
        // The circle's centre is the mean of its points; every point is a radius from it.
        val east = points.map { it.eastMeters }.average()
        val north = points.map { it.northMeters }.average()
        for (p in points) {
            val r = kotlin.math.hypot(p.eastMeters - east, p.northMeters - north)
            assertTrue(abs(r - radius) < 3.0, "radius $r")
            assertNull(p.deltaRate)
        }
        val speed = points.map { assertNotNull(it.speed) }.sorted()[points.size / 2]
        assertEquals(analysis.laps.timedLaps[lap].let { 2 * PI * radius / it.durationSeconds * 3.6 }, speed, 1.0)
        assertTrue(map.outline.size in 100..MaxOutlinePoints)
    }

    @Test
    fun `a slower lap loses time all the way round`() {
        val session = telemetrySessionOf(circleSession(5), line)
        val analysis = analyzeSession(session)
        val fastest = assertNotNull(analysis.laps.fastestLapIndex)
        val slowest = analysis.laps.timedLaps.indices.maxBy { analysis.laps.timedLaps[it].durationSeconds }
        val comparison = assertNotNull(compareLaps(analysis, session, fastest, slowest))
        val map = assertNotNull(lapMap(analysis, session, slowest, comparison))
        assertEquals(fastest, map.referenceIndex)
        val timed = analysis.laps.timedLaps
        val perHundred = (timed[slowest].durationSeconds - timed[fastest].durationSeconds) / map.lengthMeters * 100
        val rates = map.runs.flatten().mapNotNull { it.deltaRate }
        assertTrue(rates.size > 100)
        val median = rates.sorted()[rates.size / 2]
        assertEquals(perHundred, median, perHundred * 0.1)
        // The fastest lap against itself has no delta.
        assertNull(lapMap(analysis, session, fastest, comparison)?.referenceIndex)
    }

    @Test
    fun `a RaceBox's or RaceChrono DIY receiver's lateral acceleration feeds the G-G diagram, either way round`() {
        for (rc in listOf(false, true)) for (sign in listOf(1.0, -1.0)) {
            val session = telemetrySessionOf(circleSession(4, sign, rc), line)
            assertEquals(if (rc) "rc_accel_y" else "accel_y", session.aliases[Aliases.LATERAL_ACCELERATION])
            assertEquals(ForwardAcceleration, session.aliases[Aliases.LONGITUDINAL_ACCELERATION])
            val analysis = analyzeSession(session)
            val lap = assertNotNull(analysis.laps.fastestLapIndex)
            val trace = assertNotNull(lapGg(analysis, session, lap))
            assertEquals(GgSource.Accelerometer, trace.source)
            val speed = 2 * PI * radius / analysis.laps.timedLaps[lap].durationSeconds
            val lateral = trace.points.map { it.lateralG }.sorted()[trace.points.size / 2]
            assertEquals(speed * speed / radius / StandardGravity, lateral, 0.02, "rc $rc, sign $sign")
            assertTrue(trace.points.count { it.distanceMeters != null } > trace.points.size / 2)
        }
    }

    @Test
    fun `the rate of a delta is its slope per 100 m, cut at the ends of a run`() {
        val delta = listOf((0..20).map { ChartPoint(it * 5.0, it * 5.0 * 0.002) }, listOf(ChartPoint(500.0, 1.0)))
        val rates = deltaRates(delta, windowMeters = 30.0)
        assertEquals(1, rates.size, "a single point has no slope")
        assertEquals(21, rates.first().size)
        for (p in rates.first()) assertEquals(0.2, p.value, 1e-9)
    }

    @Test
    fun `interpolation reads between points and nothing outside a run`() {
        val runs = listOf(listOf(ChartPoint(0.0, 0.0), ChartPoint(10.0, 1.0)), listOf(ChartPoint(20.0, 5.0)))
        assertEquals(0.25, assertNotNull(interpolate(runs, 2.5)), 1e-12)
        assertEquals(5.0, interpolate(runs, 20.0))
        assertNull(interpolate(runs, 15.0))
    }

    @Test
    fun `the nearest point is found, unless it is too far`() {
        fun p(e: Double, n: Double, d: Double) = MapPoint(e, n, d, null, null, null)
        val runs = listOf(listOf(p(0.0, 0.0, 0.0), p(10.0, 0.0, 10.0)), listOf(p(10.0, 10.0, 20.0)))
        assertEquals(20.0, nearestMapPoint(runs, 9.0, 8.0)?.distanceMeters)
        assertEquals(10.0, nearestMapPoint(runs, 9.0, 1.0, withinMeters = 2.0)?.distanceMeters)
        assertNull(nearestMapPoint(runs, 50.0, 50.0, withinMeters = 5.0))
    }

    @Test
    fun `a corner label points to the outside of the turn`() {
        // A left turn round the origin: the outside is away from it.
        val out = outward(MetricPoint(10.0, -10.0), MetricPoint(14.0, 0.0), MetricPoint(10.0, 10.0))
        assertEquals(1.0, out.eastMeters, 1e-9)
        assertEquals(0.0, out.northMeters, 1e-9)
        // On a straight north, the left of travel (west).
        val left = outward(MetricPoint(0.0, 0.0), MetricPoint(0.0, 15.0), MetricPoint(0.0, 30.0))
        assertEquals(-1.0, left.eastMeters, 1e-9)
    }
}
