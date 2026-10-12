@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
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

    /**
     * A first lap as at Silesia Ring (FBBD-202): it rolls to a stop past the line and waits, gets
     * going, brakes hard for a corner, then lifts on the next straight without braking.
     */
    private fun stopThenCorner(): LapTrace = drive(
        8.3, // 30 km/h at the line
        14.0 to -0.65, // rolling to a stop
        60.0 to 0.0, // waiting
        10.0 to 3.0, // to 108 km/h
        1.5 to -8.0, // braking to 65 km/h
        6.0 to 3.0,
        5.0 to -1.0, // a lift: 18 km/h slower, at 0.1 g
        5.0 to 2.0,
    )

    @Test
    fun `a stop past the line and a lift are not corners, the braking is (FBBD-202)`() {
        val trace = stopThenCorner()
        val corners = findCorners(trace)
        assertEquals(1, corners.size, "corners: $corners")
        val c = corners.single()
        val stopAt = trace.along[trace.speedsKmh.indexOfFirst { it == 0.0 }]
        val brakeAt = stopAt + 0.5 * 3.0 * 10.0 * 10.0
        assertTrue(abs(c.brakeAt - brakeAt) <= 6.0, "braking at ${c.brakeAt}, true $brakeAt")
        assertTrue(abs(c.minKmh - 64.8) <= 2.0, "min ${c.minKmh}")
    }

    @Test
    fun `a pass entered slowly is measured at the corner, and a pass with a stop is no pass (FBBD-202)`() {
        val trace = stopThenCorner()
        val map = CornerMap(trace, findCorners(trace))
        // The window starts while the car is still getting going, slower than at the apex.
        val pass = assertNotNull(map.passes(trace, 2).singleOrNull())
        assertEquals(map.corners.single().apexAt, pass.apexAt, 4.0)
        assertEquals(map.corners.single().brakeAt, pass.brakeAt, 4.0)
        // A lap that stops inside the window says nothing about the corner.
        val stopped = drive(30.0, 6.0 to -5.0, 20.0 to 0.0, 15.0 to 2.0)
        val stoppedMap = CornerMap(stopped, listOf(map.corners.single().copy(brakeAt = 100.0, apexAt = 130.0)))
        assertEquals(emptyList(), stoppedMap.passes(stopped, 2))
    }

    private companion object {
        /** A straight drive at 10 Hz from [startMps], through phases of (seconds, m/s²); speed never below 0. */
        fun drive(startMps: Double, vararg phases: Pair<Double, Double>): LapTrace {
            val points = mutableListOf(Xy(0.0, 0.0))
            val times = mutableListOf(0L)
            val speeds = mutableListOf(startMps * 3.6)
            var s = 0.0
            var v = startMps
            var t = 0L
            for ((seconds, accel) in phases) {
                repeat((seconds * 10).toInt()) {
                    v = maxOf(0.0, v + accel * 0.1)
                    s += v * 0.1
                    t += 100_000L
                    points += Xy(s, 0.0)
                    times += t
                    speeds += v * 3.6
                }
            }
            return LapTrace(points, times, speeds)
        }
    }
}
