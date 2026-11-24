package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.coaching.CoachedExercise
import com.flappedear.betterdriver.analysis.learning.SessionLearning
import com.flappedear.betterdriver.format.fixed
import kotlinx.serialization.Serializable
import kotlin.math.max

/** What came of an exercise (FBBD-419). */
const val OutcomeNotEnoughData = "notEnoughData"
const val OutcomeNotDone = "notDone"
const val OutcomeDoneNoGain = "doneNoGain"
const val OutcomeDoneFaster = "doneFaster"

/** Why an outcome is [OutcomeNotEnoughData]. */
const val ResultNoPlayedCue = "noPlayedCue"
const val ResultTooFewPasses = "tooFewPasses"
const val ResultSectionNotTimed = "sectionNotTimed"
const val ResultKindNotMeasured = "kindNotMeasured"

/** The optional question's answers (FBBD-419): why the gap stayed. Unanswered is null, unknown. */
const val AnswerTraffic = "traffic"
const val AnswerCarMoving = "carMoving"
const val AnswerOnPurpose = "onPurpose"
const val AnswerNotSure = "notSure"
val ExerciseAnswers = listOf(AnswerTraffic, AnswerCarMoving, AnswerOnPurpose, AnswerNotSure)

/** The result's algorithm, kept with each result. */
const val ExerciseResultAlgorithm = "exercise-result-v1"

/**
 * The result's thresholds (FBBD-419), unvalidated like the detector's (FBBD-420). At least
 * [minPasses] passes after the first heard cue; the gap counts as changed when its median fell by
 * half the way to the target, at least [minDropSeconds]; the section counts as faster when its median
 * fell by [gainSeconds], with the segment after it slower by no more than [downstreamSeconds].
 */
data class ResultThresholds(
    val minPasses: Int = 3,
    val minDropSeconds: Double = 0.25,
    val gainSeconds: Double = 0.05,
    val downstreamSeconds: Double = 0.05,
)

/**
 * What came of exercise [exerciseId], picked after the session whose record keeps this, in session
 * [drivenSessionId] (FBBD-419). [outcome] is one of the Outcome constants; [reason] says why there is
 * not enough data. [passes] counted from [laps] (the laps from the first heard cue on); [medianSeconds]
 * the gap's median there against the baseline's [baselineSeconds]; [sectionDeltaSeconds] and
 * [downstreamDeltaSeconds] the section's and the next segment's medians against the baseline's,
 * negative when quicker. [text] says what was measured and claims no cause. [asked]: the optional
 * question is offered, as its answer changes the next proposals; [answer] is one of [ExerciseAnswers],
 * null while unanswered.
 */
@Serializable
data class ExerciseOutcome(
    val drivenSessionId: String,
    val exerciseId: String,
    val outcome: String,
    val reason: String = "",
    val passes: Int = 0,
    val laps: List<Int> = emptyList(),
    val medianSeconds: Double? = null,
    val baselineSeconds: Double? = null,
    val sectionDeltaSeconds: Double? = null,
    val downstreamDeltaSeconds: Double? = null,
    val text: String = "",
    val asked: Boolean = false,
    val answer: String? = null,
    val algorithm: String = ExerciseResultAlgorithm,
    val validated: Boolean = false,
) {
    init {
        require(answer == null || answer in ExerciseAnswers) { "unknown answer: $answer" }
    }
}

/**
 * What came of [exercise] in session [drivenSessionId], from what the coach did there ([worked]) and
 * the session's [learning]: did the gap at its corner change against the frozen baseline, and was the
 * corner with its exit straight quicker, the segment after it no slower. Passes count only from the
 * first lap with an exercise cue heard to its end (FBBD-416); typical passes (medians) are compared,
 * never best against best.
 */
