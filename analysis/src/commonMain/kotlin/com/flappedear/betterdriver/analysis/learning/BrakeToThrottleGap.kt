package com.flappedear.betterdriver.analysis.learning

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceMeasured
import com.flappedear.betterdriver.analysis.driving.DrivingState
import com.flappedear.betterdriver.analysis.driving.DrivingStateInterval
import com.flappedear.betterdriver.analysis.driving.LapDrivingStates
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import com.flappedear.betterdriver.format.fixed

// Learning coach, phase 1 (FBBD-417, epic FBBD-413): the every-lap brake-to-throttle gap. For each
// corner and counted lap, the time from the moment the brake is let off to the moment the
// accelerator is pressed again: the car rolls on neither pedal in between. Read from the lap's
// driving states (FBBD-175) only where both pedals were measured (on the FR-S: the CAN brake,
// frame 0xD1, and accelerator pedal, frame 0x140; in RaceChrono's export brake_pos and the
// accelerator or throttle). Without them the detector is off and says why: the acceleration alone
// cannot tell a lift from a part throttle.
//
// A corner is flagged when the gap is there on most of its usable passes and is clearly longer than
// at the track's other corners in the same session. It needs no better pass to compare with: a gap
// on every lap is still found. It is an observation, not a diagnosis: on the FR-S's 6-speed
// automatic a late throttle can be a choice (no kickdown mid-corner, a car still rotating), so the
// text says what was observed and claims no cause.
//
// The thresholds are first heuristics, not yet checked against laps where the gap was closed and
// time gained (FBBD-420 validates them on track data).

const val BrakeToThrottleGapAlgorithm = "brake-to-throttle-gap-v1"

/** "Most passes", the default: two in three. */
const val MostPassesShare = 2.0 / 3.0

/** Why the detector found nothing for the whole session. */
const val GapUnavailableNoSegments = "noCornerSegments"
const val GapUnavailableNotMeasured = "pedalsNotMeasured"

/** Why the session's corners could not be compared with each other. */
const val GapComparisonTooFewCorners = "tooFewCornersToCompare"

/** Why one pass through a corner gives no gap. */
const val GapExcludedLapNotCounted = "lapNotCounted"
const val GapExcludedNoStates = "noDrivingStates"
const val GapExcludedNotMeasured = "pedalsNotMeasured"
const val GapExcludedNoBraking = "noBraking"
const val GapExcludedNoThrottle = "noThrottleInWindow"
const val GapExcludedDataGap = "pedalDataGap"

/** Why a corner gives no comparison. */
const val GapCornerAcrossStartLine = "acrossStartLine"
const val GapCornerTooFewPasses = "tooFewUsablePasses"

/**
 * Thresholds of [brakeToThrottleGaps]. Initial heuristics, unvalidated (FBBD-417): FBBD-420 checks
 * them on track data before any cue relies on them.
 */
data class GapThresholds(
    /** The brake for a corner may be let off this far before its entry, metres. */
    val approachMeters: Double = 150.0,
    /** The accelerator may come back this far after the corner's exit, metres. */
    val exitMeters: Double = 60.0,
    /** A hole in the states longer than this between release and throttle drops the pass, seconds. */
    val maxHoleSeconds: Double = 0.3,
    /** A pass with a gap at least this long has the gap, seconds. */
    val gapSeconds: Double = 0.3,
    /** "Most passes": at least this share of the usable ones. */
    val mostShare: Double = MostPassesShare,
    /** A corner needs this many usable passes to be judged. */
    val minPasses: Int = 4,
    /** And this many other judged corners to be compared with. */
    val minOtherCorners: Int = 2,
    /** "Clearly longer": the corner's median gap at least this many times the others' ... */
    val longerFactor: Double = 2.0,
    /** ... and at least this much longer, seconds. */
    val longerSeconds: Double = 0.25,
)

