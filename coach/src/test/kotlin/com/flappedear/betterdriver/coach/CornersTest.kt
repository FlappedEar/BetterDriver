@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.live.LapKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CornersTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()

    @Test
    fun `finds every braking corner with its braking point and minimum speed`() {
        val laps = tracedLaps(track, sim.drive(List(3) { steady }).fixes.asSequence())
        val lap = laps.first { it.lap.kind == LapKind.Timed }
        val corners = findCorners(lap.trace)
        assertEquals(4, corners.size, "corners: $corners")
        corners.forEachIndexed { k, c ->
            val brake = sim.brakePoint(k, steady)
            assertTrue(abs(c.brakeAt - brake) <= 10.0, "corner ${k + 1} braking at ${c.brakeAt}, true $brake")
            assertTrue(abs(c.minKmh - sim.minKmh[k]) <= 2.0, "corner ${k + 1} min ${c.minKmh}, true ${sim.minKmh[k]}")
            assertTrue(c.apexAt in sim.apexes[k] - 40..sim.apexes[k] + 5, "corner ${k + 1} apex ${c.apexAt}")
            assertEquals(Turn.Left, c.turn)
        }
    }

    @Test
    fun `a pass measures braking earlier and a slower apex`() {
        val laps = tracedLaps(track, sim.drive(List(3) { steady }).fixes.asSequence())
        val ref = laps.first { it.lap.kind == LapKind.Timed }
        val map = CornerMap(ref.trace, findCorners(ref.trace))
        val slow = SimCircuit.Pace(earlierM = listOf(0.0, 20.0, 0.0, 0.0), kmh = listOf(0.0, 0.0, -6.0, 0.0))
        val other = tracedLaps(track, sim.drive(listOf(steady, slow, steady)).fixes.asSequence())
            .first { it.lap.number == 3 }
        val base = map.passes(ref.trace, 1).associateBy { it.corner }
        val passes = map.passes(other.trace, 3).associateBy { it.corner }
        assertEquals(4, passes.size)
        val c2 = assertNotNull(passes[2])
        val earlier = base.getValue(2).brakeAt - c2.brakeAt
        assertTrue(earlier in 8.0..20.0, "corner 2 braked $earlier m earlier")
        assertTrue(c2.timeMicros > base.getValue(2).timeMicros + 50_000)
        val c3 = assertNotNull(passes[3])
        assertTrue(abs(c3.minKmh - (sim.minKmh[2] - 6)) <= 2.0, "corner 3 min ${c3.minKmh}")
        assertTrue(abs(passes.getValue(1).timeMicros - base.getValue(1).timeMicros) < 20_000)
    }

    @Test
    fun `the reference takes the fastest lap and the fastest pass of each corner`() {
        val good = SimCircuit.Pace(kmh = listOf(4.0, 0.0, 0.0, 0.0))
        val firstSession = tracedLaps(track, sim.drive(listOf(steady, good, steady)).fixes.asSequence())
        val secondSession = tracedLaps(
            track,
            sim.drive(listOf(steady, SimCircuit.Pace(kmh = listOf(0.0, 0.0, 0.0, 5.0)))).fixes.asSequence(),
        )
        val sessions = listOf(SessionLaps("a", firstSession), SessionLaps("b", secondSession))
        val ref = assertNotNull(buildReference(track, sessions))
        assertEquals(4, ref.corners.size)
        // The two fastest passes of each corner, fastest first (FBBD-177).
        assertEquals(8, ref.targets.size)
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4), ref.targets.map { it.corner })
        assertTrue(ref.targets.zipWithNext().all { (a, b) -> a.corner != b.corner || a.timeMicros <= b.timeMicros })
        assertEquals(listOf("a", "b"), ref.sessions)
        // Corner 1 was fastest on session a's lap 3, corner 4 on session b's lap 3.
        assertTrue(ref.targets[0].minKmh > sim.minKmh[0] + 2)
        assertTrue(ref.targets[6].minKmh > sim.minKmh[3] + 2)
        val again = ref.map()
        assertEquals(ref.corners, again.corners)
    }
}
