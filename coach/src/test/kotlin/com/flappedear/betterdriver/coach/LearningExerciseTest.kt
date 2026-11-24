@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.learning.BrakeToThrottleGaps
import com.flappedear.betterdriver.analysis.learning.GapObservation
import com.flappedear.betterdriver.analysis.learning.SessionLearning
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The learning coach's exercise (FBBD-418): up to three proposed after a session from the
 * brake-to-throttle gap (FBBD-417) and the session focus, the driver's pick kept with the proposals
 * as they were, and the next session's coach working on that corner alone.
 */
class LearningExerciseTest {
    private val sim = SimCircuit()
    private val track = sim.track()
    private val steady = SimCircuit.Pace()

    /** Bend [k]'s line every 5 m from 10 m before it to 10 m after it, as the lap analysis keeps it. */
    private fun line(k: Int): Pair<List<Double>, List<Double>> {
        val from = sim.apexes[k] - sim.arc / 2 - 10
        val points = (0..((sim.arc + 20) / 5).toInt()).map { sim.frame.toGeo(sim.xy(from + it * 5.0)) }
        return points.map { it.lat } to points.map { it.lon }
    }

    private fun pick(session: String, k: Int): CornerPick =
        line(k).let { (lats, lons) -> CornerPick("Corner ${k + 1}", lats, lons, session) }

    private fun target(session: String, k: Int, role: String): FocusTarget {
        val (lats, lons) = line(k)
        return FocusTarget(
            id = FocusTarget.idOf(session, "c${k + 1}"),
            role = role,
            segmentId = "c${k + 1}",
            name = "Corner ${k + 1}",
            kind = "repeatedLoss",
            confidence = "HIGH",
            timeAvailableSeconds = 0.25,
            observation = "In 4 of 5 compared laps you lost time through Corner ${k + 1}.",
            what = "Your 2 fastest passes braked 8 m later than your other 3.",
            lats = lats,
            lons = lons,
        )
    }

    private fun observation(k: Int, median: Double) = GapObservation(
        segmentId = "c${k + 1}",
        name = "Corner ${k + 1}",
        medianSeconds = median,
        spreadSeconds = 0.2,
        othersMedianSeconds = 0.42,
        passesWithGap = 5,
        usablePasses = 6,
        lapNumbers = listOf(1, 2, 3, 4, 5, 6),
        text = "Observed in Corner ${k + 1}: ${median}0 s off both pedals.",
    )

    private val gaps = BrakeToThrottleGaps(observations = listOf(observation(0, 1.1), observation(2, 1.6)))

    private fun record(session: String, start: Long): SessionFocusRecord {
        val targets = listOf(target(session, 1, FocusTarget.Primary), target(session, 2, FocusTarget.Secondary))
        val corners = (0..3).associate { "c${it + 1}" to pick(session, it) }
        return SessionFocusRecord(
            trackId = "sim",
            sessionId = session,
            sessionStartUtcMillis = start,
            algorithm = "test",
            targets = targets,
            exercises = exerciseProposals(session, SessionLearning(gaps), targets, corners),
        )
    }

