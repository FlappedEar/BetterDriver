package com.flappedear.betterdriver.recorder.accel

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The window means in one sweep give what a search and a walk per window give. */
class WindowMeansTest {
    /** The mean of the finite values within [half] of [t], both ends included, the slow way. */
    private fun walked(times: DoubleArray, values: DoubleArray, t: Double, half: Double): Double {
        val inside = times.indices.filter { times[it] >= t - half && times[it] <= t + half && values[it].isFinite() }
        return if (inside.isEmpty()) Double.NaN else inside.sumOf { values[it] } / inside.size
    }

    private fun assertSame(expected: Double, actual: Double, what: String) {
        if (expected.isNaN()) {
            assertTrue(actual.isNaN(), "$what: $actual, expected none")
        } else {
            assertEquals(expected, actual, 1e-9, what)
        }
    }

    @Test
    fun `every window's mean matches the walk, with gaps, missing values and a shift`() {
        val random = Random(7)
        // About 110 samples a second as the phone records, with a 1 s gap and some values missing.
        val times = (0 until 2_000).map { it / 109.3 }.filterNot { it in 5.0..6.0 }.toDoubleArray()
        val values = DoubleArray(times.size) {
            if (random.nextInt(20) == 0) Double.NaN else random.nextDouble(-1.0, 1.0)
        }
        val at = DoubleArray(400) { -0.5 + it * 0.05 }
        val windows = WindowMeans(times, values)
        for (shift in listOf(0.0, 0.03, -0.3)) for (half in listOf(0.1, 0.2)) {
            val means = windows.at(at, half, shift)
            for (j in at.indices) {
                assertSame(walked(times, values, at[j] + shift, half), means[j], "t ${at[j]} shift $shift half $half")
            }
        }
    }

    @Test
    fun `times out of order and not finite still get their own windows`() {
        val times = DoubleArray(100) { it * 0.04 }
        val values = DoubleArray(100) { it.toDouble() }
        val at = doubleArrayOf(3.0, 1.0, Double.NaN, 2.0, 0.0)
        val means = WindowMeans(times, values).at(at, 0.05)
        for (j in at.indices) assertSame(walked(times, values, at[j], 0.05), means[j], "t ${at[j]}")
    }
}
