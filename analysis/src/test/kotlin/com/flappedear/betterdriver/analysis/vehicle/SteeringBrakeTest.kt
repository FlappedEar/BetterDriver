@file:Suppress("MagicNumber") // Made-up laps.

package com.flappedear.betterdriver.analysis.vehicle

import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.recorder.Provenance
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The FR-S steering wheel and brake pressure on the Car tab (FBBD-322, UI-250 A, UI-251 A). */
class SteeringBrakeTest {
    /** 100 s at 50 Hz of [values], as the FR-S's CAN channels are recorded. */
    private fun channel(
        name: String,
        unit: String,
        provenance: Provenance? = Provenance.MEASURED_OBD,
        values: (Double) -> Double,
    ) =
        (0..5000).map { it / 50.0 }.let { t ->
            TelemetryChannel(name, t.toDoubleArray(), t.map(values).toDoubleArray(), unit, provenance)
        }

    private fun session(vararg c: TelemetryChannel) = TelemetrySession(c.associateBy { it.name }, emptyMap())

    /** Two 50 s laps: 120° left from 10 to 14 s, 95° right from 30 to 33 s, 200° left from 60 to 61 s. */
    private val wheel = channel("obd_steering_angle", "deg") { t ->
        when {
            t >= 10 && t < 14 -> 120.0
            t >= 30 && t < 33 -> -95.0
            t >= 60 && t < 61 -> 200.0
            else -> 20.0
        }
    }
    private val laps = listOf(0.0 to 50.0, 50.0 to 100.0)

    @Test
    fun `the wheel gives the most lock each way and the time over 90 degrees`() {
        val s = assertNotNull(steeringStats(session(wheel), laps))
        assertEquals(200.0, s.mostLeftDegrees, 1e-9)
        assertEquals(95.0, s.mostRightDegrees, 1e-9)
        assertEquals(8.0, s.largeLockSeconds, 0.05)
        assertEquals(2, s.stretches)
    }

    @Test
    fun `no wheel, or one worked out, gives no steering`() {
        assertNull(steeringStats(session(), laps))
        val worked = channel("obd_steering_angle", "deg", Provenance.CALCULATED) { 10.0 }
        assertNull(steeringStats(session(worked), laps))
    }

    @Test
    fun `the trace starts at the lap's start and follows the wheel`() {
        val trace = steeringTrace(session(wheel), 50.0, 100.0)
        val points = trace.flatten()
        assertTrue(points.first().time < 0.5, "from the lap's start")
        assertEquals(200.0, points.maxOf { it.value }, 1e-9)
        assertTrue(points.last().time <= 50.2, "to the lap's end, at the middle of its last bucket")
    }

    @Test
    fun `brake pressure gives the hardest press, the typical stop and the time at half braking or more`() {
        // Stops of 60, 30 and 50 bar in lap 1, one of 40 in lap 2; light foot (3 bar) between them.
        val pressure = channel("obd_brake_pressure", "bar") { t ->
            when {
                t >= 5 && t < 7 -> 60.0
                t >= 15 && t < 17 -> 30.0
                t >= 25 && t < 26 -> 50.0
                t >= 70 && t < 72 -> 40.0
                else -> 3.0
            }
        }
        val b = assertNotNull(brakePressureStats(session(pressure), laps))
        assertEquals(60.0, b.hardest, 1e-9)
        assertEquals(45.0, b.typicalStop!!, 1e-9)
        assertEquals(3.0, b.hardSeconds, 0.05)
        assertEquals(2, b.stretches)
        assertEquals("bar", b.unit)
    }

    @Test
    fun `a stop across the line between two laps counts once`() {
        // One 60 bar stop from 48 to 52 s, across the line at 50 s, and one of 20 bar in lap 2.
        val pressure = channel("obd_brake_pressure", "bar") { t ->
            when {
                t >= 48 && t < 52 -> 60.0
                t >= 70 && t < 71 -> 20.0
                else -> 0.0
            }
        }
        val b = assertNotNull(brakePressureStats(session(pressure), laps))
        assertEquals(40.0, b.typicalStop!!, 1e-9, "median of 60 and 20, not of 60, 60 and 20")
    }

    @Test
    fun `a gap in the readings adds no time`() {
        // 120° held, but read only at 0 and 10 s: the 10 s between is a gap, not 10 s of lock.
        val sparse = TelemetryChannel(
            "obd_steering_angle", doubleArrayOf(0.0, 10.0), doubleArrayOf(120.0, 120.0), "deg", Provenance.MEASURED_OBD,
        )
        val s = assertNotNull(steeringStats(session(sparse), listOf(0.0 to 20.0)))
        assertEquals(0.0, s.largeLockSeconds, 1e-9)
    }

    @Test
    fun `a pressure in another unit, or none, is not shown`() {
        assertNull(brakePressureStats(session(channel("obd_brake_pressure", "kPa") { 100.0 }), laps))
        assertNull(brakePressureStats(session(wheel), laps))
    }

    @Test
    fun `the brake pressure channel is found by the app's and RaceChrono's names`() {
        assertTrue(VehicleChannel.BrakePressure.matches("obd_brake_pressure"))
        assertTrue(VehicleChannel.BrakePressure.matches("brake_pressure-canbus"))
        assertTrue(!VehicleChannel.BrakePressure.matches("obd_brake"))
    }
}
