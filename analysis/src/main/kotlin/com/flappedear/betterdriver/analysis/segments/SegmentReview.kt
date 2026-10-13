@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList")

package com.flappedear.betterdriver.analysis.segments

import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.geoMidpoint
import com.flappedear.betterdriver.analysis.geo.hypot
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapTrace
import com.flappedear.betterdriver.analysis.laps.LapTracePoint
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.TrackFeatures
import com.flappedear.betterdriver.analysis.progress.computeTrackFeatures
import com.flappedear.betterdriver.analysis.progress.projectLapTrace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// Port of FlappedEar Telemetry analysis/automatic_segments.dart and the constants of
// track_segment_review.dart (Apache 2.0): one lap's segment proposals, its coverage gaps and the
// line-consensus check that guards adopting them automatically.

/** Curvature smoothing for segment proposals, metres. */
const val SegmentReviewSmoothingMeters = 6.0

/** Shorter coverage gaps are ignored. */
const val SegmentReviewMinimumGapMeters = 15.0

/** A lap's line farther than this from most other laps somewhere is not trusted for segments. */
const val SegmentConsensusMaximumMeters = 30.0

/** Fewer other laps than this leave the line unchecked. */
const val SegmentConsensusMinimumLaps = 2

/** At most this many other laps, spread over the session, are compared. */
const val SegmentConsensusMaximumLaps = 8

/** The line is checked every this many metres along the axis. */
const val SegmentConsensusStepMeters = 5.0

/** An approved corner, straight or sector on a progress axis; `end < start` wraps the gate. */
data class TrackSegment(
    val id: String,
    val name: String,
    val type: TrackSegmentType,
    val startProgressMeters: Double,
    val endProgressMeters: Double,
)

/**
 * Segments every lap of a population is timed against. [revision] and
 * [trackConfigurationReference] identify them; results for others are never mixed.
 */
data class ApprovedSegments(
    val segments: List<TrackSegment>,
    val revision: String,
    val trackConfigurationReference: String = "",
)

/**
 * Progress ranges [trace] (one lap projected onto an axis of [length]) does not cover, at least
 * [SegmentReviewMinimumGapMeters] long, from progress 0 to [length].
 */
fun coverageGaps(trace: List<ProgressSegment>, length: Double): List<ProgressRange> {
    val covered = trace.filter { it.samples.isNotEmpty() }
        .map { ProgressRange(it.samples.first().progressMeters, it.samples.last().progressMeters) }
        .sortedBy { it.startMeters }
    val gaps = mutableListOf<ProgressRange>()
    var reached = 0.0
    fun addGap(start: Double, end: Double) {
        if (end - start >= SegmentReviewMinimumGapMeters && gaps.size < MaximumProposalGpsGaps) {
            gaps += ProgressRange(start, end)
        }
    }
    for (range in covered) {
        addGap(reached, range.startMeters)
        reached = max(reached, range.endMeters)
    }
    addGap(reached, length)
    return gaps
}

/** How well a lap's line agrees with the other eligible laps. */
data class LineConsensus(
    val checkedLaps: Int = 0,
    /** The largest, along the lap, of the distance within which at least half the others pass. */
    val worstDistanceMeters: Double = 0.0,
    val worstProgressMeters: Double = 0.0,
) {
    val checked: Boolean get() = checkedLaps >= SegmentConsensusMinimumLaps

    /** Whether somewhere more than half of the other laps are farther than the limit. */
    val disagrees: Boolean get() = checked && !(worstDistanceMeters <= SegmentConsensusMaximumMeters)
}

private class Line(val points: List<LapTracePoint>) {
    private class Box(
        val first: Int,
        val last: Int,
        val minE: Double,
        val maxE: Double,
        val minN: Double,
        val maxN: Double,
    )

    private val boxes = mutableListOf<Box>()

    init {
        var first = 0
        while (first + 1 < points.size) {
            val last = min(first + 32, points.size - 1)
            var minE = Double.POSITIVE_INFINITY
            var maxE = Double.NEGATIVE_INFINITY
            var minN = Double.POSITIVE_INFINITY
            var maxN = Double.NEGATIVE_INFINITY
            for (i in first..last) {
                minE = min(minE, points[i].eastMeters)
                maxE = max(maxE, points[i].eastMeters)
                minN = min(minN, points[i].northMeters)
                maxN = max(maxN, points[i].northMeters)
            }
            boxes += Box(first, last, minE, maxE, minN, maxN)
            first += 32
        }
    }

    fun distanceTo(point: MetricPoint): Double {
        val px = point.eastMeters
        val py = point.northMeters
        if (points.size == 1) return hypot(px - points.first().eastMeters, py - points.first().northMeters)
        var best = Double.POSITIVE_INFINITY
        for (box in boxes) {
            val dx = if (px < box.minE) box.minE - px else if (px > box.maxE) px - box.maxE else 0.0
            val dy = if (py < box.minN) box.minN - py else if (py > box.maxN) py - box.maxN else 0.0
            if (hypot(dx, dy) >= best) continue
            for (i in box.first until box.last) {
                val a = points[i]
                val b = points[i + 1]
                val abx = b.eastMeters - a.eastMeters
                val aby = b.northMeters - a.northMeters
                val apx = px - a.eastMeters
                val apy = py - a.northMeters
                val length2 = abx * abx + aby * aby
                val t = if (length2 > 0.0) ((apx * abx + apy * aby) / length2).coerceIn(0.0, 1.0) else 0.0
                val distance = hypot(apx - t * abx, apy - t * aby)
                if (distance < best) best = distance
            }
        }
        return best
    }
}

/**
 * How well [axis] agrees with [others], the other eligible laps' traces around the same origin:
 * every [SegmentConsensusStepMeters] along the axis, the distance within which at least half of
 * them pass.
 */
