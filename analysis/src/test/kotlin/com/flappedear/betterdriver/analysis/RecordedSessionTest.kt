package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.compare.compareLaps
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.map.GgSource
import com.flappedear.betterdriver.analysis.map.sessionGg
import com.flappedear.betterdriver.analysis.recorded.ForwardAcceleration
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Provenance
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

class RecordedSessionTest {
    private val centerLat = 50.0
    private val centerLon = 19.0
    private val metersPerDegreeLat = 111_195.0
    private val metersPerDegreeLon = metersPerDegreeLat * cos(centerLat * PI / 180.0)

    /** Laps of a 200 m radius circle at 25 Hz, anticlockwise from the south; lap k is a bit slower. */
    private fun circleSession(laps: Int): SessionData {
        val radius = 200.0
        val sets = mutableListOf<SampleSet>()
        var t = 0L
        val stepMicros = 40_000L
        for (lap in 0 until laps) {
            val speed = 30.0 - lap // m/s
            val steps = (2 * PI * radius / speed / 0.04).toInt()
            for (i in 0 until steps) {
                val angle = -PI / 2 + 2 * PI * i / steps
                val east = radius * cos(angle)
                val north = radius * sin(angle)
                sets += SampleSet(
                    t,
                    listOf(
                        ChannelValue(GnssChannels.LAT.id, centerLat + north / metersPerDegreeLat),
                        ChannelValue(GnssChannels.LON.id, centerLon + east / metersPerDegreeLon),
                        ChannelValue(GnssChannels.SPEED_KMH.id, speed * 3.6),
                    ),
                )
                t += stepMicros
            }
        }
        // Out of order on disk, as the store may return them, plus a no-fix sample.
        sets.add(0, sets.removeAt(5))
        val noFix = listOf(ChannelValue(GnssChannels.LAT.id, 0.0), ChannelValue(GnssChannels.LON.id, 0.0))
        sets += SampleSet(t, noFix)
        val info = SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL)
        return SessionData(
            "s", info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets,
            emptyList(), null, 0, 0,
        )
    }

    /** A 30 m line across the circle's southmost point, for anticlockwise travel (east). */
    private val line = TrackLine(
        left = GeoPoint(centerLat + (-200.0 + 15) / metersPerDegreeLat, centerLon),
        right = GeoPoint(centerLat + (-200.0 - 15) / metersPerDegreeLat, centerLon),
    )

    /**
     * Speeding up then braking in a straight line, with accel_x of sign [sign] (+1: forward positive)
     * reading [tilt] more from a tilted mount.
     */
    private fun straightRun(
        sign: Double,
        tilt: Double = 0.0,
        dead: Boolean = false,
        axis: ChannelDef = ChannelDef(110, "accel_x", "g", "racebox", 3),
        profile: (Int) -> Double = { i -> if (i < 250) 0.3 else -0.6 }, // g at each 25 Hz sample
    ): SessionData {
        val accelX = axis
        var speed = 10.0
        val sets = (0 until 500).map { i ->
            val a = profile(i)
            speed += a * 9.80665 * 0.04
            SampleSet(
                i * 40_000L,
                listOf(
                    ChannelValue(GnssChannels.LAT.id, centerLat + i * 1e-5),
                    ChannelValue(GnssChannels.LON.id, centerLon),
                    ChannelValue(GnssChannels.SPEED_KMH.id, speed * 3.6),
                    ChannelValue(accelX.id, if (dead) 0.0 else sign * a + tilt),
                ),
            )
        }
        val channels = GnssChannels.ALL + accelX
        return SessionData(
            "s", SessionInfo(startUtcMillis = 0, channels = channels), SessionState.FINISHED,
            channels.associateBy { it.id }, sets, emptyList(), null, 0, 0,
        )
    }

    @Test
    fun `the RaceBox X acceleration is signed so braking reads negative, its tilt taken off`() {
        for (sign in listOf(1.0, -1.0)) for (tilt in listOf(0.0, -0.187)) {
            val session = telemetrySessionOf(straightRun(sign, tilt), null)
            assertEquals(ForwardAcceleration, session.aliases[Aliases.LONGITUDINAL_ACCELERATION])
            val forward = assertNotNull(session.channel(Aliases.LONGITUDINAL_ACCELERATION))
            assertEquals(0.3, forward.values.first(), 0.01, "sign $sign, tilt $tilt")
            assertEquals(-0.6, forward.values.last(), 0.01, "sign $sign, tilt $tilt")
        }
    }

    /**
     * FBBD-218: a receiver turned by 90° (forward on Y) or standing on its side (forward on Z, which
     * then reads gravity too) still gives the car's acceleration, turned round and levelled.
     */
    @Test
    fun `whichever axis points forward is used`() {
        val axes = listOf(ChannelDef(111, "accel_y", "g", "racebox", 3), ChannelDef(112, "accel_z", "g", "racebox", 3))
        for (axis in axes) for (sign in listOf(1.0, -1.0)) {
            val tilt = if (axis.name == "accel_z") 1.0 else 0.05
            val session = telemetrySessionOf(straightRun(sign, tilt, axis = axis), null)
            val forward = assertNotNull(session.channel(Aliases.LONGITUDINAL_ACCELERATION), axis.name)
            assertEquals(0.3, forward.values.first(), 0.01, "${axis.name} sign $sign")
            assertEquals(-0.6, forward.values.last(), 0.01, "${axis.name} sign $sign")
        }
    }

    /**
     * [run] plus the phone's accelerometer, 13 ms after each receiver sample, lying across the car
     * (forward on its -Y) and tipped back 60° in its holder, so its Z reads part of the braking too.
     */
    private fun withPhone(run: SessionData, lateMicros: Long = 13_000): SessionData {
        val phone = listOf("phone_accel_x", "phone_accel_y", "phone_accel_z")
            .mapIndexed { k, name -> ChannelDef(700 + k, name, "g", "phone", 3) }
        val tip = PI / 3
        val sets = run.sampleSets.zipWithNext { a, b ->
            val g = (b.values.first { it.channelId == GnssChannels.SPEED_KMH.id }.value -
                a.values.first { it.channelId == GnssChannels.SPEED_KMH.id }.value) / 3.6 / 9.80665 / 0.04
            SampleSet(
                a.tMicros + lateMicros,
                listOf(
                    ChannelValue(700, 0.02),
                    ChannelValue(701, -g * cos(tip)),
                    ChannelValue(702, g * sin(tip) + cos(tip)),
                ),
            )
        }
        val channels = run.info.channels + phone
        return run.copy(
            info = run.info.copy(channels = channels),
            channels = channels.associateBy { it.id },
            sampleSets = run.sampleSets + sets,
        )
    }

    /** FBBD-218: Arek's RaceBox readings did not match GPS on his drive; the phone's accelerometer then gives G. */
    @Test
    fun `the phone's accelerometer is used when the receiver's does not agree with GPS`() {
        val session = telemetrySessionOf(withPhone(straightRun(1.0, dead = true)), null)
        val forward = assertNotNull(session.channel(Aliases.LONGITUDINAL_ACCELERATION))
        assertEquals(0.013, forward.timestamps.first(), 1e-9, "read at the phone's times")
        assertEquals(0.3, forward.values[10], 0.02)
        assertEquals(-0.6, forward.values[forward.sampleCount - 10], 0.02)
        assertEquals(Provenance.MEASURED_ACCELEROMETER, session.provenanceOf(Aliases.LONGITUDINAL_ACCELERATION))
        assertEquals(GgSource.Gps, assertNotNull(sessionGg(session)).source, "no cornering to fit left on")
    }

    /** FBBD-226: as in RaceChrono, a receiver's and the phone's accelerometers agreeing with GPS are averaged. */
    @Test
    fun `the receiver's and the phone's accelerometers are averaged when both agree`() {
        val session = telemetrySessionOf(withPhone(straightRun(-1.0, tilt = 0.05)), null)
        val forward = assertNotNull(session.channel(Aliases.LONGITUDINAL_ACCELERATION))
        assertEquals(0.0, forward.timestamps.first(), 1e-9, "from the first reading of either")
        assertEquals(0.04, forward.timestamps[1] - forward.timestamps[0], 1e-9, "at 25 Hz")
        assertEquals(0.3, forward.values[10], 0.01)
        assertEquals(-0.6, forward.values[forward.sampleCount - 10], 0.01)
        assertEquals(Provenance.MEASURED_ACCELEROMETER, session.provenanceOf(Aliases.LONGITUDINAL_ACCELERATION))
    }

    /** Codex on #175: a phone stamped 0.3 s late on quick changes still has its mount found. */
    @Test
    fun `a late phone's mount is fitted at its matched clock`() {
        // Pumping the throttle: a quarter period is 0.3 s, so unmatched the phone does not follow GPS at all.
        val pumping = { i: Int -> 0.4 * sin(2 * PI * i * 0.04 / 1.2) }
        val run = straightRun(1.0, dead = true, profile = pumping)
        val session = telemetrySessionOf(withPhone(run, lateMicros = 300_000), null)
        val forward = assertNotNull(session.channel(Aliases.LONGITUDINAL_ACCELERATION), "the phone gives G")
        assertEquals(Provenance.MEASURED_ACCELEROMETER, session.provenanceOf(Aliases.LONGITUDINAL_ACCELERATION))
        val peak = forward.values.filter { it.isFinite() }.maxOf { abs(it) }
        assertEquals(0.4, peak, 0.05, "turned the right way and levelled")
        // Codex on #175: and moved onto the session's clock, so the throttle is where the car did it:
        // within the 50 ms the mount's clock is matched to (0.1 g at the steepest), not 0.3 s late.
        for (t in listOf(5.0, 9.1, 13.3, 17.0)) {
            val at = assertNotNull(forward.valueAt(t), "at $t s")
            assertEquals(0.4 * sin(2 * PI * t / 1.2), at, 0.1, "at $t s")
        }
    }

    @Test
    fun `an accelerometer column of zeros is left out, so GPS is used`() {
        val session = telemetrySessionOf(straightRun(1.0, dead = true), null)
        assertNull(session.aliases[Aliases.LONGITUDINAL_ACCELERATION])
        assertEquals(GgSource.Gps, assertNotNull(sessionGg(session)).source)
    }

    @Test
    fun `a drive without laps has its G-forces, from GPS without a lateral axis`() {
        // No start/finish line, so no laps: the session's G-forces still show its braking.
        val session = telemetrySessionOf(straightRun(-1.0, tilt = -0.187), null)
        val gg = assertNotNull(sessionGg(session))
        // accel_y was not recorded, so the G-G diagram is GPS's for both axes.
        assertEquals(GgSource.Gps, gg.source)
        val braking = gg.points.minOf { it.longitudinalG }
        assertEquals(-0.6, braking, 0.05)
    }

    @Test
    fun `each analysis channel keeps its recorded provenance, the signed acceleration included`() {
        val data = straightRun(1.0)
        val calculatedSpeed = GnssChannels.SPEED_KMH.copy(provenance = Provenance.CALCULATED)
        val channels = data.channels + (calculatedSpeed.id to calculatedSpeed)
        val session = telemetrySessionOf(data.copy(channels = channels), null)
        assertEquals(Provenance.MEASURED_GNSS_RECEIVER, session.provenanceOf(Aliases.LATITUDE))
        assertEquals(Provenance.CALCULATED, session.provenanceOf(Aliases.SPEED))
        assertEquals(Provenance.MEASURED_ACCELEROMETER, session.provenanceOf("accel_x"))
        assertEquals(Provenance.MEASURED_ACCELEROMETER, session.provenanceOf(Aliases.LONGITUDINAL_ACCELERATION))
        val sources = analysisProvenance(session)
        assertEquals(Provenance.CALCULATED, sources.speed)
        assertEquals(Provenance.MEASURED_ACCELEROMETER, sources.acceleration)
        assertNull(sources.brake)
        // A braking point from deceleration is inferred; one from a pedal is the pedal channel's.
        assertEquals(Provenance.INFERRED, sources.braking("inferred"))
        assertNull(sources.braking("measured"))
        assertEquals(Provenance.MEASURED_CAN, sources.copy(brake = Provenance.MEASURED_CAN).braking("measured"))
        assertEquals(Provenance.INFERRED, sources.pickup("inferred"))
        assertEquals(sources, analyzeSession(session).provenance)
    }

    @Test
    fun `channels are in time order with aliases and the start line as gate`() {
        val session = telemetrySessionOf(circleSession(2), line)
        val lat = assertNotNull(session.channel(Aliases.LATITUDE))
        assertTrue(lat.timestamps.asList().zipWithNext().all { (a, b) -> b > a })
        assertEquals(0.0, lat.timestamps.first())
        assertTrue(lat.values.last().isNaN(), "0,0 is no fix")
        assertNotNull(session.channel(Aliases.SPEED))
        assertEquals(1, session.timingGates.size)
    }

    @Test
    fun `a recorded session is analysed and two laps compared`() {
        val session = telemetrySessionOf(circleSession(5), line)
        val analysis = analyzeSession(session)
        // Passes 2 to 5 bound three full laps; the first lap starts at the line without crossing it.
        assertTrue(analysis.laps.timedLaps.size >= 3, "laps: ${analysis.laps.timedLaps.size}")
        val fastest = assertNotNull(analysis.laps.fastestLapIndex)
        val slowest = analysis.laps.timedLaps.indices.maxBy { analysis.laps.timedLaps[it].durationSeconds }
        val comparison = assertNotNull(compareLaps(analysis, session, fastest, slowest))
        val last = comparison.delta.last().last()
        val timed = analysis.laps.timedLaps
        val expected = timed[slowest].durationSeconds - timed[fastest].durationSeconds
        assertTrue(expected > 0, "laps ${timed.map { it.durationSeconds }}")
        assertTrue(abs(last.value - expected) < 0.05, "delta at the line ${last.value} vs $expected")
        // Speed is constant within a lap; the ends may read the next lap's samples.
        val speeds = comparison.referenceSpeed.flatten().map { it.value }
        val median = speeds.sorted()[speeds.size / 2]
        assertTrue(speeds.count { abs(it - median) > 1e-3 } < speeds.size / 50, "speeds $speeds")
    }

    /** A few GNSS samples with a horizontal accuracy channel named [name]. */
    private fun withAccuracy(name: String, id: Int): SessionData {
        val accuracy = ChannelDef(id, name, "m", "receiver", 2)
        val defs = GnssChannels.ALL + accuracy
        val sets = (0 until 10).map { i ->
            SampleSet(i * 40_000L, listOf(ChannelValue(GnssChannels.SPEED_KMH.id, 50.0), ChannelValue(id, 0.8)))
        }
        return SessionData(
            "s", SessionInfo(startUtcMillis = 0, channels = defs), SessionState.FINISHED,
            defs.associateBy { it.id }, sets, emptyList(), null, 0, 0,
        )
    }

    @Test
    fun `the accuracy alias names the receiver's own accuracy channel`() {
        assertEquals("gnss_hacc", telemetrySessionOf(withAccuracy("gnss_hacc", 101), null).aliases[Aliases.ACCURACY])
        assertEquals("dragy_hacc", telemetrySessionOf(withAccuracy("dragy_hacc", 621), null).aliases[Aliases.ACCURACY])
        assertEquals(null, telemetrySessionOf(circleSession(1), null).aliases[Aliases.ACCURACY])
    }
}
