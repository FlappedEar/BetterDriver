@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CueEngineTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()
    private val early2 = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0))

    private fun reference(): CoachReference {
        val laps = tracedLaps(track, sim.drive(List(3) { steady }).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    private class Run(val cues: List<Cue>, val results: List<CueResult>)

    private fun run(engine: CueEngine, drive: SimCircuit.Drive): Run {
        val cues = mutableListOf<Cue>()
        val results = mutableListOf<CueResult>()
        drive.fixes.forEach { f ->
            val u = engine.update(f)
            cues += u.cues
            results += u.results
        }
        return Run(cues, results)
    }

    /** Distance driven at session time [t], interpolated between fixes. */
    private fun distanceAt(drive: SimCircuit.Drive, t: Long): Double {
        val i = drive.fixes.indexOfLast { it.tMicros <= t }.coerceAtLeast(0)
        val j = minOf(i + 1, drive.fixes.size - 1)
        val t0 = drive.fixes[i].tMicros
        val t1 = drive.fixes[j].tMicros
        val u = if (t1 == t0) 0.0 else (t - t0).toDouble() / (t1 - t0)
        return drive.distance[i] + u * (drive.distance[j] - drive.distance[i])
    }

    /**
     * Asserts no part of any cue is heard inside a braking zone, from the braking point of the lap
     * being driven to the apex. Returns the corner each cue was heard before.
     */
    private fun assertOutsideBrakingZones(
        drive: SimCircuit.Drive,
        cues: List<Cue>,
        paces: List<SimCircuit.Pace>,
        delayMillis: Int,
    ): List<Int> = cues.map { cue ->
        val start = cue.tMicros + DefaultLatencyMicros + delayMillis * 1_000L
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
        sim.apexes.indexOfFirst { it > at }.let { if (it < 0) 1 else it + 1 }
    }

    @Test
    fun `after the learning laps it asks for later braking at the corner braked early, once`() {
        val paces = listOf(early2, early2, early2, early2, steady, steady)
        val drive = sim.drive(paces)
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference())
        val run = run(engine, drive)
        val corner = run.cues.filter { it.kind == CueKind.Corner }
        assertEquals(1, corner.size, "corner cues: ${corner.map { it.text }}")
        val cue = corner.single()
        assertEquals(2, cue.corner)
        // Braking 15 m earlier into the same braking curve moves the braking point back by less than 15 m.
        val shift = sim.brakePoint(1, steady) - sim.brakePoint(1, early2)
        val metres = Math.round(shift / 5).toInt() * 5
        val advice = assertIs<Advice.BrakeLater>(cue.advice)
        assertEquals(metres, advice.meters, "true shift $shift m")
        // The target (FBBD-242): the asked-for brake point, counted to the apex on a track without corners.
        val corner2 = engine.corners[1]
        val before = assertNotNull(cue.before)
        val at = Math.round((corner2.apexAt - before.brakeAt - metres) / 5).toInt() * 5
        assertEquals(at, advice.at)
        assertEquals("Turn 2, brake at $at, $metres later", cue.text)
        // Paces 0 to 2 are the learning laps (timer laps 2 to 4); the cue comes on the next one.
        assertEquals(5, cue.lap)
        val heardBefore = assertOutsideBrakingZones(drive, run.cues, paces, 0)
        assertEquals(2, heardBefore[run.cues.indexOf(cue)])
        // The lap it was given on still braked early: not improved.
        val result = run.results.single()
        assertEquals(cue.seq, result.cue.seq)
        assertFalse(result.improved)
        assertEquals(CoachPhase.Coaching, engine.phase)
    }

    @Test
    fun `back out the same day it warms up for one lap, not the learning laps`() {
        val paces = listOf(early2, early2, early2, steady)
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference(), sameDay = true)
        assertEquals(CoachPhase.Learning(0, 1), engine.phase)
        val cue = run(engine, sim.drive(paces)).cues.first { it.kind == CueKind.Corner }
        assertEquals(2, cue.corner)
        // Pace 0 is the warm-up lap (timer lap 2); the cue comes on the next one.
        assertEquals(3, cue.lap)
        // Fewer learning laps in the settings still count.
        val none = CueEngine(track, CoachSettings(learningLaps = 0), reference(), sameDay = true)
        assertEquals(CoachPhase.Coaching, none.phase)
        // Without an earlier reference the corners still come from this session: full learning.
        assertEquals(CoachPhase.Learning(0, 3), CueEngine(track, CoachSettings(), sameDay = true).phase)
    }

    @Test
    fun `a corner the driver fixed gets no cue`() {
        val paces = listOf(early2, early2, steady, steady, steady, steady)
        val run = run(CueEngine(track, CoachSettings(Verbosity.Full), reference()), sim.drive(paces))
        assertTrue(run.cues.none { it.kind == CueKind.Corner }, "cues: ${run.cues.map { it.text }}")
    }

    @Test
    fun `the next pass after a cue is reported as improved when the driver brakes later`() {
        val paces = listOf(early2, early2, steady, steady)
        val engine = CueEngine(track, CoachSettings(Verbosity.Full, learningLaps = 2), reference())
        val run = run(engine, sim.drive(paces))
        val cue = run.cues.single { it.kind == CueKind.Corner }
        assertEquals(4, cue.lap)
        val result = run.results.single()
        assertTrue(result.improved)
        assertTrue(result.gainMicros > 50_000, "gain ${result.gainMicros}")
    }

    @Test
    fun `cues end before the braking zone even with a 2 second audio delay`() {
        val slow3 = SimCircuit.Pace(kmh = listOf(0.0, 0.0, -7.0, 0.0))
        val paces = listOf(slow3, slow3, slow3, slow3, slow3)
        val drive = sim.drive(paces)
        val run = run(CueEngine(track, CoachSettings(Verbosity.Full, audioDelayMillis = 2_000), reference()), drive)
        val cue = run.cues.single { it.kind == CueKind.Corner }
        val advice = cue.advice as Advice.CarrySpeed
        assertTrue(advice.kmh in 6..8, "advice $advice")
        assertOutsideBrakingZones(drive, run.cues, paces, 2_000)
    }

    /**
     * FBBD-229: each fix arrives 0.8 s after it was taken, and says so. The cue still ends before the
     * braking zone where the car really is when it is heard.
     */
    @Test
    fun `cues allow for a fix that arrives late`() {
        val slow3 = SimCircuit.Pace(kmh = listOf(0.0, 0.0, -7.0, 0.0))
        val paces = listOf(slow3, slow3, slow3, slow3, slow3)
        val drive = sim.drive(paces)
        val age = 800_000L
        val late = drive.fixes.map { it.copy(tMicros = it.tMicros + age, ageMicros = age) }
        val engine = CueEngine(track, CoachSettings(Verbosity.Full, audioDelayMillis = 1_000), reference())
        val cues = late.flatMap { engine.update(it).cues }
        assertTrue(cues.any { it.kind == CueKind.Corner }, "cues $cues")
        // Cue times are arrival times: the real clock, on which the drive says where the car is.
        assertOutsideBrakingZones(drive, cues, paces, 1_000)
    }

    @Test
    fun `without an earlier session it learns from this one and speaks lap times`() {
        val paces = List(5) { steady }
        val drive = sim.drive(paces)
        val engine = CueEngine(track, CoachSettings(Verbosity.Full))
        assertEquals(CoachPhase.Learning(0, 3), engine.phase)
        val run = run(engine, drive)
        assertEquals(4, engine.corners.size)
        assertTrue(run.cues.none { it.kind == CueKind.Corner })
        // Laps 2 to 6 are timed; the last ends 150 m before the drive stops, enough to say it.
        assertEquals(5, run.cues.count { it.kind == CueKind.LapTime }, "cues: ${run.cues.map { it.text }}")
        assertOutsideBrakingZones(drive, run.cues, paces, 0)
    }

    @Test
    fun `lap times only says nothing about corners`() {
        val paces = listOf(early2, early2, early2, early2, early2)
        val run = run(CueEngine(track, CoachSettings(Verbosity.LapTimes), reference()), sim.drive(paces))
        assertTrue(run.cues.isNotEmpty())
        assertTrue(run.cues.all { it.kind == CueKind.LapTime })
        val off = run(CueEngine(track, CoachSettings(Verbosity.Off), reference()), sim.drive(paces))
        assertTrue(off.cues.isEmpty())
    }

    @Test
    fun `advice follows the pass against the target`() {
        val target = CornerPass(
            corner = 1, lap = 1, brakeAt = 100.0, apexAt = 160.0,
            entryKmh = 160.0, minKmh = 80.0, timeMicros = 5_000_000L,
        )
        fun pass(brakeAt: Double = 100.0, minKmh: Double = 80.0) =
            target.copy(timeMicros = 5_300_000, brakeAt = brakeAt, minKmh = minKmh)
        assertNull(advise(target.copy(timeMicros = 5_040_000, brakeAt = 80.0), target))
        assertEquals(Advice.BrakeLater(20), advise(pass(brakeAt = 60.0), target))
        assertEquals(Advice.BrakeLater(10), advise(pass(brakeAt = 89.0), target))
        assertEquals(Advice.BrakeEarlier(15), advise(pass(brakeAt = 115.0, minKmh = 74.0), target))
        assertEquals(Advice.CarrySpeed(5), advise(pass(minKmh = 75.0), target))
        assertNull(advise(pass(minKmh = 78.0), target))
    }

    @Test
    fun `lap times read naturally`() {
        assertEquals("1 32 point 4", Phrases.lapTime(92_449, best = false))
        assertEquals("Best lap, 2 05 point 0", Phrases.lapTime(124_960, best = true))
        assertEquals("58 point 3", Phrases.lapTime(58_300, best = false))
    }
}
