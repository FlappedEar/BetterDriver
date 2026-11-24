@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.coaching.CoachedExercise
import com.flappedear.betterdriver.analysis.learning.BrakeToThrottleGaps
import com.flappedear.betterdriver.analysis.learning.CornerGap
import com.flappedear.betterdriver.analysis.learning.CornerSection
import com.flappedear.betterdriver.analysis.learning.GapObservation
import com.flappedear.betterdriver.analysis.learning.GapPass
import com.flappedear.betterdriver.analysis.learning.SessionLearning
import com.flappedear.betterdriver.recorder.RecorderEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What came of an exercise (FBBD-419): the gap against its frozen baseline, then the corner with its
 * exit straight against the baseline's time, only from the first heard cue on; the optional question
 * when the gap stayed, and its answer changing the next proposals.
 */
class ExerciseResultTest {
    private val line = CornerPick("Corner 9", listOf(50.0, 50.0001), listOf(19.0, 19.0001), "s1", "c9", 2)

    /** Session s1: Corner 9 1.4 s off both pedals, section 10.0 s, next segment 5.0 s. */
    private val before = SessionLearning(
        BrakeToThrottleGaps(
            observations = listOf(
                GapObservation("c9", "Corner 9", 1.4, 0.2, 0.4, 5, 6, (1..6).toList(), "Observed in Corner 9."),
            ),
        ),
        listOf(
            CornerSection(
                "c9", "Corner 9", "s10", "c10",
                (1..6).associateWith { 10.0 + it * 0.02 }, (1..6).associateWith { 5.0 },
            ),
        ),
    )

    private val exercise = exerciseProposals("s1", before, emptyList(), mapOf("c9" to line)).single()

    /** Session s2: on each lap [gap] seconds at Corner 9 and the section [section] seconds. */
    private fun driven(gap: (Int) -> Double, section: (Int) -> Double, downstream: Double = 5.0, laps: Int = 8) =
        SessionLearning(
            BrakeToThrottleGaps(
                corners = listOf(
                    CornerGap(
                        "c9", "Corner 9",
                        (1..laps).map { GapPass(it, gap(it), 0.0, gap(it), 0.0, 0.0) },
                        emptyList(), null, null, 0,
                    ),
                ),
            ),
            listOf(
                CornerSection(
                    "c9", "Corner 9", "s10", "c10",
                    (1..laps).associateWith(section), (1..laps).associateWith { downstream },
                ),
            ),
        )

    private fun worked(played: List<Int>, missed: List<Int> = emptyList()) =
        CoachedExercise("s1", exercise.id, "c9", played, missed)

    @Test
    fun `the baseline keeps the section time - the median of the counted laps`() {
        assertEquals(10.07, exercise.baseline.sectionSeconds!!, 1e-9)
        assertEquals(5.0, exercise.baseline.downstreamSeconds)
        assertEquals(6, exercise.baseline.sectionLaps)
    }

    @Test
    fun `the gap shorter and the section quicker - done and faster - counted from the first heard cue`() {
        // Laps 1 and 2 before the cue are slow and long; they are not counted.
        val learning = driven({ if (it < 3) 1.5 else 0.5 }, { if (it < 3) 10.5 else 9.8 })
        val r = exerciseOutcome(exercise, worked(listOf(3, 5, 7)), "s2", learning)
        assertEquals(OutcomeDoneFaster, r.outcome)
        assertEquals((3..8).toList(), r.laps)
        assertEquals(0.5, r.medianSeconds)
        assertEquals(1.4, r.baselineSeconds)
        assertEquals(9.8 - 10.07, r.sectionDeltaSeconds!!, 1e-9)
        assertEquals(0.0, r.downstreamDeltaSeconds!!, 1e-9)
        assertFalse(r.asked)
        assertEquals(
            "Corner 9: 0.5 s off both pedals (before: 1.4 s, aim 0.4 s). The corner and its exit straight " +
                "were 0.27 s quicker than before; the next section was no slower.",
            r.text,
        )
        assertFalse(r.text.contains("because"))
    }

    @Test
    fun `quicker here but slower in the next section - no gain`() {
        val r = exerciseOutcome(exercise, worked(listOf(1)), "s2", driven({ 0.5 }, { 9.8 }, downstream = 5.3))
        assertEquals(OutcomeDoneNoGain, r.outcome)
        assertTrue(r.text.endsWith("the next section was 0.30 s slower."), r.text)
    }

    @Test
    fun `the gap shorter but the section the same - done, no gain`() {
        val r = exerciseOutcome(exercise, worked(listOf(1)), "s2", driven({ 0.6 }, { 10.06 }))
        assertEquals(OutcomeDoneNoGain, r.outcome)
        assertTrue(r.text.contains("took about the same time as before"), r.text)
    }

    @Test
    fun `the gap as before - not done, and the question is asked`() {
        val r = exerciseOutcome(exercise, worked(listOf(1)), "s2", driven({ 1.3 }, { 10.0 }))
        assertEquals(OutcomeNotDone, r.outcome)
        assertTrue(r.asked)
        assertNull(r.answer)
        assertEquals("Corner 9: 1.3 s off both pedals (before: 1.4 s, aim 0.4 s).", r.text)
    }

