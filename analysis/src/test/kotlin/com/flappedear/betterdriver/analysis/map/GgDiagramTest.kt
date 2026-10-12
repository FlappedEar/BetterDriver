package com.flappedear.betterdriver.analysis.map

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.StandardGravity
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GgDiagramTest {
    private val step = GgStepSeconds

    /** A circle of [radius] at [speed] m/s, every [step] seconds; [direction] +1 anticlockwise (left turn). */
    private fun circle(radius: Double, speed: Double, seconds: Double, direction: Double) =
        (0..(seconds / step).toInt()).map {
            val t = it * step
            val angle = speed * t / radius
            KinematicSample(t, radius * sin(angle), direction * radius * (1 - cos(angle)), speed)
        }

    @Test
    fun `a steady left turn reads its centripetal acceleration to the left`() {
        val g = gpsG(circle(100.0, 20.0, 20.0, 1.0), step)
        assertTrue(g.size > 150)
        val expected = 20.0 * 20.0 / 100.0 / StandardGravity
        for (p in g) {
            assertEquals(expected, p.lateralG, 0.01)
            assertEquals(0.0, p.longitudinalG, 1e-9)
        }
        // And a right turn to the right.
        assertTrue(gpsG(circle(100.0, 20.0, 20.0, -1.0), step).all { abs(it.lateralG + expected) < 0.01 })
    }

    @Test
    fun `speeding up in a straight line reads forward, braking backward`() {
        var v = 10.0
        var x = 0.0
        val samples = (0..200).map {
            val a = if (it < 100) 3.0 else -6.0
            KinematicSample(it * step, x, 0.0, v).also {
                x += v * step
                v += a * step
            }
        }
        val g = gpsG(samples, step)
        val early = g.filter { it.time in 2.0..8.0 }
        val late = g.filter { it.time in 12.0..18.0 }
        assertTrue(early.all { abs(it.longitudinalG - 3.0 / StandardGravity) < 0.01 && abs(it.lateralG) < 1e-6 })
        assertTrue(late.all { abs(it.longitudinalG + 6.0 / StandardGravity) < 0.01 })
    }

    @Test
    fun `a gap splits the lap and nothing is derived when nearly stopped`() {
        val samples: List<KinematicSample?> = circle(100.0, 20.0, 5.0, 1.0) + listOf(null) +
            circle(100.0, 2.0, 5.0, 1.0)
        val g = gpsG(samples, step)
        assertTrue(g.all { it.time <= 5.0 })
        assertTrue(g.isNotEmpty())
    }

    @Test
    fun `without a speed channel the speed comes from the positions`() {
        val g = gpsG(circle(100.0, 20.0, 10.0, 1.0).map { it.copy(speed = null) }, step)
        assertTrue(g.isNotEmpty())
        val expected = 20.0 * 20.0 / 100.0 / StandardGravity
        assertTrue(g.all { abs(it.lateralG - expected) < 0.02 }, "${g.map { it.lateralG }}")
    }

    @Test
    fun `the moving average shrinks its window at the ends`() {
        val averaged = movingAverage(doubleArrayOf(0.0, 3.0, 6.0, 9.0), 1)
        assertEquals(listOf(1.5, 3.0, 6.0, 7.5), averaged.toList())
    }

    /** A left-turn circle as a session, with accelerometer channels of the given signs. */
    private fun session(lateralSign: Double, longitudinalSign: Double): Pair<SessionAnalysis, TelemetrySession> {
        val radius = 100.0
        val speed = 20.0
        val samples = circle(radius, speed, 30.0, 1.0)
        val metersPerDegree = 111_195.0
        val times = samples.map { it.time }.toDoubleArray()
        val lateral = speed * speed / radius / StandardGravity
        val channels = listOf(
            TelemetryChannel("lat", times, samples.map { 50.0 + it.northMeters / metersPerDegree }.toDoubleArray()),
            TelemetryChannel(
                "lon", times,
                samples.map { 19.0 + it.eastMeters / (metersPerDegree * cos(50.0 * PI / 180)) }.toDoubleArray(),
            ),
            TelemetryChannel("speed", times, DoubleArray(times.size) { speed * 3.6 }, "km/h"),
            TelemetryChannel("ax", times, DoubleArray(times.size) { longitudinalSign * 0.1 }, "g"),
            TelemetryChannel("ay", times, DoubleArray(times.size) { lateralSign * lateral }, "g"),
        ).associateBy { it.name }
        val aliases = mapOf(
            Aliases.LATITUDE to "lat",
            Aliases.LONGITUDE to "lon",
            Aliases.SPEED to "speed",
            Aliases.LONGITUDINAL_ACCELERATION to "ax",
            Aliases.LATERAL_ACCELERATION to "ay",
        )
        val lap = TimedLap(1, 1.0, 29.0, 28.0)
        return SessionAnalysis(LapSession(LapSessionStatus.Available, timedLaps = listOf(lap))) to
            TelemetrySession(channels, aliases)
    }

    @Test
    fun `the accelerometer is used, turned round to agree with the GPS turn`() {
        for (sign in listOf(1.0, -1.0)) {
            val (analysis, session) = session(sign, 1.0)
            val trace = assertNotNull(lapGg(analysis, session, 0))
            assertEquals(GgSource.Accelerometer, trace.source)
            assertTrue(trace.points.all { it.lateralG > 0.3 }, "sign $sign")
        }
    }

    @Test
    fun `without an accelerometer the diagram comes from GPS`() {
        val (analysis, session) = session(1.0, 1.0)
        val gpsOnly = TelemetrySession(
            session.channels,
            session.aliases - Aliases.LATERAL_ACCELERATION,
        )
        val trace = assertNotNull(lapGg(analysis, gpsOnly, 0))
        assertEquals(GgSource.Gps, trace.source)
        val lateral = trace.points.map { it.lateralG }.sorted()[trace.points.size / 2]
        assertEquals(20.0 * 20.0 / 100.0 / StandardGravity, lateral, 0.02)
    }
}
