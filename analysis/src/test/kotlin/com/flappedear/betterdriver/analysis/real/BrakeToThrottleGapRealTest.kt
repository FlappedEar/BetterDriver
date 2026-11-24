@file:Suppress("MagicNumber") // Arek's sessions and what they show.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.focus.nextSessionPlan
import com.flappedear.betterdriver.analysis.segments.flaggableCorners
import com.flappedear.betterdriver.analysis.coaching.CoachedExercise

import com.flappedear.betterdriver.coach.AnswerOnPurpose
import com.flappedear.betterdriver.coach.ExerciseBrakeToThrottle
import com.flappedear.betterdriver.coach.OutcomeNotDone
import com.flappedear.betterdriver.coach.OutcomeNotEnoughData
import com.flappedear.betterdriver.coach.ResultNoPlayedCue
import com.flappedear.betterdriver.coach.SessionFocusStore
import com.flappedear.betterdriver.coach.keepExerciseOutcome
import com.flappedear.betterdriver.coach.resultLine
import com.flappedear.betterdriver.coach.coachCandidates
import com.flappedear.betterdriver.coach.focusRecord
import com.flappedear.betterdriver.analysis.learning.BrakeToThrottleGaps
import com.flappedear.betterdriver.analysis.learning.GapComparisonTooFewCorners
import com.flappedear.betterdriver.analysis.learning.brakeToThrottleGaps
import com.flappedear.betterdriver.analysis.learning.logLines
import com.flappedear.betterdriver.analysis.learning.sessionLearning
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackRevision
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The learning coach's brake-to-throttle gap (FBBD-417) on Arek's nine FR-S sessions (Jastrząb
 * 29 Aug, Silesia Ring 27 Sep, RaceChrono with the car's brake and accelerator over OBD at 10 Hz).
 * At Jastrząb the same corner, Corner 9 (the brake let off about 1.6 to 1.8 km into the lap, before
 * the last complex where the line analysis finds most time, LineRealTest), comes out in four of
 * the five sessions with enough laps: 1.1 to 2.1 s off both pedals on nearly every lap, against
 * 0.4 to 0.9 s at the other corners. The thresholds are unvalidated heuristics (FBBD-420); this
 * fixes what they find today so a change to them is seen.
 */
class BrakeToThrottleGapRealTest {
    @TempDir
    lateinit var tmp: File

    private fun gaps(files: List<String>, track: Track): List<Pair<String, BrakeToThrottleGaps>> {
        val store = SessionStore(File(tmp, "sessions"))
        return files.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
            val session = telemetrySessionOf(store.read(id), track)
            name.substringAfter("-2026-").substringBefore("-racechrono") to brakeToThrottleGaps(analyzeSession(session))
        }
    }

    @Test
    fun `the gap is measured on every session, each brake counted once, and few corners are flagged`() {
        val all = gaps(RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack()) +
            gaps(RealCorpus.SILESIA_DAY, RealCorpus.silesiaTrack())
        assertEquals(9, all.size)
        for ((name, g) in all) {
            println("$name: " + g.logLines().joinToString(" | "))
            assertTrue(g.available, "$name ${g.unavailableReason}")
            assertTrue(g.observations.size <= 3, "$name ${g.observations.map { it.name }}")
            g.observations.forEach { assertTrue(it.text.startsWith("Observed in "), it.text) }
            // A brake let off on a lap belongs to one corner only.
            val releases = g.corners.flatMap { c -> c.passes.map { it.lapNumber to it.releaseTime } }
            assertEquals(releases.size, releases.toSet().size, name)
            // Every gap is a real time off both pedals, under the length of a straight.
            g.corners.flatMap { it.passes }.forEach { assertTrue(it.gapSeconds in 0.0..10.0, "$name ${it.gapSeconds}") }
        }
        // 17:20 had three timed laps: no corner has enough passes, nothing is claimed.
        val late = all.single { it.first == "08-29-1720" }.second
        assertEquals(GapComparisonTooFewCorners, late.comparisonReason)
        assertTrue(late.observations.isEmpty())
    }

    @Test
    fun `after the first Jastrzab session the brake-to-throttle exercise at Corner 9 is proposed first`() {
        val track = RealCorpus.jastrzabTrack()
        val store = SessionStore(File(tmp, "sessions"))
        val id = RealCorpus.import(store, File(tmp, "staging"), RealCorpus.JASTRZAB_DAY.first(), listOf(track)).id
        val analysis = analyzeSession(telemetrySessionOf(store.read(id), track))
        val corners = coachCandidates(flaggableCorners(analysis), id)
        val record = focusRecord(track.id, id, 0L, nextSessionPlan(analysis), corners, sessionLearning(analysis))
        println(record.exercises.joinToString("\n") { "${it.id} | ${it.seen} | ${it.practise} | ${it.better}" })
        assertTrue(record.exercises.size in 1..3)
        val first = record.exercises.first()
        assertEquals(ExerciseBrakeToThrottle, first.kind)
        assertEquals("Corner 9", first.where)
        assertTrue(first.lats.isNotEmpty())
        assertTrue(first.baseline.medianSeconds!! in 1.0..2.5)
        assertTrue(first.baseline.targetSeconds!! < first.baseline.medianSeconds!!)
        // Corner 9 and its exit straight timed on the counted laps, for the result (FBBD-419).
        val b = first.baseline
        println("section ${b.sectionSeconds} downstream ${b.downstreamSeconds} laps ${b.sectionLaps}")
        assertTrue(first.baseline.sectionLaps >= 3)
        assertTrue(first.baseline.sectionSeconds!! > 0.0)
        // Each corner proposed once.
        assertEquals(record.exercises.size, record.exercises.map { it.segmentId }.toSet().size)
    }

    /**
     * The learning loop end to end on two Jastrząb sessions (FBBD-419): Corner 9's gap after 10:00,
     * with no better pass to compare with, is proposed and picked; at 11:29 the coach's cue is heard
     * (or never), the gap stays as it was, so the result asks why; "on purpose" takes it out of the
     * proposals after 11:29, where the same gap is seen again.
     */
    @Test
    fun `pick - cue heard or missed - result - an answer changes the next proposals`() {
        val track = RealCorpus.jastrzabTrack()
        val sessions = SessionStore(File(tmp, "sessions"))
        val focus = SessionFocusStore(File(tmp, "focus").path)
        var identity: TrackRevision? = null
        val (first, second) = RealCorpus.JASTRZAB_DAY.take(2).map { name ->
            val id = RealCorpus.import(sessions, File(tmp, "staging"), name, listOf(track)).id
            val analysis = analyzeSession(telemetrySessionOf(sessions.read(id), track), identity = identity)
            identity = analysis.identity ?: identity
            Triple(id, analysis, sessionLearning(analysis))
        }
        fun record(s: Triple<String, SessionAnalysis, *>, start: Long) =
            focusRecord(
                track.id, s.first, start, nextSessionPlan(s.second),
                coachCandidates(flaggableCorners(s.second), s.first), sessionLearning(s.second),
                focus.history(track.id),
            )
        focus.save(record(first, 1))
        val exercise = focus.get(track.id, first.first)!!.exercises.first()
        assertEquals(ExerciseBrakeToThrottle, exercise.kind)
        assertEquals("Corner 9", exercise.where)
        focus.pickExercise(track.id, first.first, exercise.id, 2)
        assertEquals(exercise.id, focus.latestExercise(track.id)?.exercise?.id)

        // Every cue cut off: nothing measured.
        val missed = CoachedExercise(first.first, exercise.id, exercise.corner, emptyList(), listOf(2, 4, 6))
        val none = keepExerciseOutcome(focus, track.id, second.first, missed, second.third)!!
        assertEquals(OutcomeNotEnoughData, none.outcome)
        assertEquals(ResultNoPlayedCue, none.reason)

        // Heard from lap 2: the gap there stays about as long; the question is asked.
        val heard = CoachedExercise(first.first, exercise.id, exercise.corner, listOf(2, 4, 6))
        val result = keepExerciseOutcome(focus, track.id, second.first, heard, second.third)!!
        println(result.resultLine())
        println(result.text)
        assertEquals(OutcomeNotDone, result.outcome)
        assertTrue(result.asked)
        assertTrue(result.laps.all { it >= 2 })
        assertTrue(result.text.startsWith("Corner 9: "), result.text)

        // Unanswered: Corner 9's gap is proposed again after 11:29.
        assertEquals(exercise.corner, record(second, 3).exercises.first { it.kind == ExerciseBrakeToThrottle }.corner)
        // On purpose: it is not.
        focus.answerQuestion(track.id, first.first, AnswerOnPurpose)
        val after = record(second, 3).exercises
        println(after.joinToString { it.id })
        assertTrue(after.none { it.kind == ExerciseBrakeToThrottle && it.corner == exercise.corner }, "$after")
    }

    @Test
    fun `at Jastrzab the same corner before the last complex is flagged in four sessions`() {
        val day = gaps(RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack())
        val flagged = day.filter { (_, g) ->
            g.observations.any { o ->
                val corner = g.corners.single { it.segmentId == o.segmentId }
                o.name == "Corner 9" && corner.passes.all { it.releaseMeters in 1550.0..1800.0 }
            }
        }
        assertEquals(listOf("08-29-1000", "08-29-1129", "08-29-1303", "08-29-1606"), flagged.map { it.first })
        for ((name, g) in flagged) {
            val o = g.observations.single { it.name == "Corner 9" }
            assertTrue(o.medianSeconds in 1.0..2.5, "$name ${o.medianSeconds}")
            val most = o.passesWithGap >= 3 && o.passesWithGap * 4 >= o.usablePasses * 3
            assertTrue(most, "$name ${o.passesWithGap}/${o.usablePasses}")
        }
    }
}
