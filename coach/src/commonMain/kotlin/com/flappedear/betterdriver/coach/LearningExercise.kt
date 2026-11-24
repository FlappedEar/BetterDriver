package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.learning.CornerSection
import com.flappedear.betterdriver.analysis.learning.GapObservation
import com.flappedear.betterdriver.analysis.learning.SessionLearning
import com.flappedear.betterdriver.format.fixed
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max

/** The most exercises proposed after a session (FBBD-418). */
const val MaxExercises = 3

/** Exercise kinds (FBBD-418): back on the throttle straight after the brake, or a focus corner. */
const val ExerciseBrakeToThrottle = "brakeToThrottle"
const val ExerciseFocus = "focus"

/** The proposal algorithm, kept with every proposal so a later change is told apart. */
const val ExerciseAlgorithm = "learning-exercise-v1"

/**
 * What a proposal was measured on when it was made (FBBD-418), kept as it was: the after-session
 * result (FBBD-419) compares the next session with it, never with a later analysis of the same data.
 * For [ExerciseBrakeToThrottle], [medianSeconds] is the corner's median time off both pedals between
 * brake release and throttle, [othersMedianSeconds] that of the session's other judged corners and
 * [targetSeconds] what counts as better; for [ExerciseFocus], [medianSeconds] is the time a typical pass
 * left against the target pass, when known.
 */
@Serializable
data class ExerciseBaseline(
    val algorithm: String,
    val usablePasses: Int = 0,
    val passesWithGap: Int = 0,
    val lapNumbers: List<Int> = emptyList(),
    val medianSeconds: Double? = null,
    val othersMedianSeconds: Double? = null,
    val targetSeconds: Double? = null,
    /** The gap the detector counts (FBBD-417 `gapSeconds`); null for a focus exercise. */
    val gapSeconds: Double? = null,
    val validated: Boolean = false,
    /**
     * The median time through the corner and its exit straight over the session's counted laps
     * ([sectionLaps] of them), and of the segment after it (FBBD-419); null when not timed.
     */
    val sectionSeconds: Double? = null,
    val downstreamSeconds: Double? = null,
    val sectionLaps: Int = 0,
)

/**
 * One exercise proposed after a session (FBBD-418), in the words the pick screen shows: what was
 * [seen], what to [practise], [where] (the corner's name) and what counts as [better]. [segmentId],
 * [cornerId] and [identityRevision] name the corner as [FocusTarget] does; [lats]/[lons] are its line,
 * so the coach can place it. [id] names it for good, like [FocusTarget.id].
 */
@Serializable
data class ExerciseProposal(
    val id: String,
    val kind: String,
    val segmentId: String,
    val cornerId: String? = null,
    val identityRevision: Int? = null,
    val where: String,
    val seen: String,
    val practise: String,
    val better: String,
    val baseline: ExerciseBaseline,
    val lats: List<Double> = emptyList(),
    val lons: List<Double> = emptyList(),
) {
    init {
        require(kind == ExerciseBrakeToThrottle || kind == ExerciseFocus) { "unknown exercise: $kind" }
        require(lats.size == lons.size) { "a line needs as many longitudes as latitudes" }
    }

    /** The corner as a coach pick, null without its line. */
    fun pick(sessionId: String): CornerPick? = if (lats.isEmpty()) {
        null
    } else {
        CornerPick(where, lats, lons, sessionId, cornerId, identityRevision)
    }

    /** The corner as the next session finds it: its CornerId, else the analysis's segment id. */
    val corner: String get() = cornerId ?: segmentId

    /** What the coach says at the corner: the exercise itself, or for a focus exercise its usual advice. */
    val advice: Advice? get() = Advice.BrakeToThrottle().takeIf { kind == ExerciseBrakeToThrottle }
}

/**
 * The driver's answer to the proposals (FBBD-418): the exercise [id] picked, or null when skipped, at
 * [atUtcMillis]. The proposals stay as they were when picked.
 */
@Serializable
data class ExercisePick(val id: String?, val atUtcMillis: Long)

/**
 * Up to [MaxExercises] exercises after a session (FBBD-418): first each corner where the brake-to-throttle
 * gap was observed ([learning]'s gaps, FBBD-417), longest first, then the session focus [targets] at other
 * corners, primary first. Only corners with a line in [corners] (by segment id) or [targets] can be
 * coached, so only those are proposed. A gap the driver said was on purpose, or came from the car moving,
 * after the last exercise there ([earlier] records, FBBD-419) is not proposed again.
 */
