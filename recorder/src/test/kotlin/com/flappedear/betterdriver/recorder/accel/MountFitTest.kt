package com.flappedear.betterdriver.recorder.accel

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-218: an accelerometer in any mount gives the car's forward and left acceleration. */
class MountFitTest {
    private val n = 3_000

    /** Braking into corners: forward and left move together, as on track. */
    private val forward = DoubleArray(n) { i -> 0.5 * sin(i * 0.013) }
    private val left = DoubleArray(n) { i -> 0.8 * sin(i * 0.021 + 1.0) + 0.4 * forward[i] }

    /**
     * Readings of an accelerometer turned by [yaw] about the car's up axis, then tipped back by
     * [pitch] about its own Y axis (a phone in a holder), gravity included, with a little noise.
     */
    private fun readings(yaw: Double, pitch: Double, f: DoubleArray, l: DoubleArray): List<DoubleArray> {
        val random = Random(7)
        // The device's X, Y and Z axes in the car's frame (forward, left, up).
        val x0 = doubleArrayOf(cos(yaw), sin(yaw), 0.0)
        val y0 = doubleArrayOf(-sin(yaw), cos(yaw), 0.0)
        val z0 = doubleArrayOf(0.0, 0.0, 1.0)
        val x = DoubleArray(3) { cos(pitch) * x0[it] - sin(pitch) * z0[it] }
        val z = DoubleArray(3) { sin(pitch) * x0[it] + cos(pitch) * z0[it] }
        return listOf(x, y0, z).map { axis ->
            DoubleArray(f.size) { i ->
                axis[0] * f[i] + axis[1] * l[i] + axis[2] * 1.0 + random.nextDouble(-0.02, 0.02)
            }
        }
    }

    private fun assertRecovers(fit: AxisDirection?, axes: List<DoubleArray>, expected: DoubleArray, what: String) {
        val d = assertNotNull(fit, what)
        assertTrue(d.usable, what)
        val worst = expected.indices.maxOf { i ->
            kotlin.math.abs(d.correct(DoubleArray(axes.size) { axes[it][i] }) - expected[i])
        }
        assertTrue(worst < 0.05, "$what: worst error $worst g")
    }

    @Test
    fun `a receiver turned round or by 90 degrees and a tilted phone all give forward and left`() {
        for (yaw in listOf(0.0, PI / 2, PI, -PI / 2, 0.6)) for (pitch in listOf(0.0, 0.3, 1.2)) {
            val axes = readings(yaw, pitch, forward, left)
            val fit = assertNotNull(fitMount(axes, forward, left), "yaw $yaw pitch $pitch")
            assertRecovers(fit.forward, axes, forward, "forward, yaw $yaw pitch $pitch")
            assertRecovers(fit.left, axes, left, "left, yaw $yaw pitch $pitch")
        }
    }

    /** Fitting each direction alone would let the cornering that comes with braking leak into forward. */
    @Test
    fun `forward and left come out at right angles, so braking in a corner does not leak`() {
        val axes = readings(0.6, 1.2, forward, left)
        val fit = assertNotNull(fitMount(axes, forward, left))
        val f = assertNotNull(fit.forward).weights
        val l = assertNotNull(fit.left).weights
        assertEquals(0.0, f.indices.sumOf { f[it] * l[it] }, 1e-9)
        assertEquals(1.0, f.sumOf { it * it }, 1e-9)
    }

    @Test
    fun `on a straight only forward is found`() {
        val still = DoubleArray(n)
        val axes = readings(PI / 2, 0.3, forward, still)
        val fit = assertNotNull(fitMount(axes, forward, still))
        assertRecovers(fit.forward, axes, forward, "forward")
        assertNull(fit.left?.takeIf { it.usable })
    }

    /**
     * A receiver that sends only its X axis (an RC2 line with the Y and Z fields empty) on a track
     * where braking and cornering come together: the one axis must not be both forward and left.
     */
    @Test
    fun `a one-axis accelerometer gives one direction, not the same reading twice`() {
        val trailBraking = DoubleArray(n) { i -> 0.9 * forward[i] + 0.1 * sin(i * 0.05) }
        val axes = readings(0.0, 0.0, forward, trailBraking).take(1)
        val fit = assertNotNull(fitMount(axes, forward, trailBraking))
        assertRecovers(fit.forward, axes, forward, "forward")
        assertNull(fit.left)
    }

    @Test
    fun `too few readings give no fit`() {
        val few = forward.copyOf(10)
        assertNull(fitMount(readings(0.0, 0.0, few, few), few, few))
    }
}
