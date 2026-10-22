@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.GnssContinuity
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.LiveTiming
import com.flappedear.betterdriver.timing.live.gnssGapThresholdSeconds
import com.flappedear.betterdriver.timing.replay.replayLaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Laps timed across a GNSS gap (audit A11) and fixes the timer ignores (audit A12). A line crossed
 * in a gap is still counted, so the laps go on, but a lap whose time leans on fixes that never came
 * is never the best lap or the delta's reference; and a fix older than the last one changes nothing.
 */
class GnssGapTimingTest {
    private val circuit = FakeCircuit()
    private val track = circuit.track()

    // Lap 1 is the short out lap, then laps 2 to 4; lap 3 is the fastest.
    private val paces = listOf(1.0, 1.05, 0.97)

    private fun live(fixes: List<Fix>) = LiveTiming(track).also { l -> fixes.forEach { l.update(it) } }

    private fun session(fixes: List<Fix>) = fixes.map {
        val position = listOf(ChannelValue(GnssChannels.LAT.id, it.lat), ChannelValue(GnssChannels.LON.id, it.lon))
        SampleSet(it.tMicros, position)
    }

    @Test
    fun `25 Hz with 2 s missing across the line, both laps counted, neither the best, live and replayed`() {
        val drive = circuit.drive(paces)
        val passes = drive.lineTimesMicros.getValue(0.0)
        // Lap 3, the fastest, ends in the hole.
        val hole = (passes[2] - 1_000_000)..(passes[2] + 1_000_000)
        val fixes = drive.fixes.filterNot { it.tMicros.toDouble() in hole }
        val timing = live(fixes)
        val laps = timing.book.laps
        assertEquals(listOf(LapKind.Out, LapKind.Timed, LapKind.Timed, LapKind.Timed), laps.map { it.kind })
        assertEquals(listOf(false, false, true, true), laps.map { it.gnssGap })
        // The interpolated line in the hole ends both lap 3 and lap 4, so neither has its last or first sector.
        assertNull(laps[2].sectorMicros.last())
        assertNull(laps[3].sectorMicros.first())
        // Lap 2 stays the best, and the screen's best time with it.
        assertEquals(2, assertNotNull(timing.book.best).number)
        assertEquals(laps[1].timeMillis, timing.state.bestMillis)

        val replayed = replayLaps("s", session(fixes), track)
        assertEquals(laps, replayed.laps.map { it.lap })
        assertEquals(2, assertNotNull(replayed.best).lap.number)
    }

    @Test
    fun `without the hole lap 3 is the best`() {
        val timing = live(circuit.drive(paces).fixes)
        assertTrue(timing.book.laps.none { it.gnssGap })
        assertEquals(3, assertNotNull(timing.book.best).number)
    }

    @Test
    fun `the phone's 1 Hz GPS times laps, also with a fix missing at the line`() {
        val drive = circuit.drive(paces, rateHz = 1)
        val passes = drive.lineTimesMicros.getValue(0.0)
        // The fix just after lap 3's line does not come: a 2 s step, under the 3 s a 1 Hz receiver allows.
        val missing = drive.fixes.first { it.tMicros > passes[2] }
        val timing = live(drive.fixes - missing)
        val laps = timing.book.laps
        assertEquals(4, laps.size)
        assertTrue(laps.none { it.gnssGap })
        assertEquals(3, assertNotNull(timing.book.best).number)
    }

    @Test
    fun `an older or repeated fix changes nothing`() {
        val drive = circuit.drive(paces)
        val passes = drive.lineTimesMicros.getValue(0.0)
        val reference = LiveTiming(track)
        val withStale = LiveTiming(track)
        // Part way round lap 3, with a best lap (lap 2) to run the delta against.
        val at = drive.fixes.indexOfFirst { it.tMicros > passes[1] + 30_000_000 }
        for ((i, fix) in drive.fixes.withIndex()) {
            reference.update(fix)
            val before = withStale.update(fix)
            if (i == at) {
                // A fix from 1 s ago, then the same fix again: both are ignored, the state stays put.
                val stale = drive.fixes[i - 25]
                assertEquals(before, withStale.update(stale))
                assertEquals(before, withStale.update(fix))
            }
        }
        assertEquals(reference.state, withStale.state)
        assertEquals(reference.book.laps, withStale.book.laps)
        // The next lap's reference path was not bent back by the stale fix: the delta on lap 4 is the same.
        val lap4 = drive.fixes.filter { it.tMicros > passes[2] + 1_000_000 && it.tMicros < passes[3] }
        val a = LiveTiming(track)
        val b = LiveTiming(track)
        val deltas = drive.fixes.withIndex().mapNotNull { (i, fix) ->
            val sa = a.update(fix)
            val sb = b.update(fix)
            if (i == at) b.update(drive.fixes[i - 25])
            if (fix in lap4) sa.deltaSeconds to sb.deltaSeconds else null
        }
        assertTrue(deltas.isNotEmpty())
        deltas.forEach { (x, y) -> assertEquals(x, y) }
    }

    @Test
    fun `the gap rule follows the receiver's rate`() {
        assertEquals(0.5, gnssGapThresholdSeconds(0.04), 1e-12)
        assertEquals(0.5, gnssGapThresholdSeconds(0.1), 1e-12)
        assertEquals(3.0, gnssGapThresholdSeconds(1.0), 1e-12)
        assertEquals(0.0, gnssGapThresholdSeconds(0.0), 0.0)
        val c = GnssContinuity()
        assertFalse(c.isGap(5_000_000), "the first step has nothing to go by")
        val phone = GnssContinuity()
        repeat(10) { assertFalse(phone.isGap(1_000_000)) }
        assertFalse(phone.isGap(2_000_000))
        assertTrue(phone.isGap(3_500_000))
        val raceBox = GnssContinuity()
        repeat(10) { assertFalse(raceBox.isGap(40_000)) }
        assertFalse(raceBox.isGap(400_000), "a few missed fixes")
        assertTrue(raceBox.isGap(2_000_000))
        // One long step does not move the median: the next one is judged the same way.
        assertTrue(raceBox.isGap(600_000))
    }
}
