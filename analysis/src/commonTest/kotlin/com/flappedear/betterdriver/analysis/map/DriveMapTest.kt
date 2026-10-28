package com.flappedear.betterdriver.analysis.map

import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.feature.analysis.distanceText
import com.flappedear.betterdriver.feature.analysis.driveFigures
import com.flappedear.betterdriver.feature.analysis.durationText
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A drive with no track (FBBD-342), as a commute: standing 30 s with GPS wandering, 120 s north at
 * 72 km/h, a 10 s GPS gap while it drives 200 m more, then 60 s at 36 km/h. At 10 Hz.
 */
class DriveMapTest {
    private val lat0 = 50.0
    private val lon0 = 19.0
    private val metersPerDegreeLat = 111_195.0
    private val metersPerDegreeLon = metersPerDegreeLat * cos(lat0 * PI / 180.0)
    private val hz = 10

    private fun drive(withSpeed: Boolean = true, speedDropout: ClosedFloatingPointRange<Double>? = null): SessionData {
        val sets = mutableListOf<SampleSet>()
        fun at(seconds: Double, north: Double, east: Double, kmh: Double) {
            val values = mutableListOf(
                ChannelValue(GnssChannels.LAT.id, lat0 + north / metersPerDegreeLat),
                ChannelValue(GnssChannels.LON.id, lon0 + east / metersPerDegreeLon),
            )
            if (withSpeed && speedDropout?.contains(seconds) != true) {
                values += ChannelValue(GnssChannels.SPEED_KMH.id, kmh)
            }
            sets += SampleSet((seconds * 1_000_000).roundToLong(), values)
        }
        // Standing: the fix wanders a metre either way.
        for (i in 0 until 30 * hz) at(i.toDouble() / hz, if (i % 2 == 0) 1.0 else -1.0, 0.0, 0.0)
        // 72 km/h north for 120 s: 2400 m.
        for (i in 0..120 * hz) at(30.0 + i.toDouble() / hz, i * 20.0 / hz, 0.0, 72.0)
        // A 10 s gap over 200 m, then 36 km/h for 60 s: 600 m.
        for (i in 0..60 * hz) at(160.0 + i.toDouble() / hz, 2600.0 + i * 10.0 / hz, 0.0, 36.0)
        val channels = GnssChannels.ALL
        return SessionData(
            "commute", SessionInfo(startUtcMillis = 0, channels = channels), SessionState.FINISHED,
            channels.associateBy { it.id }, sets, emptyList(), null, 0, 0,
        )
    }

    @Test
    fun `a drive with no track is drawn whole with its distance and speed and gap`() {
        val drive = assertNotNull(driveMap(telemetrySessionOf(drive(), null)))
        // Standing adds nothing; the gap adds its straight line.
        assertEquals(3200.0, drive.distanceMeters, 5.0)
        assertEquals(3200.0, drive.map.lengthMeters, 5.0)
        assertEquals(220.0, drive.durationSeconds, 0.2)
        assertEquals(180.0, drive.movingSeconds, 0.5)
        // 3000 m driven in 180 s; the gap counts in neither.
        assertEquals(60.0, assertNotNull(drive.averageMovingKmh), 0.5)
        assertEquals(72.0, assertNotNull(drive.topSpeedKmh), 1e-6)
        // The gap opens the line: two runs, the second starting 2600 m north.
        assertEquals(2, drive.map.runs.size)
        val second = drive.map.runs[1].first()
        assertEquals(2600.0, second.northMeters, 2.0)
        assertEquals(2600.0, second.distanceMeters, 5.0)
        // No track: no outline, no corners, an open line.
        assertTrue(drive.map.outline.isEmpty() && drive.map.corners.isEmpty() && !drive.map.closed)
        // Speed by distance, in the same runs, rising along the drive.
        assertEquals(2, drive.speed.size)
        val speeds = drive.speed.flatten()
        assertTrue(speeds.zipWithNext().all { (a, b) -> b.distanceMeters >= a.distanceMeters })
        assertEquals(72.0, speeds.first { it.distanceMeters in 1000.0..2000.0 }.value, 1e-6)
        assertEquals(36.0, speeds.last().value, 1e-6)
        // Every point is on the road north of the start.
        assertTrue(drive.map.runs.flatten().all { abs(it.eastMeters) < 0.01 })
    }

    @Test
    fun `without a speed channel GPS wander adds nothing`() {
        val drive = assertNotNull(driveMap(telemetrySessionOf(drive(withSpeed = false), null)))
        // Counted in steps of 5 m or more, so up to 5 m short before the gap and at the end.
        assertEquals(3200.0, drive.distanceMeters, 10.0)
        assertNull(drive.topSpeedKmh)
        assertTrue(drive.speed.isEmpty())
        // The standing wander never reaches 5 m, so it is no distance, and at most the 6 s it takes
        // to drive 5 m at 3 km/h of moving time.
        assertTrue(drive.movingSeconds in 179.0..186.5, "moving ${drive.movingSeconds}")
        assertTrue(assertNotNull(drive.averageMovingKmh) in 57.5..61.0, "average ${drive.averageMovingKmh}")
    }

    @Test
    fun `the speed chart breaks where the speed drops out though positions go on`() {
        val drive = assertNotNull(driveMap(telemetrySessionOf(drive(speedDropout = 60.0..80.0), null)))
        // One map run before the GPS gap, but the speed chart breaks at the dropout: three runs.
        assertEquals(2, drive.map.runs.size)
        assertEquals(3, drive.speed.size)
        val first = drive.speed[0].last().distanceMeters
        val second = drive.speed[1].first().distanceMeters
        assertTrue(second - first > 300.0, "gap ${second - first}")
    }

    @Test
    fun `a long drive is thinned for the map but measured on every fix`() {
        val drive = assertNotNull(driveMap(telemetrySessionOf(drive(), null), maxPoints = 200))
        assertTrue(drive.map.runs.sumOf { it.size } <= 200 + 4, "points ${drive.map.runs.sumOf { it.size }}")
        assertEquals(3200.0, drive.distanceMeters, 5.0)
    }

    @Test
    fun `no positions means no drive`() {
        val data = drive()
        val empty = data.copy(sampleSets = data.sampleSets.take(1))
        assertNull(driveMap(telemetrySessionOf(empty, null)))
    }

    @Test
    fun `the figures read as both apps show them`() {
        val figures = driveFigures(assertNotNull(driveMap(telemetrySessionOf(drive(), null))))
        assertEquals("3.2 km", figures.distance)
        assertEquals("3 min", figures.time)
        assertEquals("60 km/h", figures.averageSpeed)
        assertEquals("72 km/h", figures.topSpeed)
        assertEquals("850 m", distanceText(849.6))
        assertEquals("45 s", durationText(45.2))
        assertEquals("1 h 05 min", durationText(3900.0))
    }
}