    @Test
    fun `no cue heard - or too few laps after it - not enough data`() {
        val none = exerciseOutcome(exercise, worked(emptyList(), listOf(2, 4)), "s2", driven({ 0.5 }, { 9.8 }))
        assertEquals(OutcomeNotEnoughData, none.outcome)
        assertEquals(ResultNoPlayedCue, none.reason)
        val late = exerciseOutcome(exercise, worked(listOf(7)), "s2", driven({ 0.5 }, { 9.8 }))
        assertEquals(ResultTooFewPasses, late.reason)
        assertEquals(listOf(7, 8), late.laps)
        val noSections = driven({ 0.5 }, { 9.8 }).copy(sections = emptyList())
        val untimed = exerciseOutcome(exercise, worked(listOf(1)), "s2", noSections)
        assertEquals(OutcomeNotEnoughData, untimed.outcome)
        assertEquals(ResultSectionNotTimed, untimed.reason)
        assertEquals(0.5, untimed.medianSeconds)
        // The section after it not timed: "no slower" is not assumed.
        val noNext = driven({ 0.5 }, { 9.8 })
        val untimedNext = noNext.copy(sections = noNext.sections.map { it.copy(downstreamSeconds = emptyMap()) })
        val r = exerciseOutcome(exercise, worked(listOf(1)), "s2", untimedNext)
        assertEquals(OutcomeNotEnoughData, r.outcome)
        assertEquals(ResultSectionNotTimed, r.reason)
    }

    @Test
    fun `the coach's events say which laps the exercise cue was heard on`() {
        fun cue(seq: Int, lap: Int, t: Long) = RecorderEvent(
            t, CoachEvents.CUE,
            CoachEvents.cueText(
                Cue(seq, t, lap, CueKind.Corner, "Turn 9, brake to throttle", 9, Advice.BrakeToThrottle(), turn = 9),
            ),
        )
        val events = listOf(
            RecorderEvent(1, CoachEvents.EXERCISE, CoachEvents.exerciseText("s1", exercise)),
            cue(1, 3, 10), cue(2, 5, 20), RecorderEvent(21, CoachEvents.PROBLEM, CoachEvents.cutOffText(2, "error")),
            cue(3, 7, 30),
            // Late, and refused by the voice: logged only as problems, with their laps.
            RecorderEvent(40, CoachEvents.PROBLEM, CoachEvents.lateText(Cue(4, 40, 9, CueKind.Corner, "Turn 9"))),
            RecorderEvent(50, CoachEvents.PROBLEM, CoachEvents.noVoiceText(Cue(5, 50, 11, CueKind.Corner, "Turn 9"))),
        )
        val w = assertNotNull(CoachEvents.exerciseWorked(events))
        assertEquals("s1", w.sourceSessionId)
        assertEquals(exercise.id, w.exerciseId)
        assertEquals("c9", w.corner)
        assertEquals(listOf(3, 7), w.playedLaps)
        assertEquals(listOf(5, 9, 11), w.missedLaps)
        assertEquals(w, CoachEvents.coachedSession(events).exercise)
        assertNull(CoachEvents.exerciseWorked(events.drop(1)))
    }

    @Test
    fun `the result is kept with the picked session - an answer to the question changes the next proposals`() {
        val dir = Files.createTempDirectory("result").toFile()
        try {
            val store = SessionFocusStore(dir.path)
            store.save(focusRecord("sim", "s1", 1, before, line))
            store.pickExercise("sim", "s1", exercise.id, 2)
            val drivenAgain = driven({ 1.3 }, { 10.0 })
            val kept = assertNotNull(keepExerciseOutcome(store, "sim", "s2", worked(listOf(1)), drivenAgain))
            assertEquals(OutcomeNotDone, kept.outcome)
            // Before an answer, the gap seen again in s2 is proposed again.
            val gapAgain = before
            assertEquals(listOf(exercise.kind), proposalsAfter(store, gapAgain).map { it.kind })
            assertNotNull(store.answerQuestion("sim", "s1", AnswerOnPurpose))
            assertEquals(AnswerOnPurpose, store.get("sim", "s1")?.exerciseOutcome?.answer)
            // Analysed again, s2's result keeps the answer.
            keepExerciseOutcome(store, "sim", "s2", worked(listOf(1)), drivenAgain)
            assertEquals(AnswerOnPurpose, store.get("sim", "s1")?.exerciseOutcome?.answer)
            // On purpose: not proposed again.
            assertTrue(proposalsAfter(store, gapAgain).isEmpty())
            // "Not sure" brings it back.
            store.answerQuestion("sim", "s1", AnswerNotSure)
            assertEquals(1, proposalsAfter(store, gapAgain).size)
            // No question after a result that did not ask one.
            assertNull(store.answerQuestion("sim", "s9", AnswerTraffic))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the result says what it measured in one log line`() {
        val r = exerciseOutcome(exercise, worked(listOf(1)), "s2", driven({ 1.3 }, { 10.0 }))
        assertEquals(
            "exercise.result id=s1/brakeToThrottle/c9 driven=s2 outcome=notDone passes=8 laps=1,2,3,4,5,6,7,8 " +
                "median=1.30 before=1.40 section=- downstream=- asked=true " +
                "algorithm=exercise-result-v1 validated=false",
            r.resultLine(),
        )
    }

    private fun proposalsAfter(store: SessionFocusStore, learning: SessionLearning) =
        exerciseProposals("s2", learning, emptyList(), mapOf("c9" to line), earlier = store.history("sim"))

    private fun focusRecord(track: String, session: String, start: Long, learning: SessionLearning, pick: CornerPick) =
        SessionFocusRecord(
            trackId = track,
            sessionId = session,
            sessionStartUtcMillis = start,
            exercises = exerciseProposals(session, learning, emptyList(), mapOf("c9" to pick)),
        )
}
