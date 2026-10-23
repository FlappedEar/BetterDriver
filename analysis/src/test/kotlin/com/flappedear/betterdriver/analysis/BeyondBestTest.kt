@file:Suppress("MagicNumber") // Speeds, decelerations and distances of made-up corners.

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.line.BrakeStepMeters
import com.flappedear.betterdriver.analysis.line.CornerPasses
import com.flappedear.betterdriver.analysis.line.LinePass
import com.flappedear.betterdriver.analysis.line.LinePoint
import com.flappedear.betterdriver.analysis.line.beyondBest
import com.flappedear.betterdriver.analysis.line.passDynamics
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeyondBestTest {
    /**
     * A straight line every 2 m: [entry] km/h until [brakeAt] m, braking at [brakeG] to [slowest] km/h,
     * then back up at 0.3 g. No turning, so all the acceleration is the braking's.
     */
    private fun pass(brakeG: Double, entry: Double = 100.0, slowest: Double = 50.0, brakeAt: Double = 100.0): LinePass {
        val g = 9.80665
        val ve = entry / 3.6
        val va = slowest / 3.6
        val apex = brakeAt + (ve * ve - va * va) / (2 * brakeG * g)
        val line = (0..150).map { i ->
            val s = i * 2.0
            val v = when {
                s <= brakeAt -> ve
                s <= apex -> sqrt(ve * ve - 2 * brakeG * g * (s - brakeAt))
                else -> minOf(ve, sqrt(va * va + 2 * 0.3 * g * (s - apex)))
            }
            LinePoint(s, s, 0.0, 0.0, v * 3.6, null)
        }
        return LinePass("", 1, 100.0, 10.0, brakeAt, apex, slowest, null, 0.0, line)
    }

    private fun corner(name: String, target: LinePass, others: List<LinePass> = emptyList(), gain: Double = 0.0) =
        CornerPasses(name, name, target, others, gain)

    @Test
    fun `a pass's braking is read from its speed`() {
        val d = assertNotNull(passDynamics(pass(0.4)))
        assertEquals(100.0, d.entryKmh, 0.5)
        assertEquals(50.0, d.minimumKmh, 0.5)
        assertEquals(0.4, d.peakBrakeG, 0.03)
        assertEquals(0.4, d.averageBrakeG, 0.03)
        assertTrue(d.brakes)
    }

    @Test
    fun `a corner braked at half the track's limit gets a braking point one step later, worth time`() {
        val beyond = assertNotNull(
            beyondBest(
                listOf(
                    corner("hard", pass(0.95)), corner("confirms", pass(0.9)),
                    corner("soft", pass(0.4), listOf(pass(0.45))),
                ),
            ),
        )
        assertEquals(0.9, beyond.limits.brakeG, 0.03)
        assertEquals(listOf("hard", "confirms"), beyond.limits.brakeCorners)
        val soft = beyond.corners.single { it.name == "soft" }
        val room = assertNotNull(soft.brake)
        assertEquals(BrakeStepMeters, room.laterMeters)
        assertTrue(room.fullLaterMeters > room.laterMeters, "full ${room.fullLaterMeters}")
        assertTrue(room.seconds > 0.0 && room.fullSeconds > room.seconds, "${room.seconds} ${room.fullSeconds}")
        // Braking at the limit already: nothing to move.
        assertNull(beyond.corners.single { it.name == "confirms" }.brake)
        assertEquals(listOf("soft"), beyond.ranked.map { it.name })
    }

    @Test
    fun `room only where every pass shows it and the driven target has nothing left`() {
        val beyond = assertNotNull(
            beyondBest(
                listOf(
                    corner("hard", pass(0.95)), corner("confirms", pass(0.9)),
                    // One of his passes braked near the limit here: not room on every lap.
                    corner("once", pass(0.4), listOf(pass(0.85))),
                    // His fastest lap still loses to his own target here: the driven target comes first.
                    corner("line", pass(0.4), gain = 0.2),
                ),
            ),
        )
        assertNull(beyond.corners.single { it.name == "once" }.brake)
        assertTrue(beyond.corners.none { it.name == "line" })
    }

    @Test
    fun `one braking corner is no limit`() {
        // A corner taken without braking (a lift of 5 km/h) sets no braking limit.
        assertNull(beyondBest(listOf(corner("hard", pass(0.95)), corner("lift", pass(0.4, slowest = 95.0)))))
    }
}
