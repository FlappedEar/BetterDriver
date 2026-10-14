package com.flappedear.betterdriver.recorder.accel

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-226: a RaceBox's and the phone's accelerometers averaged into one reading of the car's G. */
class AccelFusionTest {
    /** Braking and cornering over two minutes, in g. */
    private fun car(t: Double) = 0.6 * sin(t * 0.9) + 0.3 * sin(t * 2.3 + 0.5)

    /**
     * An accelerometer's reading of [car] at 25 Hz, stamped [lateSeconds] late, with
     * white noise of [noise] g (standard deviation), and no samples where [dropped] says.
     */
    private fun reading(
        name: String,
        noise: Double,
        lateSeconds: Double = 0.0,
        seed: Int = 1,
        dropped: (Int) -> Boolean = { false },
    ): DirectionReading {
        val random = Random(seed)
        val kept = (0 until 3_000).filterNot(dropped)
        val times = DoubleArray(kept.size) { kept[it] * 0.04 + lateSeconds }
        val values = DoubleArray(kept.size) { car(kept[it] * 0.04) + noise * gaussian(random) }
        return DirectionReading(name, times, values, noise * noise)
    }

    private fun gaussian(random: Random): Double {
        // Sum of 12 uniforms: mean 6, variance 1.
        var s = 0.0
        repeat(12) { s += random.nextDouble() }
        return s - 6.0
    }

    /** Root mean square error of [fused] against [car] where it has a value. */
    private fun rms(fused: FusedDirection): Double {
        var sum = 0.0
        var n = 0
        for (i in fused.times.indices) if (fused.values[i].isFinite()) {
            val e = fused.values[i] - car(fused.times[i])
            sum += e * e
            n++
        }
        return sqrt(sum / n)
    }

    @Test
    fun `one accelerometer is returned as it is`() {
        val box = reading("accel_x", 0.05)
        val fused = assertNotNull(fuseDirection(listOf(box)))
        assertTrue(box.times.contentEquals(fused.times))
        assertTrue(box.values.contentEquals(fused.values))
        assertEquals(1.0, fused.shares.single().weight)
        assertNull(fuseDirection(emptyList()))
    }

    @Test
    fun `two accelerometers averaged read closer to the car than either alone`() {
        val box = reading("accel_x", 0.05, seed = 1)
        val phone = reading("phone_accel_y", 0.05, seed = 2)
        val fused = assertNotNull(fuseDirection(listOf(box, phone)))
        assertEquals(listOf(0.5, 0.5), fused.shares.map { it.weight })
        val alone = rms(assertNotNull(fuseDirection(listOf(box))))
        // Two equal, independent errors averaged: √2 smaller.
        assertTrue(rms(fused) < alone * 0.8, "fused ${rms(fused)} g, RaceBox alone $alone g")
    }

    @Test
    fun `the steadier accelerometer counts for more`() {
        val box = reading("accel_x", 0.03, seed = 1)
        val phone = reading("phone_accel_x", 0.09, seed = 2)
        val fused = assertNotNull(fuseDirection(listOf(phone, box)))
        assertEquals("accel_x", fused.shares.first().name, "the best one leads")
        // Inverse variances 1/0.0009 and 1/0.0081: 0.9 and 0.1.
        assertEquals(0.9, fused.shares[0].weight, 1e-9)
        assertEquals(0.1, fused.shares[1].weight, 1e-9)
        assertTrue(rms(fused) <= rms(assertNotNull(fuseDirection(listOf(box)))) + 1e-3)
    }

    /** A phone's sensor and a RaceBox over BLE are stamped apart; averaged unmatched they would blunt each peak. */
    @Test
    fun `a late accelerometer's clock is matched to the best one's before averaging`() {
        val box = reading("accel_x", 0.02, seed = 1)
        for (late in listOf(0.12, -0.08)) {
            val phone = reading("phone_accel_y", 0.03, lateSeconds = late, seed = 2)
            val fused = assertNotNull(fuseDirection(listOf(box, phone)))
            assertEquals(late, fused.shares[1].lagSeconds, 0.011, "late $late s")
            assertTrue(rms(fused) < 0.025, "late $late s: ${rms(fused)} g")
        }
    }

