@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FBBD-404: a corner after a straight too short for any cue (Silesia's Turns 6, 11 and 1) is cued on
 * the straight before the corner ahead of it, when that corner has nothing to say.
 */
class ShortStraightCueTest {
    /**
     * 150 m high: the straights from Corner 1 to 2 and from Corner 3 to 4 last under 2 s, shorter than
     * the shortest cue. Corner 4 is used: the straight before Corner 1 carries the lap time.
     */
    private val sim = SimCircuit(h = 150.0)
    private val track = sim.track()
    private val steady = SimCircuit.Pace()
    private val early4 = SimCircuit.Pace(earlierM = listOf(0.0, 0.0, 0.0, 15.0))

    private fun reference(): CoachReference {
        val laps = tracedLaps(track, sim.drive(List(3) { steady }).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    private fun distanceAt(drive: SimCircuit.Drive, t: Long): Double {
        val i = drive.fixes.indexOfLast { it.tMicros <= t }.coerceAtLeast(0)
        val j = minOf(i + 1, drive.fixes.size - 1)
        val t0 = drive.fixes[i].tMicros
        val t1 = drive.fixes[j].tMicros
        val u = if (t1 == t0) 0.0 else (t - t0).toDouble() / (t1 - t0)
        return drive.distance[i] + u * (drive.distance[j] - drive.distance[i])
    }

    /** No part of [cue] is heard in a braking zone; returns the corner it was heard before. */
    private fun heardBefore(drive: SimCircuit.Drive, cue: Cue, paces: List<SimCircuit.Pace>): Int {
        val start = cue.tMicros + DefaultLatencyMicros
        val end = start + Phrases.durationMicros(cue.text)
        val heard = (0..10).map { distanceAt(drive, start + (end - start) * it / 10) }
        for (d in heard) {
            val lap = (d / sim.length).toInt().coerceIn(0, paces.size - 1)
            val inLap = ((d % sim.length) + sim.length) % sim.length
            sim.apexes.indices.forEach { k ->
                val zone = sim.brakePoint(k, paces[lap])..sim.apexes[k]
                assertFalse(inLap in zone, "cue '${cue.text}' heard at $inLap m, inside corner ${k + 1}'s zone $zone")
            }
        }
        val at = ((heard.last() % sim.length) + sim.length) % sim.length
        return sim.apexes.indexOfFirst { it > at }.let { if (it < 0) 1 else it + 1 }
    }

    @Test
    fun `a corner after a too short straight is cued before the corner ahead of it`() {
        val paces = listOf(early4, early4, early4, early4, steady, steady)
        val drive = sim.drive(paces)
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference())
        val cues = mutableListOf<Cue>()
        val results = mutableListOf<CueResult>()
        drive.fixes.forEach { f ->
            val u = engine.update(f)
            cues += u.cues
            results += u.results
        }
        val corner = cues.filter { it.kind == CueKind.Corner }
        assertEquals(1, corner.size, "corner cues: ${corner.map { it.text }}")
        val cue = corner.single()
        assertEquals(4, cue.corner)
        assertTrue(cue.text.startsWith("Turn 4, "), cue.text)
        // Said on the straight before Corner 3, ending before its braking zone.
        assertEquals(3, heardBefore(drive, cue, paces))
        cues.forEach { heardBefore(drive, it, paces) }
        // Corner 4's next pass is measured against it, as for a cue on its own straight.
        assertEquals(cue.seq, results.single().cue.seq)
    }

    @Test
    fun `a corner cued early is not cued again on its own straight`() {
        val paces = List(8) { early4 }
        val engine = CueEngine(track, CoachSettings(Verbosity.Full), reference())
        val cues = sim.drive(paces).fixes.flatMap { engine.update(it).cues }.filter { it.kind == CueKind.Corner }
        // One cue per lap at most, and a lap to try it between two.
        val laps = cues.filter { it.corner == 4 }.map { it.lap }
        assertTrue(laps.isNotEmpty())
        assertEquals(laps.distinct(), laps, "corner 4 cues on laps $laps")
        assertTrue(laps.zipWithNext().all { (a, b) -> b - a >= 2 }, "corner 4 cues on laps $laps")
    }
}
