package com.flappedear.betterdriver.analysis.coaching

import com.flappedear.betterdriver.analysis.confidence.ConsistentLaps
import com.flappedear.betterdriver.analysis.confidence.MinimumSupportingLaps
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceInferred
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceMeasured
import com.flappedear.betterdriver.analysis.data.normalizedSpeedUnit
import com.flappedear.betterdriver.analysis.data.sameSpeedUnit
import com.flappedear.betterdriver.analysis.focus.FocusChangeBrakingMeters
import com.flappedear.betterdriver.analysis.focus.FocusChangeSpeedFraction
import com.flappedear.betterdriver.analysis.focus.FocusMetric
import com.flappedear.betterdriver.analysis.focus.FocusMinimumLossSeconds
import com.flappedear.betterdriver.analysis.focus.median
import com.flappedear.betterdriver.recorder.Confidence
import kotlin.math.abs

// Coaching effectiveness (FBBD-180): Analyse -> Opportunity -> Target -> Cue -> Attempt -> Result ->
// Learned/Pending. A session's next-session focus (FBBD-20) is the target; the next session at the
// track is the attempt (the one whose coach worked on it, or else the first after it); its result is
// measured against the passes of the session that made the target, at the same CornerId. Every later
// session checks whether an improvement held. Low-confidence passes are never counted, and a number
// is said only when every pass it rests on is graded High and its channel is measured in a known unit.

/** A corner improved when its median time fell at least this much: less is GPS timing noise. */
const val ImprovedSeconds = FocusMinimumLossSeconds

/** An improvement held when at least this share of it is still there. */
const val PersistedShare = 2.0 / 3.0

/** An improvement was lost when at most this share of it is still there. */
const val RevertedShare = 1.0 / 3.0

/** What came of one recommendation in the session that worked on it. */
enum class FocusVerdict {
    /** The track's corners were revised between the two sessions: nothing to compare. */
    NotComparable,

    /** No pass through the corner that can be counted. */
    NotDriven,

    /** Fewer than [ConsistentLaps] counted passes before or after: too early to tell. */
    TooFewPasses,

    /** Driven enough, but the median corner time did not fall by [ImprovedSeconds]. */
    NoGain,

    /** The median corner time fell by [ImprovedSeconds] at least. */
    Improved,
}

/** Whether an improvement held in a later session. */
enum class Persistence {
    Persisted,
    Partial,
    Reverted,
}

/**
 * How one thing the driver does at a corner moved between two sessions: the median of the counted
 * passes [before] and [after], in [unit] (metres from the corner's start for a braking point, so
 * later is more). [direction] is the way the recommendation asked for. Its number may be said only
 * when [precise]: a measured channel in a known unit, every pass it rests on graded High.
 */
data class MetricShift(
    val metric: FocusMetric,
    val before: Double,
    val after: Double,
    val unit: String,
    val direction: Int,
    val precise: Boolean,
    /** For a braking point, `measured` or `inferred`: never the two mixed. */
    val provenance: String = "",
) {
    val delta: Double get() = after - before

    /** Whether it moved enough to be a change: [FocusChangeBrakingMeters], or [FocusChangeSpeedFraction] of a speed. */
    val moved: Boolean
        get() = when (metric) {
            FocusMetric.BrakingPoint -> abs(delta) >= FocusChangeBrakingMeters
            else -> abs(delta) >= FocusChangeSpeedFraction * abs(before) && delta != 0.0
        }

    /** Whether it moved the way the recommendation asked. */
    val wanted: Boolean get() = moved && (delta > 0) == (direction > 0)
}

/**
 * What came of recommendation [ask], made after session [source], in session [session] (the
 * attempt): whether the coach worked on it ([coached]) and how many cues it gave there ([cues]); the
 * counted passes there ([passes]) and how many were faster than the median before ([fasterPasses]);
 * the median corner time before and after; how the driving changed ([shifts], for what was asked);
 * and whether the corner is still among the session's top three losses ([topThree], null when its
 * evidence was too weak to rank). [timePrecise]: every pass behind the two medians graded High.
 */
data class FocusAttempt(
    val ask: FocusAsk,
    val source: SessionCoachingRecord,
    val session: SessionCoachingRecord,
    val verdict: FocusVerdict,
    val coached: Boolean = false,
    val cues: Int = 0,
    val passes: Int = 0,
    val fasterPasses: Int = 0,
    val medianBefore: Double? = null,
    val medianAfter: Double? = null,
    val timePrecise: Boolean = false,
    val shifts: List<MetricShift> = emptyList(),
    val topThree: Boolean? = null,
) {
    /** How much faster the median pass got, seconds; null when not measured. */
    val gainSeconds: Double?
        get() = if (medianBefore != null && medianAfter != null) medianBefore - medianAfter else null

    /** Whether it worked on most counted passes, not once: more than half, and two at least. */
    val repeated: Boolean get() = fasterPasses >= MinimumSupportingLaps && fasterPasses * 2 > passes

    /** Whether the driving changed the way the recommendation asked, in every way that could be measured. */
    val behaviourChanged: Boolean get() = shifts.isNotEmpty() && shifts.all { it.wanted }
}