    private fun reference(): CoachReference {
        val laps = tracedLaps(track, sim.drive(List(3) { steady }).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    private fun cornerCues(engine: CueEngine, drive: SimCircuit.Drive): List<Cue> =
        drive.fixes.flatMap { engine.update(it).cues }.filter { it.kind == CueKind.Corner }

    /** Braking early everywhere, by a different amount in each corner: corner 1 loses least. */
    private val earlyEverywhere = SimCircuit.Pace(earlierM = listOf(12.0, 18.0, 24.0, 30.0))

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("exercise").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the gaps come first, longest first, then the focus at other corners, three at most`() {
        val proposals = record("s1", 1).exercises
        assertEquals(listOf("s1/brakeToThrottle/c3", "s1/brakeToThrottle/c1", "s1/focus/c2"), proposals.map { it.id })
        val gap = proposals.first()
        assertEquals("Corner 3", gap.where)
        assertEquals("Observed in Corner 3: 1.60 s off both pedals.", gap.seen)
        assertEquals("Go from the brake straight to the throttle: no pause off both pedals.", gap.practise)
        // Better: as short as at the other corners (0.42 s, up to the tenth), never below the 0.3 s counted.
        assertEquals("Off both pedals for 0.5 s or less on most laps (now 1.6 s).", gap.better)
        assertEquals(0.5, gap.baseline.targetSeconds)
        assertEquals(1.6, gap.baseline.medianSeconds)
        assertEquals(0.42, gap.baseline.othersMedianSeconds)
        assertEquals(6, gap.baseline.usablePasses)
        assertEquals(5, gap.baseline.passesWithGap)
        assertEquals(0.3, gap.baseline.gapSeconds)
        assertEquals("brake-to-throttle-gap-v1", gap.baseline.algorithm)
        assertEquals(Advice.BrakeToThrottle(), gap.advice)
        val focus = proposals[2]
        assertEquals(ExerciseFocus, focus.kind)
        assertEquals("Your 2 fastest passes braked 8 m later than your other 3.", focus.practise)
        assertEquals("A typical pass closer to your best than 0.25 s.", focus.better)
        assertNull(focus.advice)
    }

    @Test
    fun `a gap at a corner the coach cannot place is not proposed, nor a focus without its line`() {
        val noLine = target("s1", 1, FocusTarget.Primary).copy(lats = emptyList(), lons = emptyList())
        val proposals = exerciseProposals("s1", SessionLearning(gaps), listOf(noLine), mapOf("c1" to pick("s1", 0)))
        assertEquals(listOf("s1/brakeToThrottle/c1"), proposals.map { it.id })
        assertTrue(exerciseProposals("s1", null, emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `a pick is kept with the proposals as they were - analysed again, the session keeps it`() = withDir { dir ->
        val store = SessionFocusStore(dir.path)
        val r = record("s1", 1)
        store.save(r)
        assertNull(store.latestExercise("sim"))
        val picked = assertNotNull(store.pickExercise("sim", "s1", "s1/brakeToThrottle/c3", 5_000L))
        assertEquals(ExercisePick("s1/brakeToThrottle/c3", 5_000L), picked.exercisePick)
        assertEquals("s1/brakeToThrottle/c3", store.latestExercise("sim")?.exercise?.id)
        // Analysed again with other numbers: the proposals and the pick stay as they were picked.
        val again = r.copy(exercises = exerciseProposals("s1", null, r.targets, emptyMap()))
        store.save(again)
        val kept = assertNotNull(store.get("sim", "s1"))
        assertEquals(r.exercises, kept.exercises)
        assertEquals(picked.exercisePick, kept.exercisePick)
        // Not this session's own, nor one whose session is gone.
        assertNull(store.latestExercise("sim", exclude = "s1"))
        assertNull(store.latestExercise("sim") { false })
        assertFailsWith<IllegalArgumentException> { store.pickExercise("sim", "s1", "s1/focus/c9", 6_000L) }
        assertNull(store.pickExercise("sim", "s9", null, 6_000L))
    }

    @Test
    fun `a skip - or a later session not answered - leaves no exercise`() = withDir { dir ->
        val store = SessionFocusStore(dir.path)
        store.save(record("s1", 1))
        store.pickExercise("sim", "s1", "s1/brakeToThrottle/c1", 5_000L)
        store.save(record("s2", 2))
        assertNull(store.latestExercise("sim"))
        store.pickExercise("sim", "s2", null, 6_000L)
        assertEquals(ExercisePick(null, 6_000L), store.get("sim", "s2")?.exercisePick)
        assertNull(store.latestExercise("sim"))
        // The focus is still the next session's when nothing was picked.
        assertEquals("s2", store.latest("sim")?.sessionId)
    }

    @Test
    fun `a record written before exercises reads with none`() = withDir { dir ->
        val f = File(dir, "sim/s0.json")
        f.parentFile.mkdirs()
        f.writeText(
            """{"format":"betterdriver-session-focus","version":1,"trackId":"sim","sessionId":"s0",""" +
                """"sessionStartUtcMillis":0,"algorithm":"a","targets":[]}""",
        )
        val r = assertNotNull(SessionFocusStore(dir.path).get("sim", "s0"))
        assertTrue(r.exercises.isEmpty())
        assertNull(r.exercise)
    }

    @Test
    fun `with a brake-to-throttle exercise only its corner is cued - as the exercise, even at full`() {
        val exercise = record("s1", 1).exercises.single { it.id == "s1/brakeToThrottle/c3" }
        val focus = listOf(assertNotNull(exercise.pick("s1")))
        // At Full every corner would be cued without the exercise.
        val engine = CueEngine(
            track, CoachSettings(Verbosity.Full), reference(), focus = focus, exercise = exercise,
        )
        val cued = cornerCues(engine, sim.drive(List(7) { earlyEverywhere }))
        assertEquals(listOf(3), engine.focusCorners)
        assertTrue(cued.isNotEmpty())
        assertEquals(setOf(3), cued.mapNotNull { it.corner }.toSet(), "cues: ${cued.map { it.text }}")
        assertTrue(cued.all { it.text == "Turn 3, brake to throttle" }, "cues: ${cued.map { it.text }}")
        assertTrue(cued.all { it.advice == Advice.BrakeToThrottle() && it.target == null && it.before != null })
        // One lap to try it before it is said again.
        val laps = cued.map { it.lap }
        assertTrue(laps.zipWithNext().all { (a, b) -> b - a >= 2 }, "laps: $laps")
    }

    @Test
    fun `it is cued even where no time is lost - the exercise is not about a target pass`() {
        val exercise = record("s1", 1).exercises.single { it.id == "s1/brakeToThrottle/c3" }
        val engine = CueEngine(
            track, CoachSettings(Verbosity.Focus), reference(), focus = listOfNotNull(exercise.pick("s1")),
            exercise = exercise,
        )
        val cued = cornerCues(engine, sim.drive(List(7) { steady }))
        assertTrue(cued.any { it.corner == 3 }, "cues: ${cued.map { it.text }}")
    }

    @Test
    fun `an exercise at the corner after the line is said - the lap time does not take its straight`() {
        val exercise = record("s1", 1).exercises.single { it.id == "s1/brakeToThrottle/c1" }
        val engine = CueEngine(
            track, CoachSettings(Verbosity.Focus), reference(), focus = listOfNotNull(exercise.pick("s1")),
            exercise = exercise,
        )
        val cues = sim.drive(List(7) { earlyEverywhere }).fixes.flatMap { engine.update(it).cues }
        val corner = cues.filter { it.kind == CueKind.Corner }
        assertTrue(corner.isNotEmpty(), "cues: ${cues.map { it.text }}")
        assertTrue(corner.all { it.text == "Turn 1, brake to throttle" })
        // The lap times are still said.
        assertTrue(cues.count { it.kind == CueKind.LapTime } >= 3, "cues: ${cues.map { it.text }}")
    }

    @Test
    fun `with a focus exercise only its corner gets its usual advice`() {
        val exercise = record("s1", 1).exercises.single { it.kind == ExerciseFocus }
        val engine = CueEngine(
            track, CoachSettings(Verbosity.Full), reference(), focus = listOfNotNull(exercise.pick("s1")),
            exercise = exercise,
        )
        val cued = cornerCues(engine, sim.drive(List(7) { earlyEverywhere }))
        assertEquals(setOf(2), cued.mapNotNull { it.corner }.toSet(), "cues: ${cued.map { it.text }}")
        assertTrue(cued.none { it.advice is Advice.BrakeToThrottle })
    }

    @Test
    fun `the coach session logs the exercise cue so it reads back - in Polish too`() {
        val exercise = record("s1", 1).exercises.single { it.id == "s1/brakeToThrottle/c3" }
        val settings = CoachSettings(Verbosity.Focus, language = CoachLanguage.Polish)
        val session = CoachSession(
            settings, track, reference(), focus = listOfNotNull(exercise.pick("s1")), exercise = exercise,
        )
        val cues = sim.drive(List(7) { earlyEverywhere }).fixes.flatMap { fix ->
            session.onFix(fix, fix.tMicros) { true }.events.filter { it.kind == CoachEvents.CUE }
                .mapNotNull { CoachEvents.parseCue(it.tMicros, it.text) }
        }
        assertTrue(cues.isNotEmpty())
        val said = cues.filter { it.kind == CueKind.Corner }
        assertTrue(said.isNotEmpty(), "cues: ${cues.map { "${it.kind} ${it.text}" }}")
        assertTrue(said.all { it.advice == Advice.BrakeToThrottle() }, "cues: ${said.map { it.advice }}")
        val three = PolishPhrases.turn(3) + ", z hamulca na gaz"
        assertTrue(said.all { it.text == three }, "cues: ${said.map { it.text }}")
        val nine = PolishPhrases.turn(9) + ", " + PolishPhrases.words(Advice.BrakeToThrottle())
        assertEquals("Dziewiątka, z hamulca na gaz", nine)
        assertEquals(
            "source=s1 exercise=s1/brakeToThrottle/c3 kind=brakeToThrottle corner=c3",
            CoachEvents.exerciseText("s1", exercise),
        )
    }

    @Test
    fun `a pass back on the throttle sooner counts as having moved`() {
        val before = CornerPass(1, 1, 100.0, 150.0, 90.0, 60.0, 4_000_000L, pickupAt = 180.0)
        assertTrue(improved(Advice.BrakeToThrottle(), before, before.copy(pickupAt = 170.0)))
        assertTrue(!improved(Advice.BrakeToThrottle(), before, before.copy(pickupAt = 178.0)))
    }
}
