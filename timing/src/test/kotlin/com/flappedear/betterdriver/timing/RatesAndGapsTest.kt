@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.LiveTiming
import com.flappedear.betterdriver.timing.live.LiveTimingState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live timing (FBBD-58 to FBBD-60) end to end at the receiver rates in use, 1, 10 and 25 Hz, and
 * across GNSS gaps (FBBD-166): lap times, sectors and the predictive delta the track screen shows.
 */
class RatesAndGapsTest {
    private val circuit = FakeCircuit()

    // Lap 1 is the short out lap, then laps 2 to 4 at these paces.
    private val paces = listOf(1.0, 1.05, 0.97)

    private fun replay(fixes: List<Fix>): Pair<LiveTiming, List<LiveTimingState>> {
        val live = LiveTiming(circuit.track())
        return live to fixes.map { live.update(it) }
    }

    /** Laps 2 to 4 are timed and within [toleranceMicros] of the truth. */
    private fun assertLaps(live: LiveTiming, drive: FakeCircuit.Drive, toleranceMicros: Double) {
        val passes = drive.lineTimesMicros.getValue(0.0)
        val laps = live.book.laps
        assertEquals(4, laps.size)
        assertEquals(LapKind.Out, laps[0].kind)
        for (n in 2..4) {
            val lap = laps[n - 1]
            assertEquals(LapKind.Timed, lap.kind)
            val truth = passes[n - 1] - passes[n - 2]
            assertEquals(truth, lap.timeMicros.toDouble(), toleranceMicros, "lap $n")
        }
    }

    @Test
    fun `laps, sectors and delta at 1, 10 and 25 Hz`() {
        // Linear interpolation between fixes: the error grows with the gap between fixes.
        for ((hz, tolerance) in listOf(25 to 5_000.0, 10 to 20_000.0, 1 to 250_000.0)) {
            val drive = circuit.drive(paces, rateHz = hz)
            val (live, states) = replay(drive.fixes)
            assertLaps(live, drive, tolerance)
            live.book.laps.drop(1).forEach { assertTrue(it.allSectors, "$hz Hz: every sector timed") }
            val end = states.last()
            assertEquals(5, end.lapNumber, "$hz Hz")
            // From lap 3 on there is a best lap, so a delta on every fix of the lap; never NaN.
            val passes = drive.lineTimesMicros.getValue(0.0)
            val onLap3 = drive.fixes.indices.filter { drive.fixes[it].tMicros > passes[1] + 2_000_000 }
            onLap3.forEach { i ->
                val d = assertNotNull(states[i].deltaSeconds, "$hz Hz delta at fix $i")
                assertTrue(d.isFinite() && abs(d) < 5.0, "$hz Hz delta $d")
            }
        }
    }

    @Test
    fun `a gap across the start-finish line still ends the lap at the right time`() {
        val drive = circuit.drive(paces)
        val passes = drive.lineTimesMicros.getValue(0.0)
        // No fix for 2 s around the end of lap 3, on the straight.
        val gap = (passes[2] - 1_000_000)..(passes[2] + 1_000_000)
        val fixes = drive.fixes.filterNot { it.tMicros.toDouble() in gap }
        val (live, states) = replay(fixes)
        // A straight-line guess across 2 s of a straight at changing speed: within 50 ms.
        assertLaps(live, drive, 50_000.0)
        // The delta is back on the first fix after the gap.
        val after = fixes.indexOfFirst { it.tMicros > gap.endInclusive }
        assertNotNull(states[after].deltaSeconds)
    }

    @Test
    fun `a gap across a sector line leaves those sectors untimed and the lap timed`() {
        val drive = circuit.drive(paces)
        val thirds = drive.lineTimesMicros.getValue(1.0 / 3)
        // Lap 3's first sector line falls in a 6 s gap on the bend: the chord misses the line.
        val gap = (thirds[1] - 3_000_000)..(thirds[1] + 3_000_000)
        val fixes = drive.fixes.filterNot { it.tMicros.toDouble() in gap }
        val (live, states) = replay(fixes)
        assertLaps(live, drive, 5_000.0)
        val lap3 = live.book.laps[2]
        assertNull(lap3.sectorMicros[0])
        assertNull(lap3.sectorMicros[1])
        assertNotNull(lap3.sectorMicros[2])
        // The best sectors and the theoretical best come from the other laps; nothing shows NaN.
        assertNotNull(live.book.theoreticalBestMicros)
        states.flatMap { it.sectors }.forEach { s ->
            s.deltaVsBestLapSeconds?.let { assertTrue(it.isFinite()) }
            s.deltaVsBestSectorSeconds?.let { assertTrue(it.isFinite()) }
        }
    }

    @Test
    fun `a long gap and a receiver reporting the same time twice change nothing`() {
        val drive = circuit.drive(paces)
        val passes = drive.lineTimesMicros.getValue(0.0)
        // 20 s with no fix in the middle of lap 3 (half a lap), and a repeated fix.
        val gap = (passes[1] + 10_000_000)..(passes[1] + 30_000_000)
        val fixes = drive.fixes.filterNot { it.tMicros.toDouble() in gap }.flatMap { listOf(it, it) }
        val (live, states) = replay(fixes)
        assertLaps(live, drive, 5_000.0)
        states.forEach { s -> s.deltaSeconds?.let { assertTrue(it.isFinite()) } }
    }
}