    /** Codex on #175: a residual fitted at the unmatched clock takes a late sensor for a noisy one. */
    @Test
    fun `a late but steadier accelerometer is weighed at its matched clock against GPS`() {
        val box = reading("accel_x", 0.05, seed = 1)
        val late = reading("phone_accel_y", 0.02, lateSeconds = 0.15, seed = 2)
        // Its residual from the fit at the unmatched clock: the 0.15 s offset reads as noise.
        val asFitted = DirectionReading(late.name, late.times, late.values, 0.03)
        val gpsTimes = DoubleArray(1_200) { it * 0.1 }
        val gps = DirectionReading("GPS", gpsTimes, DoubleArray(gpsTimes.size) { car(gpsTimes[it]) }, Double.NaN)
        val fused = assertNotNull(fuseDirection(listOf(box, asFitted), gps))
        assertEquals("phone_accel_y", fused.shares.first().name, "the steadier one leads")
        assertTrue(fused.shares.first().weight > 0.7, "weight ${fused.shares.first().weight}")
        assertEquals(0.15, fused.shares.first().lagSeconds, 0.011, "the phone is late against the RaceBox")
        assertEquals(0.0, fused.shares[1].lagSeconds, "the RaceBox's clock is kept")
        // Codex on #175: the braking stays where the car did it, not 0.15 s late with the phone.
        assertTrue(rms(fused) < 0.03, "${rms(fused)} g")
        val unweighed = assertNotNull(fuseDirection(listOf(box, asFitted)))
        assertEquals("accel_x", unweighed.shares.first().name, "without GPS the fitted residuals rank")
    }

    @Test
    fun `where one accelerometer has a gap the other carries on`() {
        // The RaceBox's whole-second skip, and a lost link for 4 s.
        val box = reading("accel_x", 0.02, seed = 1) { it % 25 == 0 || it in 1_000 until 1_100 }
        val phone = reading("phone_accel_y", 0.02, seed = 2)
        val fused = assertNotNull(fuseDirection(listOf(box, phone)))
        val inGap = fused.times.indices.filter { fused.times[it] in 41.0..43.0 }
        assertTrue(inGap.isNotEmpty())
        for (i in inGap) assertTrue(abs(fused.values[i] - car(fused.times[i])) < 0.1, "at ${fused.times[i]} s")
        assertTrue(fused.values.all { it.isFinite() }, "no hole anywhere")
    }

    @Test
    fun `an accelerometer that does not agree with the best one is left out`() {
        val box = reading("accel_x", 0.02, seed = 1)
        // A phone loose in its holder: a reading mostly of its own.
        val random = Random(3)
        val loose = DirectionReading(
            "phone_accel_x",
            box.times,
            DoubleArray(box.times.size) { i ->
                val t = box.times[i]
                0.2 * car(t) + 0.5 * sin(t * 5.1) + 0.1 * gaussian(random)
            },
            0.2,
        )
        val fused = assertNotNull(fuseDirection(listOf(box, loose)))
        assertEquals(listOf("phone_accel_x"), fused.left)
        assertEquals(listOf("accel_x"), fused.shares.map { it.name })
        assertTrue(box.values.contentEquals(fused.values))
    }

    @Test
    fun `the fit's residual is what is left between the corrected axis and GPS`() {
        val random = Random(5)
        val gps = DoubleArray(2_000) { car(it * 0.04) }
        val noise = DoubleArray(gps.size) { 0.05 * gaussian(random) }
        // Mounted backwards and tilted.
        val axis = DoubleArray(gps.size) { -gps[it] + noise[it] - 0.19 }
        val fit = assertNotNull(fitAxis(axis, gps))
        val expected = noise.map { e -> e - noise.average() }.sumOf { it * it } / noise.size
        assertEquals(expected, fit.residual, 1e-9)
    }
}
