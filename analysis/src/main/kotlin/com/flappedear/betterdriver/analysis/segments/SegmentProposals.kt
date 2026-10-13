@file:Suppress(
    "MagicNumber",
    "ReturnCount",
    "CyclomaticComplexMethod",
    "LongMethod",
    "NestedBlockDepth",
    "ComplexCondition",
    "LoopWithTooManyJumpStatements",
) // Ported as written.

package com.flappedear.betterdriver.analysis.segments

import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.TrackFeatures
import kotlin.math.abs
import kotlin.math.min

// Port of FlappedEar Telemetry packages/telemetry_core/lib/src/analysis/track_segment_proposals.dart
// (Apache 2.0), itself a port of FlappedEar Overlays' TrackSegmentProposals: corners and straights
// proposed from a progress axis's smoothed curvature.

/** Most segments a lap is split into (Telemetry's maximumTrackSegments). */
const val MaximumTrackSegments = 64

/** Most GPS gaps [proposeTrackSegments] accepts. */
const val MaximumProposalGpsGaps = 4096

/** Why a proposal boundary is uncertain. */
enum class BoundaryUncertainty(val jsonName: String) {
    ConnectedCorners("connectedCorners"),
    ShortStraight("shortStraight"),
    GpsGap("gpsGap"),
}

/** Why a lap could not be split into proposals. */
enum class UnresolvedProposal(val jsonName: String) {
    ContinuousCorner("continuousCorner"),
    NoCorners("noCorners"),
    TooManySegments("tooManySegments"),
}

enum class TrackSegmentType(val jsonName: String) { Sector("sector"), Corner("corner"), Straight("straight") }

/** A progress interval on an axis; [endMeters] < [startMeters] wraps across the gate. */
data class ProgressRange(val startMeters: Double, val endMeters: Double)

/** Thresholds of [proposeTrackSegments]. */
data class SegmentProposalOptions(
    /** |curvature| at or above this is turning. */
    val cornerCurvaturePerMeter: Double = 1.0 / 250.0,
    /** Smaller turning runs are kinks, kept in the straight. */
    val minimumCornerTurnRadians: Double = 0.35,
    /** Shorter straights are not proposed; the corners around them join. */
    val connectedStraightMeters: Double = 20.0,
    /** Shorter straights get uncertain boundaries. */
    val certainStraightMeters: Double = 40.0,
    /**
     * Whether a chain of corners with no proposed straight between them is divided into its
     * single corners; the boundary between them is then uncertain.
     */
    val splitCornerChains: Boolean = false,
    /**
     * A turning run carries on while |curvature| stays at or above this share of
     * [cornerCurvaturePerMeter] (FBBD-215): a corner whose curvature dips to the threshold on the
     * way out is not split or cut short by the noise of the dip, and its end is where it
     * straightens. 1 (Telemetry's) has no hysteresis.
     */
    val exitCurvatureShare: Double = 1.0,
)

/** One end of a proposal: [toleranceMeters] is the smoothing radius plus the axis spacing. */
data class SegmentProposalBoundary(
    val progressMeters: Double,
    val toleranceMeters: Double,
    val uncertaintyReasons: List<BoundaryUncertainty> = emptyList(),
) {
    val certain: Boolean get() = uncertaintyReasons.isEmpty()
}

/** A proposed corner or straight. `end.progressMeters < start.progressMeters` wraps the gate. */
data class TrackSegmentProposal(
    val type: TrackSegmentType,
    val name: String,
    val start: SegmentProposalBoundary,
    val end: SegmentProposalBoundary,
    val lengthMeters: Double = 0.0,
    /** Signed total heading change; positive turns left. */
    val turnRadians: Double = 0.0,
    /** Signed curvature with the largest magnitude. */
    val peakCurvaturePerMeter: Double = 0.0,
    /** Corners joined into this proposal (0 for a straight). */
    val chainedCorners: Int = 0,
)

/** The result of [proposeTrackSegments]; only the last proposal may wrap. */
data class TrackSegmentProposals(
    val proposals: List<TrackSegmentProposal> = emptyList(),
    /** Set (with no proposals) when the geometry cannot be split without inventing boundaries. */
    val unresolvedReason: UnresolvedProposal? = null,
    val options: SegmentProposalOptions = SegmentProposalOptions(),
    val valid: Boolean = false,
)