/**
 * Whether the improvement of [attempt] held in the later session [session]: its median corner time
 * against the one before the recommendation ([gainSeconds], positive when still faster), the
 * changes in the driving that came with it, now ([shifts]), and the verdict.
 */
data class FocusCheck(
    val attempt: FocusAttempt,
    val session: SessionCoachingRecord,
    val gainSeconds: Double,
    val timePrecise: Boolean,
    val shifts: List<MetricShift>,
    val persistence: Persistence,
)

/**
 * The coaching history as one session shows it: what came of the focus it worked on ([attempts],
 * the primary first), and whether earlier improvements at other corners held in it ([checks]).
 */
data class SessionCoaching(
    val attempts: List<FocusAttempt> = emptyList(),
    val checks: List<FocusCheck> = emptyList(),
) {
    val empty: Boolean get() = attempts.isEmpty() && checks.isEmpty()
}

/**
 * The coaching history of session [sessionId] from the [records] of its track. The focus it worked
 * on is that of the session its coach worked from ([SessionCoachingRecord.coachedFrom]); without
 * the coach, that of the latest session before it with one, as the summary showed it. A session
 * whose record is gone has none.
 */
fun coachingHistory(sessionId: String, records: List<SessionCoachingRecord>): SessionCoaching {
    val s = records.firstOrNull { it.sessionId == sessionId } ?: return SessionCoaching()
    val sorted = records.filter { it.trackId == s.trackId }.distinctBy { it.sessionId }.sortedWith(SessionOrder)
    val at = sorted.indexOfFirst { it.sessionId == sessionId }
    val attempts = attemptsIn(sorted, at)
    val worked = attempts.map { it.ask.cornerId }.toSet()
    // The latest improvement at each other corner before this session, checked in it.
    // Both the attempt and the session it worked from come before this one: with the clock set back, a
    // coached session can sort before its source, and its attempt is then not this session's past.
    val before = sorted.subList(0, at).map { it.sessionId }.toSet()
    val latest = LinkedHashMap<String, FocusAttempt>()
    for (i in 0 until at) {
        attemptsIn(sorted, i).filter { it.verdict == FocusVerdict.Improved && it.source.sessionId in before }
            .forEach { latest[it.ask.cornerId] = it }
    }
    val checks = latest.values.filter { it.ask.cornerId !in worked }.mapNotNull { check(it, s) }
        .sortedWith(
            compareByDescending<FocusCheck> { it.attempt.session.sessionStartUtcMillis }.thenBy { it.attempt.ask.name },
        )
    return SessionCoaching(attempts, checks)
}

/** What came of the focus that session [at] of [sorted] worked on. */
private fun attemptsIn(sorted: List<SessionCoachingRecord>, at: Int): List<FocusAttempt> {
    val s = sorted[at]
    val from = s.coachedFrom
    // The coach's event names the session it worked from: found wherever the clock put it (a phone
    // clock set back sorts it after this one). Without the coach, the latest before it.
    val source = if (from != null) {
        sorted.firstOrNull { it.sessionId == from && it.sessionId != s.sessionId }
    } else {
        sorted.subList(0, at).lastOrNull { it.focus.isNotEmpty() }
    } ?: return emptyList()
    return source.focus.sortedBy { !it.primary }.map { attempt(it, source, s) }
}

private fun counted(corner: CornerRecord?): List<CornerPassRecord> =
    corner?.passes.orEmpty().filter { it.level != Confidence.LOW }

private fun allHigh(passes: List<CornerPassRecord>) = passes.all { it.level == Confidence.HIGH }

@Suppress("ReturnCount") // One early answer for each way there is nothing to measure.
private fun attempt(ask: FocusAsk, source: SessionCoachingRecord, s: SessionCoachingRecord): FocusAttempt {
    val before = source.corner(ask.cornerId)
    val after = s.corner(ask.cornerId)
    val coached = s.coachedFrom == source.sessionId
    val base = FocusAttempt(ask, source, s, FocusVerdict.NotComparable, coached, after?.cues ?: 0)
    if (before == null || source.identityRevision != s.identityRevision) return base
    val b = counted(before)
    val a = counted(after)
    if (a.isEmpty()) return base.copy(verdict = FocusVerdict.NotDriven)
    if (after == null || b.size < ConsistentLaps || a.size < ConsistentLaps) {
        return base.copy(verdict = FocusVerdict.TooFewPasses, passes = a.size)
    }
    val medianBefore = median(b.map { it.seconds })
    val medianAfter = median(a.map { it.seconds })
    return base.copy(
        verdict = if (medianBefore - medianAfter >= ImprovedSeconds) FocusVerdict.Improved else FocusVerdict.NoGain,
        passes = a.size,
        fasterPasses = a.count { it.seconds < medianBefore },
        medianBefore = medianBefore,
        medianAfter = medianAfter,
        timePrecise = allHigh(b + a),
        shifts = ask.changes.mapNotNull { c ->
            FocusMetric.entries.firstOrNull { it.code == c.metric }?.let { shift(it, c.direction, before, after) }
        },
        topThree = if (after.withheld) null else after.focusRank != null,
    )
}

