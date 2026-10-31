@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.timing.gear.FrsAutomatic
import com.flappedear.betterdriver.timing.gear.LiveGear
import com.flappedear.betterdriver.timing.overlay.OverlayData
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FBBD-397: the gear on the track screen's Gear tile (UI-309 A) and on the video overlay's RPM gauge
 * (UI-310 A), from engine speed over road speed with the gearbox the session's own readings fit.
 */
class LiveGearTest {
    private val rpmDef = ChannelDef(200, "obd_rpm", "rpm", "obd")

    /** Engine speed per m/s per unit of gear ratio: a 4.1 final drive and a 1.964 m tyre, as the FR-S. */
    private val scale = 4.1 * 60 / 1.964

    /** Speed in m/s at [t] seconds: 15 to 45 m/s and back every minute, as a lap of straights and corners. */
    private fun speedAt(t: Double): Double {
        val phase = (t % 60.0) / 60.0
        return 15.0 + 30.0 * (if (phase < 0.5) phase * 2 else (1 - phase) * 2)
    }

    /** The gear an automatic in M would be in at [speed]: 2nd, 3rd or 4th, as Arek's sessions show. */
    private fun gearFor(speed: Double): Int = when {
        speed < 25 -> 2
        speed < 35 -> 3
        else -> 4
    }

    private fun rpm(speed: Double, gear: Int) = speed * scale * FrsAutomatic.ratios[gear - 1]

    /** [seconds] of driving at 10 Hz, speed and engine speed in each set; [engine] gives the engine speed. */
    private fun drive(seconds: Int, engine: (Double) -> Double = { rpm(it, gearFor(it)) }): List<SampleSet> =
        (0 until seconds * 10).map { i ->
            val t = i / 10.0
            val s = speedAt(t)
            SampleSet(
                (t * 1e6).toLong(),
                listOf(
                    ChannelValue(GnssChannels.LAT.id, 50.0 + t * 1e-5),
                    ChannelValue(GnssChannels.LON.id, 19.0),
                    ChannelValue(GnssChannels.SPEED_KMH.id, s * 3.6),
                    ChannelValue(rpmDef.id, engine(s)),
                ),
            )
        }

    private fun reading(t: Long, mps: Double, rpm: Double) =
        SampleSet(t, listOf(ChannelValue(GnssChannels.SPEED_KMH.id, mps * 3.6), ChannelValue(rpmDef.id, rpm)))

    private fun snapshot() = LiveSnapshot("s", 0L, 0L, 0L, 0, null, null, null, null, null, 0L)

    @Test
    fun `the tile reads the gear once the session has fitted the gearbox`() {
        val sets = drive(150)
        var now = 0L
        val gear = LiveGear { now }
        gear.onChannel(rpmDef)
        // Before a fit: engine speed has arrived, so the tile is there with a dash (UI-309 A).
        sets.take(50).forEach { gear.onSamples(it); now = it.tMicros }
        assertNull(gear.gear())
        val early = gear.withGear(snapshot()).channels[LiveGear.GearChannel]
        assertTrue(early != null && early.isNaN(), "a dash until a gearbox fits: $early")

        sets.drop(50).forEach { gear.onSamples(it); now = it.tMicros }
        val box = assertNotNull(gear.gearbox, "2nd to 4th of the 6-speed automatic fit")
        assertEquals(listOf(2, 3, 4), box.gears.map { it.gear })
        val last = sets.last()
        val speed = last.values.first { it.channelId == GnssChannels.SPEED_KMH.id }.value / 3.6
        assertEquals(gearFor(speed), gear.gear())
        assertEquals(gearFor(speed).toDouble(), gear.withGear(snapshot()).channels[LiveGear.GearChannel])
    }

    @Test
    fun `a shift, an old reading or standing shows a dash, not a gear`() {
        var now = 0L
        val gear = LiveGear { now }
        gear.onChannel(rpmDef)
        drive(150).forEach { gear.onSamples(it); now = it.tMicros }
        assertNotNull(gear.gearbox)
        // Halfway between 2nd and 3rd at 24 m/s: the converter slipping through a shift.
        val between = (rpm(24.0, 2) + rpm(24.0, 3)) / 2
        now += 100_000
        gear.onSamples(reading(now, 24.0, between))
        assertNull(gear.gear())
        // In 2nd again, then nothing for two seconds: the reading is old.
        now += 100_000
        gear.onSamples(reading(now, 24.0, rpm(24.0, 2)))
        assertEquals(2, gear.gear())
        now += 2_000_000
        assertNull(gear.gear())
        // Standing at idle.
        gear.onSamples(reading(now, 0.0, 750.0))
        assertNull(gear.gear())
    }

    @Test
    fun `an engine that follows no gears, as a hybrid's, leaves the tile out`() {
        var now = 0L
        val gear = LiveGear { now }
        gear.onChannel(rpmDef)
        // The engine runs at its own speed, whatever the road speed (the Corolla's e-CVT).
        val sets = drive(150) { s -> 1500.0 + 2500.0 * abs(sin(s * 7.3)) }
        sets.forEach { gear.onSamples(it); now = it.tMicros }
        assertNull(gear.gearbox)
        assertFalse(gear.shows())
        assertNull(gear.withGear(snapshot()).channels[LiveGear.GearChannel])
    }

    @Test
    fun `without engine speed there is no Gear tile`() {
        val gear = LiveGear { 0L }
        drive(10).forEach { set ->
            gear.onSamples(SampleSet(set.tMicros, set.values.filter { it.channelId != rpmDef.id }))
        }
        assertFalse(gear.shows())
    }

    @Test
    fun `the video's RPM gauge has the gear at each moment, and none without engine speed`() {
        val sets = drive(150)
        val data = OverlayData(sets, laps = null, channels = listOf(rpmDef))
        assertNotNull(data.gearbox)
        for (t in listOf(3.0, 12.0, 31.0, 47.0, 55.0, 112.0)) {
            val frame = data.at((t * 1e6).toLong())
            assertTrue(frame.car.gears)
            assertEquals(gearFor(speedAt(t)), frame.car.gear, "at $t s")
        }
        val noRpm = sets.map { s -> SampleSet(s.tMicros, s.values.filter { it.channelId != rpmDef.id }) }
        val plain = OverlayData(noRpm, null)
        assertNull(plain.gearbox)
        assertFalse(plain.at(20_000_000).car.gears)
    }
}
