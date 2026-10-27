@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InLapTest {
    /** A reference lap: 1 km at a steady 130 km/h. */
    private val path = LapTrace(
        (0..100).map { Xy(it * 10.0, 0.0) },
        (0..100).map { (it * 10.0 / (130 / 3.6) * 1e6).toLong() },
        List(101) { 130.0 },
    )

    /** Drives [seconds] at 10 Hz from [kmh], slowing at [mps2], on the straight; returns the speed reached. */
    private fun InLap.drive(kmh: Double, mps2: Double, seconds: Double, startMicros: Long = 0L): Double {
        var v = kmh
        var along = 0.0
        for (i in 0..(seconds * 10).toInt()) {
            update(startMicros + i * 100_000L, v, along, path)
            along += v / 3.6 * 0.1
            v -= mps2 * 3.6 * 0.1
        }
        return v
    }

    @Test
    fun `lifting off on a straight for 3 s is cooling down - to the end of the lap - FBBD-202`() {
        val lap = InLap()
        lap.drive(130.0, 1.5, 2.5)
        assertFalse(lap.quiet, "2.5 s of lifting")
        lap.drive(130.0, 1.5, 3.5, startMicros = 10_000_000L)
        assertTrue(lap.quiet)
        assertTrue(lap.cruising)
        assertFalse(lap.onLapDone(125.0, path), "the lap ended at speed")
        assertFalse(lap.quiet, "the next lap is coached")
    }

    @Test
    fun `a lift for traffic ends after two corners in a row at race pace - FBBD-252`() {
        val lap = InLap()
        lap.drive(130.0, 1.5, 3.5)
        assertTrue(lap.cruising)
        // One corner near its best, then one 20 % slower: still cooling down.
        lap.onCornerPass(10_200_000L, 10_000_000L)
        lap.onCornerPass(12_000_000L, 10_000_000L)
        assertTrue(lap.cruising)
        lap.onCornerPass(10_400_000L, 10_000_000L)
        assertTrue(lap.cruising)
        // Two in a row within 5 % of their best: the rest of the lap is coached.
        lap.onCornerPass(10_300_000L, 10_000_000L)
        assertFalse(lap.cruising)
        assertFalse(lap.quiet)
    }

    @Test
    fun `braking - easing off before it and driving on are not cooling down - FBBD-202`() {
        val lap = InLap()
        lap.drive(130.0, 6.0, 4.0)
        lap.drive(130.0, 0.0, 5.0, startMicros = 10_000_000L)
        lap.drive(130.0, 1.5, 1.5, startMicros = 20_000_000L)
        assertFalse(lap.quiet)
        // Off the straight (in a corner) nothing is measured.
        repeat(50) { lap.update(30_000_000L + it * 100_000L, 130.0 - it, null, path) }
        assertFalse(lap.quiet)
    }

    @Test
    fun `slowing below race pace - out of the pit lane - is not cooling down`() {
        val lap = InLap()
        // 70 km/h where the reference lap does 130: still getting up to speed.
        lap.drive(70.0, 1.0, 4.0)
        assertFalse(lap.quiet)
        assertFalse(lap.cruising)
    }

    @Test
    fun `a lap that ends in the pit lane is an in-lap until the car is back up to speed - FBBD-202`() {
        val lap = InLap()
        assertTrue(lap.onLapDone(20.0, path))
        assertTrue(lap.quiet)
        lap.update(1_000_000L, 40.0, null, path)
        assertTrue(lap.quiet)
        lap.update(2_000_000L, 70.0, null, path)
        assertFalse(lap.quiet)
        // Without a reference lap the line speed means nothing.
        assertFalse(InLap().onLapDone(20.0, null))
    }
}