fun lineConsensus(axis: ProgressAxis, others: List<LapTrace>): LineConsensus {
    val usable = others.filter { it.points.isNotEmpty() }
    if (!axis.valid || usable.size < SegmentConsensusMinimumLaps) return LineConsensus()
    val chosen = if (usable.size <= SegmentConsensusMaximumLaps) {
        usable
    } else {
        List(SegmentConsensusMaximumLaps) { usable[it * (usable.size - 1) / (SegmentConsensusMaximumLaps - 1)] }
    }
    val lines = chosen.map { Line(it.points) }
    val spacing = if (axis.spacingMeters > 0.0) axis.spacingMeters else SegmentConsensusStepMeters
    val stride = max(1L, (SegmentConsensusStepMeters / spacing).roundToLong()).toInt()
    var worst = 0.0
    var worstAt = 0.0
    val distances = DoubleArray(lines.size)
    var index = 0
    while (index < axis.points.size) {
        val point = axis.points[index]
        for (lap in lines.indices) distances[lap] = lines[lap].distanceTo(point)
        distances.sort()
        val majority = distances[(distances.size - 1) / 2]
        if (!(majority <= worst)) {
            worst = majority
            worstAt = if (index < axis.cumulative.size) axis.cumulative[index] else 0.0
        }
        index += stride
    }
    return LineConsensus(lines.size, worst, worstAt)
}

/** Why a lap has no segment proposals. */
enum class SegmentReviewUnavailable { NoTrace, NoAxis, Unresolved, Invalid }

/** The segment proposals of one lap, or why there are none. */
data class SegmentReview(
    val axis: ProgressAxis = ProgressAxis(),
    val features: TrackFeatures = TrackFeatures(),
    val lapTrace: List<ProgressSegment> = emptyList(),
    val gaps: List<ProgressRange> = emptyList(),
    val proposals: TrackSegmentProposals = TrackSegmentProposals(),
    val unavailable: SegmentReviewUnavailable? = null,
)

/**
 * Segment proposals for timed lap [lapNumber] of [session] ([startTime] to [endTime]), with
 * [laps] derived from [session]: the axis is built from the lap's own trace around the start
 * gate's midpoint, features use [SegmentReviewSmoothingMeters], and the lap's coverage gaps mark
 * uncertain boundaries. With [splitCornerChains], a lap that would split into too many segments
 * keeps its chains, and [approved] segments from earlier get the proposals matching more of them.
 * [robustness] is how the axis is built and its proposals made: Telemetry's by default, the app's
 * in [com.flappedear.betterdriver.analysis.analyzeSession] (FBBD-215).
 */
fun computeSegmentReview(
    session: TelemetrySession,
    laps: LapSession,
    lapNumber: Int,
    startTime: Double,
    endTime: Double,
    splitCornerChains: Boolean = true,
    approved: List<TrackSegment> = emptyList(),
    robustness: AxisRobustness = AxisRobustness.Telemetry,
): SegmentReview {
    val review = segmentReview(session, laps, lapNumber, startTime, endTime, splitCornerChains, robustness)
    val tooMany = review.proposals.unresolvedReason == UnresolvedProposal.TooManySegments
    if (!splitCornerChains || (approved.isEmpty() && !tooMany)) return review
    val chained = segmentReview(session, laps, lapNumber, startTime, endTime, false, robustness)
    if (tooMany) return chained
    val split = matches(review, approved)
    val kept = matches(chained, approved)
    if (kept != split) return if (kept > split) chained else review
    return if (chained.proposals.proposals.size == approved.size &&
        review.proposals.proposals.size != approved.size
    ) {
        chained
    } else {
        review
    }
}

private fun matches(review: SegmentReview, approved: List<TrackSegment>): Int = approved.count { segment ->
    review.proposals.proposals.any {
        it.type == segment.type &&
            abs(it.start.progressMeters - segment.startProgressMeters) <= 5.0 &&
            abs(it.end.progressMeters - segment.endProgressMeters) <= 5.0
    }
}

private fun segmentReview(
    session: TelemetrySession,
    laps: LapSession,
    lapNumber: Int,
    startTime: Double,
    endTime: Double,
    splitCornerChains: Boolean,
    robustness: AxisRobustness,
): SegmentReview {
    val gate = laps.startGate
    val trace = laps.lapTraces.firstOrNull { it.lapNumber == lapNumber }
    if (gate == null || trace == null) return SegmentReview(unavailable = SegmentReviewUnavailable.NoTrace)
    val origin = geoMidpoint(gate.endpointA, gate.endpointB)
    val axis = buildRobustAxis(trace, origin, gate, closed = laps.finishGate == null, robustness)
    val features = if (axis.valid) {
        computeTrackFeatures(smoothedAxis(axis, robustness.lineSmoothingMeters), SegmentReviewSmoothingMeters)
    } else {
        TrackFeatures()
    }
    if (!features.valid) return SegmentReview(axis = axis, unavailable = SegmentReviewUnavailable.NoAxis)
    val lapTrace = projectLapTrace(axis, session, startTime, endTime, robustness.movementBaselineMeters)
    val gaps = coverageGaps(lapTrace, axis.lengthMeters)
    val proposals = proposeTrackSegments(
        axis,
        features,
        gaps,
        SegmentProposalOptions(splitCornerChains = splitCornerChains, exitCurvatureShare = robustness.cornerExitShare),
    )
    val unavailable = when {
        !proposals.valid -> SegmentReviewUnavailable.Invalid
        proposals.unresolvedReason != null -> SegmentReviewUnavailable.Unresolved
        else -> null
    }
    return SegmentReview(axis, features, lapTrace, gaps, proposals, unavailable)
}