private class Run(val label: Int, val first: Int, var count: Int)

private class Piece(val run: Run, val start: MutableBoundary)

private class MutableBoundary(var progressMeters: Double, val toleranceMeters: Double) {
    val reasons = mutableListOf<BoundaryUncertainty>()

    fun copy() = MutableBoundary(progressMeters, toleranceMeters).also { it.reasons += reasons }

    fun add(reason: BoundaryUncertainty) {
        if (reason !in reasons) reasons += reason
    }

    fun frozen() = SegmentProposalBoundary(progressMeters, toleranceMeters, reasons.toList())
}

/** Empty when every sample has the same label (no boundary exists). */
private fun circularRuns(labels: IntArray): List<Run> {
    val n = labels.size
    var startIndex = -1
    for (i in 0 until n) {
        if (labels[i] != labels[(i - 1 + n) % n]) {
            startIndex = i
            break
        }
    }
    val runs = mutableListOf<Run>()
    if (startIndex < 0) return runs
    var index = startIndex
    var walked = 0
    while (walked < n) {
        val run = Run(labels[index], index, 0)
        while (walked < n && labels[index] == run.label) {
            ++run.count
            index = (index + 1) % n
            ++walked
        }
        runs += run
    }
    return runs
}

/** Runs from the first sample to the last, for an open axis; empty when every sample has the same label. */
private fun linearRuns(labels: IntArray): List<Run> {
    val runs = mutableListOf<Run>()
    for (i in labels.indices) {
        if (runs.isEmpty() || labels[i] != runs.last().label) runs += Run(labels[i], i, 1) else ++runs.last().count
    }
    return if (runs.size < 2) emptyList() else runs
}

/**
 * Grows every turning run of [labels] over its neighbours whose |[curvature]| (the same way) is at
 * least [exit], forwards and backwards, round the loop when [closed].
 */
private fun extendTurns(labels: IntArray, curvature: List<Double>, exit: Double, closed: Boolean) {
    val n = labels.size
    fun turning(i: Int, from: Int): Boolean {
        val sign = labels[from]
        return labels[i] == 0 && sign != 0 && abs(curvature[i]) >= exit && (curvature[i] > 0.0) == (sign > 0)
    }
    // Twice round a loop, so a run reaching across the start grows on both sides of it.
    val passes = if (closed) 2 * n else n
    for (k in 1 until passes) {
        val i = k % n
        if (closed || k < n) if (turning(i, (i - 1 + n) % n)) labels[i] = labels[(i - 1 + n) % n]
    }
    for (k in passes - 2 downTo 0) {
        val i = k % n
        val next = (i + 1) % n
        if ((closed || i + 1 < n) && turning(i, next)) labels[i] = labels[next]
    }
}

private fun circularDistance(a: Double, b: Double, length: Double): Double {
    val direct = abs(a - b)
    return min(direct, length - direct)
}

private fun nearRange(progress: Double, range: ProgressRange, tolerance: Double, length: Double): Boolean {
    val inside = if (range.startMeters <= range.endMeters) {
        progress >= range.startMeters && progress <= range.endMeters
    } else {
        progress >= range.startMeters || progress <= range.endMeters
    }
    return inside ||
        circularDistance(progress, range.startMeters, length) <= tolerance ||
        circularDistance(progress, range.endMeters, length) <= tolerance
}

private fun validOptions(options: SegmentProposalOptions): Boolean {
    fun positive(value: Double) = value.isFinite() && value > 0.0
    return positive(options.cornerCurvaturePerMeter) &&
        positive(options.minimumCornerTurnRadians) &&
        positive(options.connectedStraightMeters) &&
        positive(options.certainStraightMeters) &&
        options.certainStraightMeters >= options.connectedStraightMeters &&
        positive(options.exitCurvatureShare) &&
        options.exitCurvatureShare <= 1.0
}

