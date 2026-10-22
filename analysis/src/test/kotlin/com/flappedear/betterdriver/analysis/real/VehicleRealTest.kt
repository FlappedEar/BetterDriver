@file:Suppress("MagicNumber") // Arek's lap times, temperatures and heart rate, as RaceChrono recorded them.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.heartrate.heartRateSeries
import com.flappedear.betterdriver.analysis.heartrate.heartRateStats
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.vehicle.VehicleChannel
import com.flappedear.betterdriver.analysis.vehicle.lapVehicleStretches
import com.flappedear.betterdriver.analysis.vehicle.sessionVehicleStretch
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Car tab (FBBD-176) on Arek's Jastrząb session of 29 Aug 2026 from 17:20 local, imported as the
 * app imports it: 15 min with OBD pedals, rpm and temperatures at 10 Hz and a worn Polar H10. Its three
 * timed laps were 1:55.4, 1:56.3 and 1:51.2; oil rose to 128 °C, the gearbox to 111 °C, and his heart
 * rate averaged 132 bpm, 105 to 157 (read from the file itself, the figures of UI review round 40).
 */
class VehicleRealTest {
    @TempDir
    lateinit var tmp: File

    private val track = RealCorpus.jastrzabTrack()

    private fun session(name: String) = run {
        val store = SessionStore(File(tmp, "sessions"))
        val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
        telemetrySessionOf(store.read(id), track)
    }

    private val jastrzab1720 = RealCorpus.JASTRZAB_DAY.single { "-1720-" in it }

    @Test
    fun `every timed lap has its pedals and its temperatures, oil rising lap by lap`() {
        val s = session(jastrzab1720)
        val laps = deriveSourceLapSession(s).timedLaps
        val fast = laps.filter { it.durationSeconds in 110.0..118.0 }
        assertEquals(3, fast.size, "laps: ${laps.map { it.durationSeconds }}")
        val stretches = lapVehicleStretches(s, fast)
        for ((lap, stretch) in fast.zip(stretches)) {
            val pedals = assertNotNull(stretch.pedals, "lap ${lap.number} pedals")
            val sum = pedals.full + pedals.part + pedals.coasting + (pedals.braking ?: 0.0)
            assertEquals(1.0, sum, 1e-9)
            // Flat out for about a third of each lap at race pace (28 to 36 % with the pedal at 90 % or more).
            assertTrue(pedals.full in 0.22..0.42, "lap ${lap.number}: full ${pedals.full}")
            pedals.braking?.let { assertTrue(it in 0.08..0.32, "lap ${lap.number}: braking $it") }
            val kinds = stretch.temperatures.map { it.kind }
            assertEquals(VehicleChannel.Temperatures, kinds, "lap ${lap.number}")
        }
        val oil = stretches.map { st -> st.temperatures.single { it.kind == VehicleChannel.OilTemp } }
        assertTrue(oil.zipWithNext().all { (a, b) -> b.max >= a.max }, "oil by lap: $oil")
        assertEquals(128.0, oil.last().max, 1.0)
        val gearbox = stretches.last().temperatures.single { it.kind == VehicleChannel.GearboxTemp }
        assertEquals(110.0, gearbox.max, 1.0)
    }

    @Test
    fun `the whole session reads as one drive, for a session without laps`() {
        val whole = assertNotNull(sessionVehicleStretch(session(jastrzab1720)))
        assertNotNull(whole.pedals)
        assertEquals(128.0, whole.temperatures.single { it.kind == VehicleChannel.OilTemp }.max, 1.0)
        assertEquals(111.0, whole.temperatures.single { it.kind == VehicleChannel.GearboxTemp }.max, 1.0)
    }

    @Test
    fun `the heart rate of an imported session shows, and its chart stays within what was recorded`() {
        val s = session(jastrzab1720)
        val hr = assertNotNull(heartRateStats(s))
        assertEquals(132, hr.averageBpm, "average")
        assertEquals(157, hr.maxBpm, "max")
        assertEquals(105, hr.minBpm, "min")
        val series = heartRateSeries(s)
        assertTrue(series.isNotEmpty())
        assertTrue(series.flatten().all { it.value in 105.0..157.0 }, "chart values")
        // 15 minutes at one point each 10 s, with no dropout in this session.
        assertTrue(series.flatten().size in 85..95, "${series.flatten().size} points")
    }
}
