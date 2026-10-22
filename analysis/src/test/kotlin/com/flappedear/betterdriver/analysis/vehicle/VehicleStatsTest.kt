@file:Suppress("MagicNumber") // Made-up drives.

package com.flappedear.betterdriver.analysis.vehicle

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.recorder.Provenance
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The car's values after a session (FBBD-176, UI-158 C, UI-159 B): only what was recorded. */
class VehicleStatsTest {
    private fun channel(name: String, unit: String, provenance: Provenance?, values: (Double) -> Double) =
        (0..TENTHS).map { it / 10.0 }.let { t ->
            TelemetryChannel(name, t.toDoubleArray(), t.map(values).toDoubleArray(), unit, provenance)
        }

    /**
     * 100 s at 10 Hz above 20 km/h, except 10 s standing still from 90 s: 20 s flat out, 30 s part
     * throttle, 20 s coasting, 20 s braking (the throttle lifted), in that order.
     */
    private fun drive(withBrake: Boolean = true, extra: List<TelemetryChannel> = emptyList()): TelemetrySession {
        val throttle = channel("obd_accelerator", "%", Provenance.MEASURED_OBD) { t ->
            when {
                t < 20 -> 100.0
                t < 50 -> 40.0
                else -> 0.0
            }
        }
        val brake = channel("obd_brake", "%", Provenance.MEASURED_OBD) { t -> if (t in 70.0..<90.0) 50.0 else 0.0 }
        val speed = channel("speed", "km/h", Provenance.MEASURED_GNSS_RECEIVER) { t -> if (t >= 90) 0.0 else 80.0 }
        val channels = listOfNotNull(throttle, brake.takeIf { withBrake }, speed) + extra
        val aliases = mapOf(Aliases.THROTTLE to throttle.name, Aliases.SPEED to speed.name) +
            (if (withBrake) mapOf(Aliases.BRAKE to brake.name) else emptyMap())
        return TelemetrySession(channels.associateBy { it.name }, aliases)
    }

    @Test
    fun `the pedals split the moving time into full, part, coasting and braking`() {
        val p = assertNotNull(pedalShares(drive(), 0.0, 100.0))
        assertEquals(90.0, p.seconds, 0.2)
        assertEquals(20.0 / 90, p.full, 0.01)
        assertEquals(30.0 / 90, p.part, 0.01)
        assertEquals(20.0 / 90, p.coasting, 0.01)
        assertEquals(20.0 / 90, p.braking!!, 0.01)
        assertEquals(1.0, p.full + p.part + p.coasting + p.braking!!, 1e-9)
    }

    @Test
    fun `without a brake, the time off the throttle is all coasting and braking is not known`() {
        val p = assertNotNull(pedalShares(drive(withBrake = false), 0.0, 100.0))
        assertNull(p.braking)
        assertEquals(40.0 / 90, p.coasting, 0.01)
    }

    @Test
    fun `no throttle, or a car that never moved, has no pedal shares`() {
        assertNull(pedalShares(TelemetrySession(emptyMap(), emptyMap()), 0.0, 100.0))
        assertNull(pedalShares(drive(), 91.0, 100.0))
    }

    @Test
    fun `temperatures are the lowest and highest recorded in the stretch, by the app's or RaceChrono's names`() {
        val oil = channel("obd_oil_temp", "°C", Provenance.MEASURED_OBD) { t -> 80.0 + t / 2 }
        val gearbox = channel("gearbox_temp-obd", "", Provenance.MEASURED_OBD) { t -> 70.0 + t / 4 }
        val s = drive(extra = listOf(oil, gearbox))
        val lap = vehicleStretch(s, 20.0, 60.0)
        assertEquals(listOf(VehicleChannel.OilTemp, VehicleChannel.GearboxTemp), lap.temperatures.map { it.kind })
        assertEquals(90.0, lap.temperatures[0].min, 1e-9)
        assertEquals(110.0, lap.temperatures[0].max, 1e-9)
        assertEquals("°C", lap.temperatures[1].unit)
        assertTrue(lap.temperatures.none { it.kind == VehicleChannel.CoolantTemp }, "not recorded, not zero")
    }

    @Test
    fun `a value worked out or of unknown origin is not the car's`() {
        val worked = channel("oil_temp", "°C", Provenance.CALCULATED) { 100.0 }
        val unknown = channel("coolant_temp", "°C", null) { 90.0 }
        val s = drive(extra = listOf(worked, unknown))
        assertNull(vehicleChannel(s, VehicleChannel.OilTemp))
        assertNull(vehicleChannel(s, VehicleChannel.CoolantTemp))
    }

    @Test
    fun `channel names match the app's OBD and RaceChrono's columns, and nothing else`() {
        for (name in listOf("obd_rpm", "rpm-obd", "obd_oil_temp", "engine_oil_temp-obd")) {
            assertTrue(VehicleChannel.Rpm.matches(name) || VehicleChannel.OilTemp.matches(name), name)
        }
        assertTrue(VehicleChannel.GearboxTemp.matches("obd_atf_temp"))
        assertTrue(VehicleChannel.IntakeTemp.matches("obd_intake_air_temp"))
        assertTrue(VehicleChannel.IntakeTemp.matches("intake_temp-obd"))
        assertTrue(VehicleChannel.CoolantTemp.matches("coolant_temp-obd"))
        assertTrue(!VehicleChannel.OilTemp.matches("oil_temp_warning"))
        assertTrue(!VehicleChannel.Rpm.matches("rpm-calc"))
    }

    @Test
    fun `a road drive has one stretch over the whole session, and none without any car values`() {
        val whole = assertNotNull(sessionVehicleStretch(drive()))
        assertNotNull(whole.pedals)
        val gpsOnly = TelemetrySession(
            mapOf("speed" to channel("speed", "km/h", Provenance.MEASURED_GNSS_RECEIVER) { 80.0 }),
            mapOf(Aliases.SPEED to "speed"),
        )
        assertNull(sessionVehicleStretch(gpsOnly))
    }

    @Test
    fun `a chart series averages each bucket and breaks at a gap, never drawing across it`() {
        val times = (0..19).map { it.toDouble() } + (40..59).map { it.toDouble() }
        val values = times.map { if (it < 30) 100.0 else 120.0 }
        val c = TelemetryChannel("x", times.toDoubleArray(), values.toDoubleArray())
        val segments = bucketedSegments(c, 0.0, 59.0, 10.0, 5.0)
        assertEquals(2, segments.size)
        assertEquals(listOf(5.0, 15.0), segments[0].map { it.time })
        assertEquals(listOf(45.0, 55.0), segments[1].map { it.time })
        assertTrue(segments[1].all { it.value == 120.0 })
    }

    @Test
    fun `values the chart does not accept are left out like a gap`() {
        val times = (0..29).map { it.toDouble() }
        val values = times.map { if (it in 10.0..19.0) 0.0 else 100.0 }
        val c = TelemetryChannel("hr", times.toDoubleArray(), values.toDoubleArray())
        val segments = bucketedSegments(c, 0.0, 29.0, 10.0, 5.0) { it > 0.0 }
        assertEquals(2, segments.size)
    }

    private companion object {
        const val TENTHS = 1000
    }
}
