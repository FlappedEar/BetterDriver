package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.live.Level
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The phone's axes are levelled only from readings of a car that really stands (FBBD-337). */
class LevelTest {
    /** A phone upright, tilted back 28°: X across the car, Y mostly up, Z mostly back; [forward] in g. */
    private fun reading(forward: Double, shake: Double = 0.0): DoubleArray {
        val c = cos(Math.toRadians(28.0))
        val s = sin(Math.toRadians(28.0))
        return doubleArrayOf(shake, -s * forward + c + shake, -c * forward - s - shake)
    }

    @Test
    fun `a second standing with idle shake levels the axes, gravity reads straight up`() {
        val level = Level()
        val levelled = (0 until 25).map { level.update(0.0, reading(0.0, if (it % 2 == 0) 0.01 else -0.01)) }
        assertEquals(listOf(true), levelled.filter { it })
        val up = level.apply(reading(0.0))
        assertEquals(1.0, up[2], 1e-3)
        assertEquals(0.0, up[0], 1e-3)
        assertEquals(0.0, up[1], 1e-3)
    }

    @Test
    fun `pulling away while the last 1 Hz fix still says standing does not level the axes`() {
        val level = Level()
        // Half a second standing, then a 0.5 g start, the speed still the standing fix's.
        val levelled = (0 until 25).map { level.update(0.0, reading(if (it < 12) 0.0 else 0.5)) }
        assertFalse(levelled.any { it })
        // A steady 0.3 g start reads 1.04 g: not gravity alone either.
        assertFalse((0 until 25).map { level.update(0.0, reading(0.3)) }.any { it })
        // Standing again: levelled.
        assertTrue((0 until 25).map { level.update(0.0, reading(0.0)) }.last())
    }

    @Test
    fun `a moving reading starts the run again`() {
        val level = Level()
        repeat(20) { level.update(0.0, reading(0.0)) }
        level.update(30.0, reading(0.2))
        assertFalse((0 until 20).map { level.update(0.0, reading(0.0)) }.any { it })
        assertTrue((0 until 5).map { level.update(0.0, reading(0.0)) }.last())
    }
}
