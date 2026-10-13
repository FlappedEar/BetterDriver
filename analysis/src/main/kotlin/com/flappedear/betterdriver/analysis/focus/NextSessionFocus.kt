package com.flappedear.betterdriver.analysis.focus

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.confidence.ConsistentLaps
import com.flappedear.betterdriver.analysis.confidence.Graded
import com.flappedear.betterdriver.analysis.confidence.MinimumSupportingLaps
import com.flappedear.betterdriver.analysis.confidence.segmentSeconds
import com.flappedear.betterdriver.analysis.confidence.usableLaps
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.Confidence
import kotlin.math.max

// What to work on next session (FBBD-20): one primary focus and at most two secondary ones, picked
// from the confidence-gated focus areas (FBBD-177) of the session's corners. Each carries its
// evidence: the time a typical pass leaves against what the driver has already done there on two
// laps, and how the faster passes differ from the others (braking point, minimum speed, exit speed).
// The plan is what the next session's coach works on first at the track, and what is kept per
// track and session under the corner's CornerId (FBBD-172) for the coaching history (FBBD-180).

const val NextSessionFocusAlgorithm = "next-session-focus-v1"

/** At most this many secondary focuses beside the primary one. */
const val MaxSecondaryFocus = 2

/** A secondary focus leaves at least this share of the primary's time; less is not worth the driver's attention. */
const val SecondaryShareOfPrimary = 0.25

/** A braking point difference smaller than this is not a change to make. */
const val FocusChangeBrakingMeters = 5.0

/** A speed difference smaller than this share of the speed is not a change to make. */
const val FocusChangeSpeedFraction = 0.03

/** Primary or secondary. */
enum class FocusRole(val code: String) {
    Primary("primary"),
    Secondary("secondary"),
}

/** What a focus asks to change at its corner. */
enum class FocusMetric(val code: String) {
    BrakingPoint("brakingPoint"),
    MinimumSpeed("minimumSpeed"),
    ExitSpeed("exitSpeed"),
}

/**
 * How the faster passes through a corner differ from the others in one [metric]: the median of the
 * [faster] passes against the median of the [other] passes. A braking point is metres along the lap,
 * so a positive [delta] brakes later; a speed is in [unit], so a positive [delta] is faster.
 * [measured] is false for a braking point inferred from the speed (no brake signal) and for speeds
 * not measured (calculated from positions); such a change is said without its number.
 * [fasterCount] and [otherCount] are how many passes of each group had a value for [metric]
 * ([MinimumSupportingLaps] each at least); 0 when not known. Its number is said only when it is
 * [precise] and the focus is High confidence.
 */
data class FocusChange(
    val metric: FocusMetric,
    val other: Double,
    val faster: Double,
    val unit: String,
    val measured: Boolean = true,
    val fasterCount: Int = 0,
    val otherCount: Int = 0,
    /** The lowest confidence of the passes the change uses, in its corner (FBBD-177). */
    val confidence: Confidence = Confidence.HIGH,
) {
    val delta: Double get() = faster - other

    /** Whether its number may be said: measured, in a known unit, from passes all graded High. */
    val precise: Boolean get() = measured && confidence == Confidence.HIGH && unit.isNotBlank()
}

/**
 * One corner to work on next session. [timeAvailableSeconds]: the larger of what a typical pass
 * (the median of the usable laps) leaves against the second fastest usable pass, a time the driver
 * has done on two laps at least ([cornerTimeAvailable]), and the time the focus area measured (the
 * best lap's gap, or the median repeated loss); null when neither is known.
 * [changes] compare the [fasterLaps] fastest usable passes with the [otherLaps] others.
 * [what] says it in words: with numbers at High confidence, without below.
 */
data class NextFocus(
    val role: FocusRole,
    val area: FocusArea,
    val confidence: Graded,
    val timeAvailableSeconds: Double?,
    /**
     * How far [timeAvailableSeconds] can be trusted: [confidence], and below it the lowest of the
     * passes it was worked out from (FBBD-177). Its number is said only at High.
     */
    val timeConfidence: Confidence,
    val changes: List<FocusChange>,
    val fasterLaps: Int,
    val otherLaps: Int,
    val what: String,
) {
    val segmentId: String get() = area.segmentId
    val name: String get() = area.name
}

/**
 * What to work on next session: [primary] and at most [MaxSecondaryFocus] [secondary] focuses, or
 * none when no corner is clear enough. [withheld] are the corners left out for weak evidence.
 * [identityRevision] is the track's corner identity revision the segment ids are CornerIds of, null
 * when the session's corners are not the track's.
 */