@Suppress("ReturnCount") // Each early return is a reason there is not enough to say.
fun exerciseOutcome(
    exercise: ExerciseProposal,
    worked: CoachedExercise,
    drivenSessionId: String,
    learning: SessionLearning,
    t: ResultThresholds = ResultThresholds(),
): ExerciseOutcome {
    fun notEnough(reason: String, passes: Int = 0, laps: List<Int> = emptyList()) = ExerciseOutcome(
        drivenSessionId, exercise.id, OutcomeNotEnoughData, reason, passes, laps,
        baselineSeconds = exercise.baseline.medianSeconds, text = notEnoughText(exercise.where, reason),
    )
    if (exercise.kind != ExerciseBrakeToThrottle) return notEnough(ResultKindNotMeasured)
    val first = worked.playedLaps.minOrNull() ?: return notEnough(ResultNoPlayedCue)
    val corner = learning.gaps.corners.firstOrNull { it.segmentId == exercise.corner }
    val passes = corner?.passes.orEmpty().filter { it.lapNumber >= first }
    val laps = passes.map { it.lapNumber }
    if (passes.size < t.minPasses) return notEnough(ResultTooFewPasses, passes.size, laps)
    val before = requireNotNull(exercise.baseline.medianSeconds) { "a gap exercise without its median" }
    val aim = exercise.baseline.targetSeconds ?: before
    val now = median(passes.map { it.gapSeconds })
    val done = before - now >= max(t.minDropSeconds, (before - aim) / 2)
    val gap = "${exercise.where}: ${fixed(now, 1)} s off both pedals " +
        "(before: ${fixed(before, 1)} s, aim ${fixed(aim, 1)} s)."
    if (!done) {
        return ExerciseOutcome(
            drivenSessionId, exercise.id, OutcomeNotDone, passes = passes.size, laps = laps, medianSeconds = now,
            baselineSeconds = before, text = gap, asked = true,
        )
    }
    val section = learning.section(exercise.corner)
    val after = section?.seconds?.filterKeys { it >= first }?.values?.toList().orEmpty()
    val sectionBefore = exercise.baseline.sectionSeconds
    if (section == null || after.size < t.minPasses || sectionBefore == null) {
        return notEnough(ResultSectionNotTimed, passes.size, laps).copy(medianSeconds = now, text = gap + " " +
            "The corner and its exit straight were not timed on enough laps to compare.")
    }
    val delta = median(after) - sectionBefore
    val downstreamAfter = section.downstreamSeconds.filterKeys { it >= first }.values.toList()
    val downstreamDelta = exercise.baseline.downstreamSeconds?.takeIf { downstreamAfter.isNotEmpty() }
        ?.let { median(downstreamAfter) - it }
    val faster = delta <= -t.gainSeconds && (downstreamDelta == null || downstreamDelta <= t.downstreamSeconds)
    return ExerciseOutcome(
        drivenSessionId, exercise.id, if (faster) OutcomeDoneFaster else OutcomeDoneNoGain,
        passes = passes.size, laps = laps, medianSeconds = now, baselineSeconds = before,
        sectionDeltaSeconds = delta, downstreamDeltaSeconds = downstreamDelta,
        text = gap + " " + sectionText(delta, downstreamDelta, t),
    )
}

/** The section's time in words, with no claim of cause. */
private fun sectionText(delta: Double, downstream: Double?, t: ResultThresholds): String {
    val section = when {
        delta <= -t.gainSeconds -> "The corner and its exit straight were ${fixed(-delta, 2)} s quicker than before"
        delta >= t.gainSeconds -> "The corner and its exit straight were ${fixed(delta, 2)} s slower than before"
        else -> "The corner and its exit straight took about the same time as before"
    }
    val next = when {
        downstream == null -> ""
        downstream > t.downstreamSeconds -> "; the next section was ${fixed(downstream, 2)} s slower"
        else -> "; the next section was no slower"
    }
    return "$section$next."
}

private fun notEnoughText(where: String, reason: String) = when (reason) {
    ResultNoPlayedCue -> "$where: the exercise cue was not heard on any lap, so nothing was measured."
    ResultTooFewPasses -> "$where: too few laps after the first cue to tell."
    ResultKindNotMeasured -> "$where: this exercise has no measured result yet."
    else -> "$where: not enough data."
}

/** The question offered with an [OutcomeNotDone] result (FBBD-419), skippable. */
const val ExerciseQuestion = "Why the gap?"

/** The words of each answer, in [ExerciseAnswers] order. */
val ExerciseAnswerWords = listOf("Traffic", "Car moving", "On purpose", "Not sure")

/**
 * Keeps what came of the exercise the coach worked on in session [drivenSessionId] at [trackId]
 * ([coached]), from that session's [learning], on the record of the session it was picked after
 * (FBBD-419). Returns the result kept, or null when the coach worked on none, or its record or
 * exercise is gone. The same on Android and iOS.
 */
@Suppress("ReturnCount") // Nothing to keep without each of them.
fun keepExerciseOutcome(
    store: SessionFocusStore,
    trackId: String,
    drivenSessionId: String,
    coached: CoachedExercise?,
    learning: SessionLearning,
): ExerciseOutcome? {
    val worked = coached?.takeIf { it.sourceSessionId != drivenSessionId } ?: return null
    val source = store.get(trackId, worked.sourceSessionId) ?: return null
    val exercise = source.exercises.firstOrNull { it.id == worked.exerciseId } ?: return null
    val outcome = exerciseOutcome(exercise, worked, drivenSessionId, learning)
    return store.recordOutcome(trackId, worked.sourceSessionId, outcome)?.exerciseOutcome
}

/** [outcome]'s numbers as one log line, "exercise.result id=… outcome=… …". */
fun ExerciseOutcome.resultLine(): String =
    "exercise.result id=$exerciseId driven=$drivenSessionId outcome=$outcome" +
        (if (reason.isNotEmpty()) " reason=$reason" else "") +
        " passes=$passes laps=${laps.joinToString(",").ifEmpty { "-" }}" +
        " median=${medianSeconds?.let { fixed(it, 2) } ?: "-"}" +
        " before=${baselineSeconds?.let { fixed(it, 2) } ?: "-"}" +
        " section=${sectionDeltaSeconds?.let { fixed(it, 2) } ?: "-"}" +
        " downstream=${downstreamDeltaSeconds?.let { fixed(it, 2) } ?: "-"} asked=$asked" +
        " algorithm=$algorithm validated=$validated"
