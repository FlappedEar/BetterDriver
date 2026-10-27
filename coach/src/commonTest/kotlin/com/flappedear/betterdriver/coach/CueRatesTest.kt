@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The coach at the receiver rates in use (FBBD-166): 25 Hz RaceBox, 10 Hz receivers and a 1 Hz phone
 * GNSS, with and without the receiver's speed. A cue is never heard inside a braking zone.
 */
class CueRatesTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()
    private val early2 = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0))
    private val paces = listOf(early2, early2, early2, early2, steady, steady)

    private fun reference(): CoachReference {
        val laps = tracedLaps(track, sim.drive(List(3) { steady }).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    private fun cues(engine: CueEngine, drive: SimCircuit.Drive): List<Cue> =
        drive.fixes.flatMap { engine.update(it).cues }

    /** Distance driven at session time [t], interpolated between fixes. */
    private fun distanceAt(drive: SimCircuit.Drive, t: Long): Double {
        val i = drive.fixes.indexOfLast { it.tMicros <= t }.coerceAtLeast(0)
        val j = minOf(i + 1, drive.fixes.size - 1)
        val t0 = drive.fixes[i].tMicros
        val t1 = drive.fixes[j].tMicros
        val u = if (t1 == t0) 0.0 else (t - t0).toDouble() / (t1 - t0)
        return drive.distance[i] + u * (drive.distance[j] - drive.distance[i])
    }

    private fun assertOutsideBrakingZones(drive: SimCircuit.Drive, cues: List<Cue>, hz: Int) {
        for (cue in cues) {
            val start = cue.tMicros + DefaultLatencyMicros
            val end = start + Phrases.durationMicros(cue.text)
            for (k in 0..10) {
                val d = distanceAt(drive, start + (end - start) * k / 10)
                val lap = (d / sim.length).toInt().coerceIn(0, paces.size - 1)
                val inLap = ((d % sim.length) + sim.length) % sim.length
                sim.apexes.indices.forEach { c ->
                    val zone = sim.brakePoint(c, paces[lap])..sim.apexes[c]
                    assertFalse(inLap in zone, "$hz Hz: '${cue.text}' heard at $inLap m, in corner ${c + 1}'s zone")
                }
            }
        }
    }

    @Test
    fun `the corner cue and the lap times come at 25 and 10 Hz`() {
        for (hz in listOf(25, 10)) {
            val drive = sim.drive(paces, rateHz = hz)
            val cues = cues(CueEngine(track, CoachSettings(Verbosity.Focus), reference()), drive)
            val corner = cues.filter { it.kind == CueKind.Corner }
            assertEquals(listOf(2), corner.map { it.corner }, "$hz Hz: ${cues.map { it.text }}")
            assertTrue(cues.count { it.kind == CueKind.LapTime } >= 4, "$hz Hz: ${cues.map { it.text }}")
            assertOutsideBrakingZones(drive, cues, hz)
        }
    }

    @Test
    fun `at 1 Hz cues are still timed outside the braking zones`() {
        val drive = sim.drive(paces, rateHz = 1)
        val engine = CueEngine(track, CoachSettings(Verbosity.Full), reference())
        val cues = cues(engine, drive)
        assertTrue(cues.any { it.kind == CueKind.LapTime }, "cues: ${cues.map { it.text }}")
        assertOutsideBrakingZones(drive, cues, 1)
    }

    @Test
    fun `a receiver without speed gets it from the positions`() {
        for (hz in listOf(25, 10)) {
            val drive = sim.drive(paces, rateHz = hz)
            val noSpeed = SimCircuit.Drive(drive.fixes.map { it.copy(speedKmh = null) }, drive.distance)
            val cues = cues(CueEngine(track, CoachSettings(Verbosity.Focus), reference()), noSpeed)
            assertEquals(listOf(2), cues.filter { it.kind == CueKind.Corner }.map { it.corner }, "$hz Hz")
            assertOutsideBrakingZones(noSpeed, cues, hz)
        }
    }

    @Test
    fun `a GNSS gap on a lap leaves the engine coaching`() {
        val drive = sim.drive(paces)
        // 3 s with no fix in the middle of the third lap.
        val t0 = drive.fixes.first { it.tMicros > 0 }.tMicros
        val lapMicros = drive.fixes.last().tMicros / paces.size
        val gap = (t0 + 2 * lapMicros + lapMicros / 2).let { it..(it + 3_000_000) }
        val kept = drive.fixes.indices.filter { drive.fixes[it].tMicros !in gap }
        val gapped = SimCircuit.Drive(kept.map { drive.fixes[it] }, kept.map { drive.distance[it] })
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference())
        val cues = cues(engine, gapped)
        assertEquals(CoachPhase.Coaching, engine.phase)
        assertEquals(listOf(2), cues.filter { it.kind == CueKind.Corner }.map { it.corner })
        assertOutsideBrakingZones(gapped, cues, 25)
    }
}