data class NextSessionPlan(
    val primary: NextFocus? = null,
    val secondary: List<NextFocus> = emptyList(),
    val withheld: List<WithheldFocus> = emptyList(),
    val identityRevision: Int? = null,
) {
    /** The primary focus, then the secondary ones. */
    val focuses: List<NextFocus> get() = listOfNotNull(primary) + secondary
}

/**
 * The next-session plan of [analysis]. Candidates are the corners among [sessionFocus]'s areas (Low
 * confidence is never one, FBBD-177); straights are left out, as the coach works on corners. They
 * rank High confidence before Medium, then by time available, then by the area's own score. The
 * first is the primary focus. A secondary focus needs a time available of at least
 * [FocusMinimumLossSeconds] and [SecondaryShareOfPrimary] of the primary's (or, with no time to
 * show, High confidence): the driver is never handed every loss.
 */
fun nextSessionPlan(analysis: SessionAnalysis, lapLabel: (Any?) -> String = { "lap $it" }): NextSessionPlan {
    val found = sessionFocus(analysis, lapLabel, maximum = Int.MAX_VALUE)
    val corners = analysis.segments?.segments.orEmpty()
        .filter { it.type == TrackSegmentType.Corner }.map { it.id }.toSet()
    val candidates = found.areas.filter { it.area.segmentId in corners }.map { graded ->
        val level = graded.confidence.level
        val own = secondsOf(graded.area)
        val corner = cornerTime(analysis, graded.area.segmentId)
        if (corner != null && (own == null || corner.first > own)) {
            Candidate(graded, corner.first, minOf(level, corner.second))
        } else {
            Candidate(graded, own, level)
        }
    }.sortedWith(
        compareByDescending<Candidate> { it.graded.confidence.level }
            .thenByDescending { it.seconds ?: 0.0 }
            .thenByDescending { it.graded.area.score }
            .thenBy { it.graded.area.segmentId },
    )
    val revision = analysis.identity?.number?.takeIf { analysis.identified }
    val first = candidates.firstOrNull()
        ?: return NextSessionPlan(withheld = found.withheld, identityRevision = revision)
    val primarySeconds = first.seconds ?: 0.0
    val secondary = candidates.drop(1).filter { c ->
        val s = c.seconds
        if (s == null) {
            c.graded.confidence.level == Confidence.HIGH
        } else {
            s >= FocusMinimumLossSeconds && s >= SecondaryShareOfPrimary * primarySeconds
        }
    }.take(MaxSecondaryFocus)
    return NextSessionPlan(
        primary = focusOf(analysis, first, FocusRole.Primary),
        secondary = secondary.map { focusOf(analysis, it, FocusRole.Secondary) },
        withheld = found.withheld,
        identityRevision = revision,
    )
}

private class Candidate(val graded: GradedFocusArea, val seconds: Double?, val level: Confidence)

/** The area's own value when it is a time. */
private fun secondsOf(area: FocusArea): Double? = area.value.takeIf { area.unit == "s" && it.isFinite() }

private fun focusOf(analysis: SessionAnalysis, c: Candidate, role: FocusRole): NextFocus {
    val graded = c.graded
    val id = graded.area.segmentId
    val groups = passGroups(analysis, id)
    val changes = groups?.let { cornerChanges(analysis, id, it) }.orEmpty()
    val what = groups?.let { changeText(changes, it.faster.size, it.other.size, graded.confidence.level) }.orEmpty()
    return NextFocus(
        role = role,
        area = graded.area,
        confidence = graded.confidence,
        timeAvailableSeconds = c.seconds,
        timeConfidence = c.level,
        changes = changes,
        fasterLaps = groups?.faster?.size ?: 0,
        otherLaps = groups?.other?.size ?: 0,
        what = what,
    )
}

/**
 * What a typical pass through [segmentId] leaves against the second fastest usable pass, seconds;
 * null with fewer than [ConsistentLaps] usable laps timed there.
 */
fun cornerTimeAvailable(analysis: SessionAnalysis, segmentId: String): Double? = cornerTime(analysis, segmentId)?.first

/** [cornerTimeAvailable], and the lowest confidence of the passes it is worked out from. */
private fun cornerTime(analysis: SessionAnalysis, segmentId: String): Pair<Double, Confidence>? {
    val timed = analysis.usableLaps(segmentId)
        .mapNotNull { i -> analysis.segmentSeconds(segmentId, i)?.takeIf { it.isFinite() }?.let { i to it } }
    if (timed.size < ConsistentLaps) return null
    val times = timed.map { it.second }.sorted()
    val level = Confidence.lowest(timed.map { analysis.confidence.lap(segmentId, it.first).level })
    return max(0.0, median(times) - times[MinimumSupportingLaps - 1]) to level
}