/** What one pass through a corner gives: a [GapPass] or a [GapExclusion]. */
sealed interface GapPassOutcome

/**
 * One pass's gap on lap [lapNumber]: the brake let off at [releaseTime] ([releaseMeters] along the
 * axis), the accelerator pressed at [throttleTime] ([throttleMeters]), [gapSeconds] apart.
 */
data class GapPass(
    val lapNumber: Int,
    val gapSeconds: Double,
    val releaseTime: Double,
    val throttleTime: Double,
    val releaseMeters: Double,
    val throttleMeters: Double,
) : GapPassOutcome

/** A pass on lap [lapNumber] that gives no gap, and why ([reason], one of the GapExcluded constants). */
data class GapExclusion(val lapNumber: Int, val reason: String) : GapPassOutcome

/**
 * The gap through corner [segmentId] ([name]) over the session: its usable [passes] and the
 * [excluded] ones with their reasons, the median and interquartile range of the gaps, how many
 * passes had a gap of at least [GapThresholds.gapSeconds], and whether it is [flagged].
 * [unavailableReason] is set when the corner is not judged.
 */
data class CornerGap(
    val segmentId: String,
    val name: String,
    val passes: List<GapPass>,
    val excluded: List<GapExclusion>,
    val medianSeconds: Double?,
    val spreadSeconds: Double?,
    val passesWithGap: Int,
    val flagged: Boolean = false,
    val unavailableReason: String = "",
)

/**
 * A flagged corner as the learning coach says it: [medianSeconds] off both pedals between brake and
 * throttle on [passesWithGap] of [usablePasses] passes, against [othersMedianSeconds] at the
 * session's other judged corners. [text] says it was observed and claims no cause.
 */
data class GapObservation(
    val segmentId: String,
    val name: String,
    val medianSeconds: Double,
    val spreadSeconds: Double?,
    val othersMedianSeconds: Double,
    val passesWithGap: Int,
    val usablePasses: Int,
    val lapNumbers: List<Int>,
    val text: String,
)

/**
 * The detector's result for one session. [unavailableReason] is set when it is off for the whole
 * session (no corners, pedals not measured); [comparisonReason] when corners were measured but too
 * few to compare with each other. [validated] is false: the thresholds are not yet proven.
 */
data class BrakeToThrottleGaps(
    val corners: List<CornerGap> = emptyList(),
    val observations: List<GapObservation> = emptyList(),
    val unavailableReason: String = "",
    val comparisonReason: String = "",
    val thresholds: GapThresholds = GapThresholds(),
    val algorithm: String = BrakeToThrottleGapAlgorithm,
    val validated: Boolean = false,
) {
    val available: Boolean get() = unavailableReason.isEmpty()
}

/**
 * The brake-to-throttle gap at every corner of [analysis]'s session, from its driving states, and
 * the corners where it is on most laps and clearly longer than elsewhere.
 */
