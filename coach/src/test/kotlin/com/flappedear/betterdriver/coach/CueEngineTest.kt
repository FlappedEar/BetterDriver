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
        phrases: PhraseBook = Phrases,
    ): List<Int> = cues.map { cue ->
        val start = cue.tMicros + DefaultLatencyMicros + delayMillis * 1_000L
        val end = start + phrases.durationMicros(cue.text)
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
    fun `in Polish the same cues are said in Polish, still before the braking zone`() {
        val paces = listOf(early2, early2, early2, early2, steady, steady)
        val drive = sim.drive(paces)
        val english = run(CueEngine(track, CoachSettings(Verbosity.Focus), reference()), drive)
        val settings = CoachSettings(Verbosity.Focus, language = CoachLanguage.Polish)
        val polish = run(CueEngine(track, settings, reference()), drive)
        assertEquals(english.cues.map { it.kind to it.lap }, polish.cues.map { it.kind to it.lap })
        val cue = polish.cues.single { it.kind == CueKind.Corner }
        val advice = assertIs<Advice.BrakeLater>(cue.advice)
        assertEquals("Zakręt 2, hamuj na ${advice.at}, ${advice.meters} później", cue.text)
        polish.cues.filter { it.kind == CueKind.LapTime }.forEach { assertTrue("przecinek" in it.text, it.text) }
        assertTrue(polish.cues.all { CueClips.covers(it.text, PolishPhrases) }, polish.cues.joinToString { it.text })
        assertOutsideBrakingZones(drive, polish.cues, paces, 0, PolishPhrases)
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
        // The cue worked, so the straight after the corner praises it (FBBD-243, UI-93 C).
        val praise = run.cues.single { it.kind == CueKind.Praise }
        assertEquals("Turn 2, that worked", praise.text)
        assertEquals(Praise.Worked, praise.praise)
        assertEquals(4, praise.lap)
        assertEquals(cue.before, praise.before)
        assertEquals(result.after, praise.praised)
        assertTrue(praise.tMicros >= result.tMicros)
        val heardBefore = assertOutsideBrakingZones(sim.drive(paces), run.cues, paces, 0)
        assertEquals(3, heardBefore[run.cues.indexOf(praise)])
    }

    @Test
    fun `a corner faster than ever before is praised once`() {
        val fast3 = SimCircuit.Pace(kmh = listOf(0.0, 0.0, 6.0, 0.0))
        val paces = listOf(steady, steady, steady, fast3, fast3)
        val drive = sim.drive(paces)
        val run = run(CueEngine(track, CoachSettings(Verbosity.Full), reference()), drive)
        val praise = run.cues.filter { it.kind == CueKind.Praise }
        assertEquals(listOf("Turn 3, best yet"), praise.map { it.text }, "cues: ${run.cues.map { it.text }}")
        // Timer lap 5 is pace 3; on lap 6 the same pace only equals the new best.
        assertEquals(5, praise.single().lap)
        assertEquals(Praise.BestYet, praise.single().praise)
        assertOutsideBrakingZones(drive, run.cues, paces, 0)
    }

    @Test
    fun `at most one praise per lap, none while learning or with lap times only`() {
        val fast23 = SimCircuit.Pace(kmh = listOf(0.0, 5.0, 5.0, 0.0))
        val faster23 = SimCircuit.Pace(kmh = listOf(0.0, 10.0, 10.0, 0.0))
        val paces = listOf(fast23, steady, steady, faster23)
        val praise = run(CueEngine(track, CoachSettings(Verbosity.Full), reference()), sim.drive(paces)).cues
            .filter { it.kind == CueKind.Praise }
        // Lap 2 (pace 0) beats the reference but is a learning lap; lap 5 (pace 3) beats it at both
        // corners but is praised once.
        assertEquals(listOf(5), praise.map { it.lap }, "praise: ${praise.map { it.text }}")
        assertEquals("Turn 2, best yet", praise.single().text)
        val quiet = run(CueEngine(track, CoachSettings(Verbosity.LapTimes), reference()), sim.drive(paces))
        assertTrue(quiet.cues.none { it.kind == CueKind.Praise })
    }

    @Test
    fun `praise is logged and read back`() {
        val pass = CornerPass(3, 5, 400.0, 460.0, 150.0, 72.0, 6_100_000)
        val before = pass.copy(lap = 4, minKmh = 68.0, timeMicros = 6_400_000)
        val cue = Cue(
            7, 1_000, 5, CueKind.Praise, "Turn 3, best yet", 3, before = before, turn = 3,
            praise = Praise.BestYet, praised = pass,
        )
        val back = assertNotNull(CoachEvents.parseCue(1_000, CoachEvents.cueText(cue)))
        assertEquals(cue, back)
        assertEquals("0.30 s faster than your best until then", ReviewedCue(back, null).outcome())
        assertNull(back.confidenceText())
        assertTrue(CueClips.covers(cue.text))
        assertTrue(CueClips.covers("Turn 12, that worked"))
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