@Suppress("LongParameterList") // The session, what it found, and what came before at the track.
fun exerciseProposals(
    sessionId: String,
    learning: SessionLearning?,
    targets: List<FocusTarget>,
    corners: Map<String, CornerPick>,
    identityRevision: Int? = null,
    earlier: List<SessionFocusRecord> = emptyList(),
): List<ExerciseProposal> {
    val gaps = learning?.gaps
    val explained = explainedGaps(earlier)
    val fromGaps = gaps?.observations.orEmpty().sortedByDescending { it.medianSeconds }.mapNotNull { o ->
        val line = corners[o.segmentId] ?: targets.firstOrNull { it.segmentId == o.segmentId }?.pick(sessionId)
        line?.takeIf { (it.cornerId ?: o.segmentId) !in explained }
            ?.let { gapExercise(sessionId, o, it, learning!!, identityRevision) }
    }
    val fromFocus = targets.sortedBy { !it.primary }
        .filter { t -> t.lats.isNotEmpty() && fromGaps.none { it.segmentId == t.segmentId } }
        .map { focusExercise(sessionId, it, learning) }
    return (fromGaps + fromFocus).take(MaxExercises)
}

/**
 * The corners whose brake-to-throttle gap the driver explained (FBBD-419): at each corner, the latest
 * answer to the question after a brake-to-throttle exercise there, when it was "on purpose" or "the car
 * was moving". A later "traffic", "not sure" or no answer brings the corner back.
 */
private fun explainedGaps(earlier: List<SessionFocusRecord>): Set<String> {
    val latest = HashMap<String, String?>()
    for (r in earlier.sortedWith(compareBy({ it.sessionStartUtcMillis }, { it.sessionId }))) {
        val outcome = r.exerciseOutcome ?: continue
        val e = r.exercises.firstOrNull { it.id == outcome.exerciseId }?.takeIf { it.kind == ExerciseBrakeToThrottle }
        if (e != null) latest[e.corner] = outcome.answer
    }
    return latest.filterValues { it == AnswerOnPurpose || it == AnswerCarMoving }.keys
}

/** The medians of [section]'s counted laps, for a frozen baseline. */
private fun sectionBaseline(section: CornerSection?) = Triple(
    section?.seconds?.values?.toList()?.takeIf { it.isNotEmpty() }?.let(::median),
    section?.downstreamSeconds?.values?.toList()?.takeIf { it.isNotEmpty() }?.let(::median),
    section?.seconds?.size ?: 0,
)

private fun gapExercise(
    sessionId: String,
    o: GapObservation,
    line: CornerPick,
    learning: SessionLearning,
    identityRevision: Int?,
): ExerciseProposal {
    val gaps = learning.gaps
    val (section, downstream, laps) = sectionBaseline(learning.section(o.segmentId))
    // Better: as short as at his other corners, never asked below what the detector counts as a gap.
    val target = ceil(max(o.othersMedianSeconds, gaps.thresholds.gapSeconds) * Tenths) / Tenths
    return ExerciseProposal(
        id = idOf(sessionId, ExerciseBrakeToThrottle, o.segmentId),
        kind = ExerciseBrakeToThrottle,
        segmentId = o.segmentId,
        cornerId = line.cornerId,
        identityRevision = line.identityRevision ?: identityRevision.takeIf { line.cornerId != null },
        where = o.name,
        seen = o.text,
        practise = "Go from the brake straight to the throttle: no pause off both pedals.",
        better = "Off both pedals for ${fixed(target, 1)} s or less on most laps (now ${fixed(o.medianSeconds, 1)} s).",
        baseline = ExerciseBaseline(
            algorithm = gaps.algorithm,
            usablePasses = o.usablePasses,
            passesWithGap = o.passesWithGap,
            lapNumbers = o.lapNumbers,
            medianSeconds = o.medianSeconds,
            othersMedianSeconds = o.othersMedianSeconds,
            targetSeconds = target,
            gapSeconds = gaps.thresholds.gapSeconds,
            validated = gaps.validated,
            sectionSeconds = section,
            downstreamSeconds = downstream,
            sectionLaps = laps,
        ),
        lats = line.lats,
        lons = line.lons,
    )
}

private fun focusExercise(sessionId: String, t: FocusTarget, learning: SessionLearning?): ExerciseProposal {
    val left = t.timeAvailableSeconds
    val (section, downstream, laps) = sectionBaseline(learning?.section(t.segmentId))
    return ExerciseProposal(
        id = idOf(sessionId, ExerciseFocus, t.segmentId),
        kind = ExerciseFocus,
        segmentId = t.segmentId,
        cornerId = t.cornerId,
        identityRevision = t.identityRevision,
        where = t.name,
        seen = t.observation,
        practise = t.what,
        better = if (left != null) {
            "A typical pass closer to your best than ${fixed(left, 2)} s."
        } else {
            "A typical pass closer to your best."
        },
        baseline = ExerciseBaseline(
            algorithm = ExerciseAlgorithm,
            medianSeconds = left,
            sectionSeconds = section,
            downstreamSeconds = downstream,
            sectionLaps = laps,
        ),
        lats = t.lats,
        lons = t.lons,
    )
}

private fun idOf(sessionId: String, kind: String, segmentId: String) = "$sessionId/$kind/$segmentId"

private const val Tenths = 10.0
