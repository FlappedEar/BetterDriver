@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.steering.SteerAdvice
import com.flappedear.betterdriver.timing.steering.SteerSample
import com.flappedear.betterdriver.timing.steering.SteeringPass
import com.flappedear.betterdriver.timing.steering.bends
import com.flappedear.betterdriver.timing.steering.improvedSteering
import com.flappedear.betterdriver.timing.steering.steeringAdvice
import com.flappedear.betterdriver.timing.steering.steeringPass
import com.flappedear.betterdriver.timing.steering.wheelForBend
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the hands did through a corner (FBBD-321), and what to do differently against the target. */
class SteeringTest {
    /**
     * 200 m at 1 m steps: straight to 40 m, wheel wound on to [peak] by 70 m, held to [unwind], back to
     * straight 30 m later; [extra] adds to the wheel at each metre. Positive is left.
     */
    private fun pass(
        peak: Double = 90.0,
        unwind: Int = 110,
        left: Boolean = true,
        bend: ((Int) -> Double)? = null,
        extra: (Int) -> Double = { 0.0 },
    ): SteeringPass? {
        val side = if (left) 1.0 else -1.0
        val samples = (0..200).map { m ->
            val w = when {
                m < 40 -> 0.0
                m < 70 -> peak * (m - 40) / 30.0
                m <= unwind -> peak
                m < unwind + 30 -> peak * (unwind + 30 - m) / 30.0
                else -> 0.0
            }
            SteerSample(m.toDouble(), side * (w + extra(m)), bend?.invoke(m)?.times(side))
        }
        return steeringPass(samples, left)
    }

    @Test
    fun `one smooth movement - turn-in, peak, where the wheel opens, no corrections`() {
        val p = assertNotNull(pass())
        assertEquals(90.0, p.peakDeg, 1e-9)
        assertEquals(49.0, p.turnInAt, 1.0) // 30 % of the peak
        assertEquals(114.0, assertNotNull(p.unwindAt), 0.5) // 15 % off full lock on the way out
        assertEquals(0, p.corrections)
        assertFalse(p.countersteer)
        assertNull(p.pushDeg)
        // A right-hander reads the same, the wheel's sign turned round.
        assertEquals(p, pass(left = false))
        // A kink is not a corner to steer.
        assertNull(pass(peak = 10.0))
    }

    @Test
    fun `lock eased and added again is a correction, the wheel the wrong way before it opens is countersteer`() {
        val sawing = assertNotNull(pass(extra = { m -> if (m in 80..85 || m in 95..100) -15.0 else 0.0 }))
        assertEquals(2, sawing.corrections)
        // Wobbles smaller than CorrectionDeg are the hand, not a correction.
        assertEquals(0, assertNotNull(pass(extra = { m -> if (m in 80..85) -5.0 else 0.0 })).corrections)
        val caught = assertNotNull(pass(extra = { m -> if (m in 100..104) -100.0 else 0.0 }))
        assertTrue(caught.countersteer)
    }

    @Test
    fun `push is the wheel beyond what the path's bend needs`() {
        // A 40 m radius needs about 48° of wheel on the FR-S (13:1, 2.57 m).
        assertEquals(47.9, wheelForBend(1 / 40.0), 0.1)
        val p = assertNotNull(pass(bend = { 1 / 40.0 }))
        // Near full lock, the ramps' ends included: a little under 90° less the bend's need.
        assertEquals(90.0 - wheelForBend(1 / 40.0), assertNotNull(p.pushDeg), 2.0)
        assertEquals(p.pushDeg, pass(left = false, bend = { 1 / 40.0 })?.pushDeg)
    }

    @Test
    fun `the bend of a circle is one over its radius, positive turning left`() {
        val r = 50.0
        val points = (0..100).map { i -> Xy(r * sin(i / r), r - r * cos(i / r)) }
        val along = DoubleArray(points.size) { it * 2 * r * sin(0.5 / r) }
        val b = bends(points, along)
        assertNull(b.first())
        assertNull(b.last())
        assertEquals(1 / r, assertNotNull(b[50]), 1e-4)
        val mirrored = bends(points.map { Xy(it.x, -it.y) }, along)
        assertEquals(-1 / r, assertNotNull(mirrored[50]), 1e-4)
    }

    @Test
    fun `steer more when wider with less wheel, less when the front slid`() {
        val target = assertNotNull(pass(bend = { 1 / 40.0 }))
        // Wider by 3 m with 20° less wheel: the hands made it wide.
        val shy = assertNotNull(pass(peak = 70.0, bend = { 1 / 40.0 }))
        assertEquals(listOf<SteerAdvice>(SteerAdvice.More), steeringAdvice(shy, target, tighterMeters = 3.0))
        // On the same line, less wheel is nothing to fix.
        assertEquals(emptyList(), steeringAdvice(shy, target, tighterMeters = 0.5))
        // Wider with 30° more wheel on a wider bend: the front tyres slid; more wheel would not help.
        val pushed = assertNotNull(pass(peak = 120.0, bend = { 1 / 45.0 }))
        assertEquals(listOf<SteerAdvice>(SteerAdvice.Less), steeringAdvice(pushed, target, tighterMeters = 3.0))
        assertTrue(improvedSteering(SteerAdvice.Less, pushed, target))
        assertFalse(improvedSteering(SteerAdvice.Less, pushed, pushed))
        // Within what the GNSS bend repeats, no push.
        assertEquals(emptyList(), steeringAdvice(assertNotNull(pass(peak = 98.0, bend = { 1 / 40.0 })), target, 0.0))
    }

    @Test
    fun `open the wheel earlier, smoother, and oversteer the target did not have`() {
        val target = assertNotNull(pass(unwind = 100))
        val late = assertNotNull(pass(unwind = 112))
        assertEquals(listOf<SteerAdvice>(SteerAdvice.OpenEarlier(10)), steeringAdvice(late, target, null))
        assertEquals(emptyList(), steeringAdvice(assertNotNull(pass(unwind = 103)), target, null))
        assertEquals(
            listOf<SteerAdvice>(SteerAdvice.OpenEarlier(20)),
            steeringAdvice(assertNotNull(pass(unwind = 140)), target, null),
        )
        assertTrue(improvedSteering(SteerAdvice.OpenEarlier(10), late, assertNotNull(pass(unwind = 106))))
        assertFalse(improvedSteering(SteerAdvice.OpenEarlier(10), late, assertNotNull(pass(unwind = 110))))
        val sawing = assertNotNull(pass(unwind = 100, extra = { m -> if (m in 80..85 || m in 90..94) -15.0 else 0.0 }))
        assertEquals(listOf<SteerAdvice>(SteerAdvice.Smoother), steeringAdvice(sawing, target, null))
        val caught = assertNotNull(pass(unwind = 100, extra = { m -> if (m in 92..96) -100.0 else 0.0 }))
        assertTrue(SteerAdvice.Oversteer in steeringAdvice(caught, target, null))
        assertTrue(improvedSteering(SteerAdvice.Oversteer, caught, target))
    }
}