@Suppress("ReturnCount")
fun brakeToThrottleGaps(analysis: SessionAnalysis, thresholds: GapThresholds = GapThresholds()): BrakeToThrottleGaps {
    val corners = analysis.segments?.segments.orEmpty().filter { it.type == TrackSegmentType.Corner }
    if (corners.isEmpty()) {
        return BrakeToThrottleGaps(unavailableReason = GapUnavailableNoSegments, thresholds = thresholds)
    }
    val laps = analysis.laps.timedLaps
    val measured = laps.indices.any { i ->
        laps[i].referenceEligible && analysis.drivingStates.getOrNull(i)?.isMeasured() == true
    }
    if (!measured) return BrakeToThrottleGaps(unavailableReason = GapUnavailableNotMeasured, thresholds = thresholds)
    val measuredCorners = corners.map { cornerGap(analysis, it, corners, thresholds) }
    val judged = measuredCorners.filter { it.unavailableReason.isEmpty() }
    if (judged.size < thresholds.minOtherCorners + 1) {
        return BrakeToThrottleGaps(
            measuredCorners, comparisonReason = GapComparisonTooFewCorners, thresholds = thresholds,
        )
    }
    val observations = mutableListOf<GapObservation>()
    val result = measuredCorners.map { c ->
        if (c.unavailableReason.isNotEmpty()) return@map c
        val others = judged.filter { it.segmentId != c.segmentId }.mapNotNull { it.medianSeconds }
        val baseline = median(others)
        val median = c.medianSeconds ?: return@map c
        val most = c.passesWithGap >= thresholds.mostShare * c.passes.size
        val longer = median >= thresholds.gapSeconds &&
            median >= baseline * thresholds.longerFactor &&
            median >= baseline + thresholds.longerSeconds
        if (!(most && longer)) return@map c
        observations += GapObservation(
            c.segmentId, c.name, median, c.spreadSeconds, baseline, c.passesWithGap, c.passes.size,
            c.passes.map { it.lapNumber }, observationText(c.name, median, c.passesWithGap, c.passes.size, baseline),
        )
        c.copy(flagged = true)
    }
    return BrakeToThrottleGaps(result, observations.sortedByDescending { it.medianSeconds }, thresholds = thresholds)
}

/**
 * The observation in words, for example: "Observed in Corner 4: 0.8 s off both pedals between
 * brake release and throttle on 7 of 8 counted laps (the other corners: 0.2 s)."
 */
fun observationText(name: String, medianSeconds: Double, withGap: Int, usable: Int, othersSeconds: Double): String =
    "Observed in $name: ${fixed(medianSeconds, 1)} s off both pedals between brake release and throttle " +
        "on $withGap of $usable counted laps (the other corners: ${fixed(othersSeconds, 1)} s)."

/**
 * The result as lines of the drive's log: a summary (or why the detector is off), then one line per
 * judged or flagged corner. "gap.summary corners=9 judged=7 flagged=1 algorithm=... validated=false".
 */
fun BrakeToThrottleGaps.logLines(): List<String> {
    if (!available) return listOf("gap.off reason=$unavailableReason algorithm=$algorithm")
    val judged = corners.count { it.unavailableReason.isEmpty() }
    val summary = "gap.summary corners=${corners.size} judged=$judged flagged=${observations.size}" +
        (if (comparisonReason.isNotEmpty()) " comparison=$comparisonReason" else "") +
        " algorithm=$algorithm validated=$validated"
    return listOf(summary) + corners.map { c ->
        val reasons = c.excluded.groupingBy { it.reason }.eachCount().entries
            .joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "-" }
        val median = c.medianSeconds?.let { fixed(it, 2) } ?: "-"
        val state = when {
            c.flagged -> "FLAG"
            c.unavailableReason.isNotEmpty() -> c.unavailableReason
            else -> "ok"
        }
        "gap.corner id=${c.segmentId} $state median=$median used=${c.passes.size} " +
            "with_gap=${c.passesWithGap} excluded=$reasons"
    }
}

private fun LapDrivingStates.isMeasured(): Boolean = valid && provenance == BrakingProvenanceMeasured