/** Whether [attempt]'s improvement held in [s]; null when [s] cannot tell (too few passes, corners revised). */
@Suppress("ReturnCount") // One early answer for each way there is nothing to check.
private fun check(attempt: FocusAttempt, s: SessionCoachingRecord): FocusCheck? {
    val source = attempt.source
    val gained = attempt.gainSeconds ?: return null
    val before = source.corner(attempt.ask.cornerId) ?: return null
    val now = s.corner(attempt.ask.cornerId) ?: return null
    if (s.identityRevision != source.identityRevision) return null
    val b = counted(before)
    val n = counted(now)
    if (b.size < ConsistentLaps || n.size < ConsistentLaps) return null
    val gain = median(b.map { it.seconds }) - median(n.map { it.seconds })
    // Each change that came with the improvement, measured the same way now.
    val pairs = attempt.shifts.filter { it.wanted }.mapNotNull { then ->
        shift(then.metric, then.direction, before, now, then.provenance.ifEmpty { null })?.let { then to it }
    }
    val shares = listOf(gain / gained) + pairs.map { (then, now) -> now.delta / then.delta }
    val persistence = when {
        shares.all { it >= PersistedShare } -> Persistence.Persisted
        shares.all { it <= RevertedShare } -> Persistence.Reverted
        else -> Persistence.Partial
    }
    return FocusCheck(attempt, s, gain, allHigh(b + n), pairs.map { it.second }, persistence)
}

/**
 * How [metric] moved at a corner from [before] to [after] (the same corner in two sessions), over
 * their counted passes; null when either has fewer than [MinimumSupportingLaps] with a value, or
 * the speeds are not in one unit. A braking point compares measured points when both sessions have
 * them, else inferred ones ([only] when given), never the two mixed.
 */
private fun shift(
    metric: FocusMetric,
    direction: Int,
    before: CornerRecord,
    after: CornerRecord,
    only: String? = null,
): MetricShift? = if (metric == FocusMetric.BrakingPoint) {
    val ways = listOfNotNull(only).ifEmpty { listOf(BrakingProvenanceMeasured, BrakingProvenanceInferred) }
    ways.firstNotNullOfOrNull { brakingShift(it, direction, before, after) }
} else {
    speedShift(metric, direction, before, after)
}

/** How the braking point found by [provenance] moved, as [shift] says. */
private fun brakingShift(provenance: String, direction: Int, before: CornerRecord, after: CornerRecord): MetricShift? {
    fun points(c: CornerRecord) = counted(c).filter { it.brakingMeters != null && it.brakingProvenance == provenance }
    val pb = points(before)
    val pa = points(after)
    if (pb.size < MinimumSupportingLaps || pa.size < MinimumSupportingLaps) return null
    val measured = provenance == BrakingProvenanceMeasured && before.brakeMeasured && after.brakeMeasured
    return MetricShift(
        FocusMetric.BrakingPoint,
        median(pb.mapNotNull { it.brakingMeters }),
        median(pa.mapNotNull { it.brakingMeters }),
        "m",
        direction, measured && allHigh(pb + pa), provenance,
    )
}

/** How the minimum or exit speed moved, as [shift] says: in one unit, its number only when measured in a known one. */
private fun speedShift(metric: FocusMetric, direction: Int, before: CornerRecord, after: CornerRecord): MetricShift? {
    val value: (CornerPassRecord) -> Double? =
        if (metric == FocusMetric.MinimumSpeed) CornerPassRecord::minimumSpeed else CornerPassRecord::exitSpeed
    val unit = before.speedUnit
    val pb = counted(before).filter { value(it) != null }
    val pa = counted(after).filter { value(it) != null }
    val comparable = unit.isNotBlank() && sameSpeedUnit(unit, after.speedUnit) &&
        pb.size >= MinimumSupportingLaps && pa.size >= MinimumSupportingLaps
    if (!comparable) return null
    val precise = normalizedSpeedUnit(unit).isNotEmpty() && before.speedMeasured && after.speedMeasured &&
        allHigh(pb + pa)
    return MetricShift(
        metric, median(pb.mapNotNull(value)), median(pa.mapNotNull(value)), unit.trim(), direction, precise,
    )
}
