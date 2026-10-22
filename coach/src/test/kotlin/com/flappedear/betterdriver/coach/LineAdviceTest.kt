@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the car is across the road (FBBD-307), and what the coach makes of it. */
class LineAdviceTest {
    /** A 1 km straight eastwards at 1 m steps, [y] metres north (left) of the line at each metre. */
    private fun trace(kmh: (Int) -> Double, y: (Int) -> Double = { 0.0 }): LapTrace {
        val n = 1_000
        var t = 0.0
        val times = LongArray(n + 1)
        for (i in 1..n) {
            t += 3.6 / kmh(i) * 1_000_000
            times[i] = t.toLong()
        }
        return LapTrace(List(n + 1) { Xy(it.toDouble(), y(it)) }, times.toList(), List(n + 1) { kmh(it) })
    }

    /** From 150 km/h braking at 30 m to [min] at the 70 m apex, then back to 150 over 40 m. */
    private fun kmh(at: Int, min: Double = 80.0): Double = when {
        at < 30 || at > 110 -> 150.0
        at <= 70 -> 150.0 - (150.0 - min) * (at - 30) / 40.0
        else -> min + (150.0 - min) * (at - 70) / 40.0
    }

    private val corner = Corner(1, brakeAt = 30.0, apexAt = 70.0, entryKmh = 150.0, minKmh = 80.0, turn = Turn.Left)
    private val map = CornerMap(trace({ kmh(it) }), listOf(corner))

    private fun pass(y: (Int) -> Double = { 0.0 }): CornerPass =
        assertNotNull(map.passes(trace({ kmh(it) }, y), 1).singleOrNull())

    @Test
    fun `a pass knows where it was across the road at its apex, left positive`() {
        assertEquals(0.0, assertNotNull(pass().apexOffsetMeters), 0.01)
        assertEquals(2.5, assertNotNull(pass(y = { 2.5 }).apexOffsetMeters), 0.01)
        assertEquals(-3.0, assertNotNull(pass(y = { -3.0 }).apexOffsetMeters), 0.01)
        assertEquals(-1.0, assertNotNull(map.offset(Xy(10.0, -1.0), 10.0)), 1e-9)
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
