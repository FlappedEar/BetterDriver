@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.geo.Xy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the car is across the road and where the pedal goes back on (FBBD-307), and what the coach makes of it. */
class LineAdviceTest {
    /** A 1 km straight eastwards at 1 m steps, [y] metres north (left) of the line at each metre. */
    private fun trace(kmh: (Int) -> Double, y: (Int) -> Double = { 0.0 }, pedal: ((Int) -> Double?)? = null): LapTrace {
        val n = 1_000
        var t = 0.0
        val times = LongArray(n + 1)
        for (i in 1..n) {
            t += 3.6 / kmh(i) * 1_000_000
            times[i] = t.toLong()
        }
        return LapTrace(
            List(n + 1) { Xy(it.toDouble(), y(it)) },
            times.toList(),
            List(n + 1) { kmh(it) },
            throttlesPct = pedal?.let { p -> List(n + 1) { p(it) } },
        )
    }

    /** From 150 km/h braking at 30 m to [min] at the 70 m apex, then back to 150 over 40 m. */
    private fun kmh(at: Int, min: Double = 80.0): Double = when {
        at < 30 || at > 110 -> 150.0
        at <= 70 -> 150.0 - (150.0 - min) * (at - 30) / 40.0
        else -> min + (150.0 - min) * (at - 70) / 40.0
    }

    private val corner = Corner(1, brakeAt = 30.0, apexAt = 70.0, entryKmh = 150.0, minKmh = 80.0, turn = Turn.Left)
    private val map = CornerMap(trace({ kmh(it) }), listOf(corner))

    private fun pass(y: (Int) -> Double = { 0.0 }, pedal: ((Int) -> Double?)? = null): CornerPass =
        assertNotNull(map.passes(trace({ kmh(it) }, y, pedal), 1).singleOrNull())

    /** Full throttle to the 30 m braking point, off, a heel-and-toe blip at 32 to 38 m, back on at [on] m. */
    private fun pedal(on: Int): (Int) -> Double = { at ->
        when {
            at < 30 -> 100.0
            at in 32..38 -> 40.0
            at < on -> 0.0
            else -> 60.0
        }
    }

    @Test
    fun `a pass knows where it was across the road at its apex, left positive`() {
        assertEquals(0.0, assertNotNull(pass().apexOffsetMeters), 0.01)
        assertEquals(2.5, assertNotNull(pass(y = { 2.5 }).apexOffsetMeters), 0.01)
        assertEquals(-3.0, assertNotNull(pass(y = { -3.0 }).apexOffsetMeters), 0.01)
        assertEquals(-1.0, assertNotNull(map.offset(Xy(10.0, -1.0), 10.0)), 1e-9)
    }

    @Test
    fun `the throttle point is where the pedal goes back on, not a blip while braking`() {
        assertNull(pass().pickupAt)
        assertEquals(66.0, assertNotNull(pass(pedal = pedal(66)).pickupAt), 1.0)
        assertEquals(85.0, assertNotNull(pass(pedal = pedal(85)).pickupAt), 1.0)
        // A gap in the pedal is not bridged.
        assertNull(pass(pedal = { at -> if (at in 40..90) null else pedal(66)(at) }).pickupAt)
    }

    @Test
    fun `throttle advice says how much earlier, beyond what the receiver repeats`() {
        val late = pass(pedal = pedal(85)).copy(timeMicros = 3_000_000)
        val target = pass(pedal = pedal(70)).copy(timeMicros = 2_800_000)
        assertEquals(15, pickupChange(late, target))
        assertEquals(0, pickupAfterApex(target))
        assertNull(pickupChange(late, pass(pedal = pedal(83)).copy(timeMicros = 2_800_000)))
        assertEquals(-15, pickupChange(target.copy(timeMicros = 3_000_000), late.copy(timeMicros = 2_800_000)))
        assertTrue(improvedPickup(15, late, pass(pedal = pedal(76))))
        assertFalse(improvedPickup(15, late, pass(pedal = pedal(80))))
    }

    @Test
    fun `the pedal reaches the coach from its own channel while it is recent`() {
        val defs = listOf(
            ChannelDef(1, "lat", "deg", "gnss"),
            ChannelDef(40, "obd_throttle", "%", "obd"),
            ChannelDef(41, "obd_accelerator", "%", "obd"),
        )
        assertEquals(41, pedalChannelId(defs))
        assertEquals(40, pedalChannelId(defs.take(2)))
        assertNull(pedalChannelId(defs.take(1)))
        val reader = CoachFixReader(41)
        fun fix(t: Long) = SampleSet(
            t,
            listOf(ChannelValue(GnssChannels.LAT.id, 52.0), ChannelValue(GnssChannels.LON.id, 16.0)),
        )
        assertNull(reader.read(fix(0)).let { assertNotNull(it).throttlePct })
        assertNull(reader.read(SampleSet(100_000, listOf(ChannelValue(41, 35.0)))))
        assertEquals(35.0, reader.read(fix(200_000))?.throttlePct)
        assertNull(reader.read(fix(700_000))?.throttlePct)
    }

    @Test
    fun `a line within what the receiver repeats is not advice`() {
        val last = pass().copy(timeMicros = 3_000_000)
        assertNull(lineChange(last, pass(y = { 1.2 }).copy(timeMicros = 2_800_000), corner))
        assertEquals(3, lineChange(last, pass(y = { 3.0 }).copy(timeMicros = 2_800_000), corner))
        // The same line is wider in a right-hander.
        val right = corner.copy(turn = Turn.Right)
        assertEquals(-3, lineChange(last, pass(y = { 3.0 }).copy(timeMicros = 2_800_000), right))
        // Never more than MaxLineMeters at once.
        assertEquals(MaxLineMeters, lineChange(last, pass(y = { 9.0 }).copy(timeMicros = 2_800_000), corner))
        // Nothing to say without losing time to the target.
        assertNull(lineChange(last, pass(y = { 3.0 }).copy(timeMicros = 2_990_000), corner))
    }

    @Test
    fun `a line cue worked when the next pass moved at least half the way`() {
        val before = pass()
        assertTrue(improvedLine(3, before, pass(y = { 1.6 }), Turn.Left))
        assertFalse(improvedLine(3, before, pass(y = { 1.0 }), Turn.Left))
        assertFalse(improvedLine(3, before, pass(y = { 3.0 }), Turn.Right))
        assertTrue(improvedLine(-2, before, pass(y = { -1.2 }), Turn.Left))
    }
}
