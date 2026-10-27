@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ImuCalibrationTest {
    private val rnd = Random(7)

    /** Still samples from a RaceBox tilted by [rollDeg] about X, with a gyro offset and a little noise. */
    private fun still(rollDeg: Double, bias: Vec3, n: Int = 100, noise: Double = 0.005): List<ImuSample> {
        val r = radians(rollDeg)
        return List(n) {
            fun j() = (rnd.nextDouble() - 0.5) * 2 * noise
            ImuSample(
                Vec3(j(), sin(r) + j(), cos(r) + j()),
                Vec3(bias.x + j() * 10, bias.y + j() * 10, bias.z + j() * 10),
            )
        }
    }

    private fun ok(r: ImuCalibration.Result) = (r as ImuCalibration.Result.Ok).calibration

    @Test
    fun `a level RaceBox only loses its gyro offset`() {
        val c = ok(ImuCalibration.from(still(0.0, Vec3(0.3, -0.2, 0.1)), 1))
        val g = c.gyro(Vec3(0.3, -0.2, 0.1))
        assertEquals(0.0, g.length, 0.01)
        assertEquals(0.0, c.rollDeg, 0.5)
        val a = c.accel(Vec3(0.5, 0.0, 1.0))
        assertEquals(0.5, a.x, 0.01)
    }

    @Test
    fun `a tilted RaceBox reads as level after calibration`() {
        val c = ok(ImuCalibration.from(still(12.0, Vec3(0.0, 0.0, 0.0)), 1))
        assertEquals(12.0, c.rollDeg, 0.5)
        val r = radians(12.0)
        val gravity = c.accel(Vec3(0.0, sin(r), cos(r)))
        assertEquals(0.0, gravity.x, 1e-3)
        assertEquals(0.0, gravity.y, 1e-3)
        assertEquals(1.0, gravity.z, 1e-3)
        // A 0.5 g push sideways on the car is measured along the tilted Y axis; after levelling it is pure Y.
        val side = c.accel(Vec3(0.0, 0.5 * cos(r), -0.5 * sin(r)))
        assertEquals(0.5, side.y, 1e-3)
        assertEquals(0.0, side.z, 1e-3)
    }

    @Test
    fun `a RaceBox mounted label down keeps X forward`() {
        for (roll in listOf(180.0, 177.0, -175.0)) {
            val c = ok(ImuCalibration.from(still(roll, Vec3(0.0, 0.0, 0.0)), 1))
            val r = radians(roll)
            val gravity = c.accel(Vec3(0.0, sin(r), cos(r)))
            assertEquals(1.0, gravity.z, 1e-3)
            // Braking along the car's X axis stays on X, and a sideways push stays on Y with its sign.
            val brake = c.accel(Vec3(-0.8, 0.0, 0.0))
            assertEquals(-0.8, brake.x, 5e-3)
            assertEquals(0.0, brake.y, 5e-3)
            val side = c.accel(Vec3(0.0, 0.5 * cos(r), -0.5 * sin(r)))
            assertEquals(0.5, side.y, 5e-3)
            assertEquals(0.0, side.x, 5e-3)
        }
    }

    @Test
    fun `refuses when the car moves or too little data came`() {
        val moving = still(0.0, Vec3(0.0, 0.0, 0.0), noise = 0.2)
        assertEquals(
            ImuCalibration.Result.Failed(ImuCalibration.Failure.Moving),
            ImuCalibration.from(moving, 1),
        )
        assertEquals(
            ImuCalibration.Result.Failed(ImuCalibration.Failure.TooFewSamples),
            ImuCalibration.from(still(0.0, Vec3(0.0, 0.0, 0.0), n = 10), 1),
        )
    }

    @Test
    fun `round-trips through its text form`() {
        val c = ok(ImuCalibration.from(still(5.0, Vec3(0.2, 0.1, -0.3)), 1_791_733_327_000))
        val back = ImuCalibration.decode(c.encode())
        assertNotNull(back)
        assertEquals(c.atMillis, back!!.atMillis)
        assertEquals(c.rollDeg, back.rollDeg, 1e-5)
        assertTrue(c.level.zip(back.level).all { (a, b) -> kotlin.math.abs(a - b) < 1e-5 })
        assertEquals(null, ImuCalibration.decode("nonsense"))
    }
}

private fun radians(deg: Double) = deg * kotlin.math.PI / 180.0