/**
 * Classifies [axis]'s smoothed curvature into alternating corner and straight proposals.
 * [features] must come from `computeTrackFeatures(axis, ...)`. [gpsGaps] are progress ranges
 * where the analysed lap lacks GPS coverage; boundaries within tolerance of a gap are uncertain.
 * Returns `valid == false` for invalid inputs. On an open axis (a point-to-point run, FBBD-162)
 * the proposals go from its start to its finish, and none wraps.
 */
fun proposeTrackSegments(
    axis: ProgressAxis,
    features: TrackFeatures,
    gpsGaps: List<ProgressRange> = emptyList(),
    options: SegmentProposalOptions = SegmentProposalOptions(),
): TrackSegmentProposals {
    val invalid = TrackSegmentProposals(options = options)
    if (!axis.valid ||
        !features.valid ||
        axis.points.size < 4 ||
        features.samples.size != axis.points.size ||
        !(axis.spacingMeters > 0.0) ||
        !axis.lengthMeters.isFinite() ||
        axis.lengthMeters <= 0.0 ||
        !validOptions(options) ||
        gpsGaps.size > MaximumProposalGpsGaps
    ) {
        return invalid
    }
    val length = axis.lengthMeters
    for (gap in gpsGaps) {
        if (!gap.startMeters.isFinite() || !gap.endMeters.isFinite() ||
            gap.startMeters < 0.0 || gap.endMeters < 0.0 ||
            gap.startMeters > length || gap.endMeters > length
        ) {
            return invalid
        }
    }
    fun unresolved(reason: UnresolvedProposal) =
        TrackSegmentProposals(options = options, unresolvedReason = reason, valid = true)

    val samples = features.samples
    val n = samples.size
    val spacing = axis.spacingMeters
    val labels = IntArray(n)
    for (i in 0 until n) {
        val curvature = samples[i].curvaturePerMeter
        if (!curvature.isFinite()) return invalid
        if (abs(curvature) >= options.cornerCurvaturePerMeter) labels[i] = if (curvature > 0.0) 1 else -1
    }
    if (options.exitCurvatureShare < 1.0) {
        val exit = options.cornerCurvaturePerMeter * options.exitCurvatureShare
        extendTurns(labels, samples.map { it.curvaturePerMeter }, exit, axis.closed)
    }
    fun turnOf(run: Run): Double {
        var turn = 0.0
        for (k in 0 until run.count) turn += samples[(run.first + k) % n].curvaturePerMeter * spacing
        return turn
    }
    fun unsplittable() =
        unresolved(if (labels.first() == 0) UnresolvedProposal.NoCorners else UnresolvedProposal.ContinuousCorner)

    fun runsOf(labels: IntArray) = if (axis.closed) circularRuns(labels) else linearRuns(labels)
    var runs = runsOf(labels)
    if (runs.isEmpty()) return unsplittable()
    // A turning run too small to be a corner is a kink: fold it into the straight.
    for (run in runs) {
        if (run.label != 0 && abs(turnOf(run)) < options.minimumCornerTurnRadians) {
            for (k in 0 until run.count) labels[(run.first + k) % n] = 0
        }
    }
    runs = runsOf(labels)
    if (runs.isEmpty()) return unsplittable()

    val tolerance = features.smoothingMeters + spacing
    val pieces = runs.map {
        Piece(Run(it.label, it.first, it.count), MutableBoundary(samples[it.first].progressMeters, tolerance))
    }
    val m = pieces.size
    for (k in 0 until m) {
        val run = pieces[k].run
        val straightLength = run.count * spacing
        if (run.label == 0 &&
            straightLength >= options.connectedStraightMeters &&
            straightLength < options.certainStraightMeters
        ) {
            pieces[k].start.add(BoundaryUncertainty.ShortStraight)
            if (axis.closed || k + 1 < m) pieces[(k + 1) % m].start.add(BoundaryUncertainty.ShortStraight)
        }
    }

    // A straight too short to stand on its own is not proposed. Corners with no proposed straight
    // between them form one corner chain.
    var anchor = -1
    for (k in 0 until m) {
        val run = pieces[k].run
        if (run.label == 0 && run.count * spacing >= options.connectedStraightMeters) {
            anchor = k
            break
        }
    }
    if (anchor < 0) return unresolved(UnresolvedProposal.ContinuousCorner)
    // An open axis is walked from its start, so no chain joins its finish to its start.
    if (!axis.closed) anchor = 0
    val kept = mutableListOf<Piece>()
    val chained = mutableListOf<Int>()
    for (step in 0 until m) {
        val piece = pieces[(anchor + step) % m]
        val straightPiece = piece.run.label == 0
        val proposedStraight = straightPiece && piece.run.count * spacing >= options.connectedStraightMeters
        val first = kept.singleOrNull()?.run
        if (!axis.closed && !straightPiece && first != null && first.label == 0 &&
            first.count * spacing < options.connectedStraightMeters
        ) {
            // A run's opening straight too short to propose belongs to its first corner.
            kept[0] = Piece(Run(piece.run.label, first.first, first.count + piece.run.count), kept[0].start)
            chained[0] = 1
            continue
        }
        if (proposedStraight || kept.isEmpty() || kept.last().run.label == 0) {
            // A copy: the chain below extends the kept run, never the piece's own.
            kept += Piece(Run(piece.run.label, piece.run.first, piece.run.count), piece.start.copy())
            chained += if (straightPiece) 0 else 1
            continue
        }
        if (options.splitCornerChains && !straightPiece) {
            // The next corner of the chain stands on its own; the stretch before it stays with
            // the corner it follows.
            val start = piece.start.copy()
            start.add(BoundaryUncertainty.ConnectedCorners)
            kept += Piece(Run(piece.run.label, piece.run.first, piece.run.count), start)
            chained += 1
            continue
        }
        // Extend the current chain (its label stays non-zero: it is a corner).
        kept.last().run.count += piece.run.count
        if (!straightPiece) chained[chained.size - 1] = chained.last() + 1
    }
    if (kept.size < 2) return unresolved(UnresolvedProposal.ContinuousCorner)
    if (kept.size > MaximumTrackSegments) return unresolved(UnresolvedProposal.TooManySegments)

    for (piece in kept) {
        if (gpsGaps.any { nearRange(piece.start.progressMeters, it, tolerance, length) }) {
            piece.start.add(BoundaryUncertainty.GpsGap)
        }
    }

    // Order by start progress so only the final proposal can wrap the gate.
    var rotation = 0
    for (k in 1 until kept.size) {
        if (kept[k].start.progressMeters < kept[rotation].start.progressMeters) rotation = k
    }
    val ordered = kept.subList(rotation, kept.size) + kept.subList(0, rotation)
    val orderedChained = chained.subList(rotation, chained.size) + chained.subList(0, rotation)

    val proposals = mutableListOf<TrackSegmentProposal>()
    val count = ordered.size
    var cornerNumber = 0
    var straightNumber = 0
    for (k in 0 until count) {
        val piece = ordered[k]
        val start = piece.start.copy()
        val end = ordered[(k + 1) % count].start.copy()
        // The loop closes at progress 0 only when the first boundary sits exactly on the gate.
        if (k == count - 1 && end.progressMeters == 0.0) end.progressMeters = length
        val span = end.progressMeters - start.progressMeters
        val lengthMeters = if (span > 0.0) span else span + length
        if (piece.run.label == 0) {
            proposals += TrackSegmentProposal(
                TrackSegmentType.Straight, "Straight ${++straightNumber}", start.frozen(), end.frozen(), lengthMeters,
            )
            continue
        }
        val links = orderedChained[k]
        val firstCorner = cornerNumber + 1
        cornerNumber += links
        var peak = 0.0
        for (j in 0 until piece.run.count) {
            val curvature = samples[(piece.run.first + j) % n].curvaturePerMeter
            if (abs(curvature) > abs(peak)) peak = curvature
        }
        proposals += TrackSegmentProposal(
            type = TrackSegmentType.Corner,
            name = if (links > 1) "Corners $firstCorner–$cornerNumber" else "Corner $firstCorner",
            start = start.frozen(),
            end = end.frozen(),
            lengthMeters = lengthMeters,
            turnRadians = turnOf(piece.run),
            peakCurvaturePerMeter = peak,
            chainedCorners = links,
        )
    }
    return TrackSegmentProposals(proposals, null, options, true)
}