/** The gap of every counted lap through [corner]; [corners] are all of the session's, for attribution. */
@Suppress("ReturnCount")
private fun cornerGap(
    analysis: SessionAnalysis,
    corner: TrackSegment,
    corners: List<TrackSegment>,
    t: GapThresholds,
): CornerGap {
    if (corner.endProgressMeters < corner.startProgressMeters) {
        return CornerGap(
            corner.id, corner.name, emptyList(), emptyList(), null, null, 0,
            unavailableReason = GapCornerAcrossStartLine,
        )
    }
    val passes = mutableListOf<GapPass>()
    val excluded = mutableListOf<GapExclusion>()
    analysis.laps.timedLaps.forEachIndexed { i, lap ->
        val states = analysis.drivingStates.getOrNull(i)
        val pass: GapPassOutcome = when {
            !lap.referenceEligible -> GapExclusion(lap.number, GapExcludedLapNotCounted)
            states == null || !states.valid -> GapExclusion(lap.number, GapExcludedNoStates)
            states.provenance != BrakingProvenanceMeasured -> GapExclusion(lap.number, GapExcludedNotMeasured)
            else -> lapGap(lap.number, states.intervals, corner, corners, t)
        }
        when (pass) {
            is GapPass -> passes += pass
            is GapExclusion -> excluded += pass
        }
    }
    val gaps = passes.map { it.gapSeconds }
    if (passes.size < t.minPasses) {
        return CornerGap(
            corner.id, corner.name, passes, excluded, median(gaps).takeIf { gaps.isNotEmpty() }, null,
            gaps.count { it >= t.gapSeconds }, unavailableReason = GapCornerTooFewPasses,
        )
    }
    val summary = summarizeConsistency(gaps, minimumSamples = t.minPasses)
    return CornerGap(
        corner.id, corner.name, passes, excluded, summary.median ?: median(gaps), summary.interquartileRange,
        gaps.count { it >= t.gapSeconds },
    )
}

/**
 * One brake press of a lap: [release] the index of its last braking stretch, [throttle] the index of
 * the first stretch on the accelerator after it (null when the lap ends first). Presses with only
 * coasting between them are one press: the gap runs from the last release.
 */
private class Press(val release: Int, val throttle: Int?)

private fun presses(intervals: List<DrivingStateInterval>): List<Press> {
    val presses = mutableListOf<Press>()
    var release: Int? = null
    for (k in intervals.indices) {
        when (intervals[k].state) {
            DrivingState.Braking -> release = k
            DrivingState.Coasting -> Unit
            else -> release?.let {
                presses += Press(it, k)
                release = null
            }
        }
    }
    release?.let { presses += Press(it, null) }
    return presses
}

/**
 * The corner a brake let off at [meters] belongs to: the one it is let off in, else the next one
 * ahead within [GapThresholds.approachMeters]. A corner across the start line takes none.
 */
private fun cornerOf(meters: Double, corners: List<TrackSegment>, t: GapThresholds): TrackSegment? {
    val forward = corners.filter { it.endProgressMeters >= it.startProgressMeters }
    return forward.firstOrNull { meters >= it.startProgressMeters && meters <= it.endProgressMeters }
        ?: forward.filter { it.startProgressMeters > meters && it.startProgressMeters - meters <= t.approachMeters }
            .minByOrNull { it.startProgressMeters }
}

/**
 * The gap of one lap through [corner]: from the last brake let off that belongs to it to the first
 * accelerator after that (a part throttle through the bend counts), which must come before the
 * corner's exit plus [GapThresholds.exitMeters].
 */
@Suppress("ReturnCount")
private fun lapGap(
    lapNumber: Int,
    intervals: List<DrivingStateInterval>,
    corner: TrackSegment,
    corners: List<TrackSegment>,
    t: GapThresholds,
): GapPassOutcome {
    val mine = presses(intervals).filter { cornerOf(intervals[it.release].endMeters, corners, t)?.id == corner.id }
    val press = mine.lastOrNull() ?: return GapExclusion(lapNumber, GapExcludedNoBraking)
    val r = press.release
    val k = press.throttle
    if (k == null || intervals[k].startMeters > corner.endProgressMeters + t.exitMeters) {
        return GapExclusion(lapNumber, GapExcludedNoThrottle)
    }
    for (j in r until k) {
        if (intervals[j + 1].startTime - intervals[j].endTime > t.maxHoleSeconds) {
            return GapExclusion(lapNumber, GapExcludedDataGap)
        }
    }
    val off = intervals[r]
    val on = intervals[k]
    return GapPass(lapNumber, on.startTime - off.endTime, off.endTime, on.startTime, off.endMeters, on.startMeters)
}

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}
