package com.flappedear.betterdriver.analysis.segments

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.TimingGate
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.hypot
import com.flappedear.betterdriver.analysis.laps.LapTrace
import com.flappedear.betterdriver.analysis.laps.LapTracePoint
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.ProjectedSample
import com.flappedear.betterdriver.analysis.progress.buildProgressAxis
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

// Segmentation robustness (FBBD-215). Corners are where the reference lap's curvature reaches
// 1/250 per metre, and curvature is the second derivative of position: on the lap's raw fixes,
// position noise of a few decimetres (GNSS noise, or the 0.4 m steps of a latitude stored as float32)
// is many times that threshold over the 6 m Telemetry's features smooth over. Corners then split,
// merge, appear and vanish with the precision, the sample rate or a missing fix, and sector times
// with them. The app builds, segments and reads its axis so that the corners and times it finds are
// the track's and the driver's, not the recording's.

/**
 * How the progress axis is built and read. [Telemetry] is FlappedEar Telemetry's own (the parity
 * tests), [App] the app's (FBBD-215):
 *
 * - [traceSmoothingMeters]: the axis lap's path is smoothed ([smoothedLapTrace]) before the axis is
 *   built from it. Its tangents then follow the track, not single fixes, so another lap's noisy fix
 *   is not refused for a bend that is not there, and its length is the lap's, not the zigzag's.
 * - [lineSmoothingMeters]: segment proposals are made on the axis's line smoothed again
 *   ([smoothedAxis]), so 0.3 m of noise per fix is well under the curvature a corner starts at.
 * - [cornerExitShare]: a corner carries on until its curvature falls below this share of the
 *   threshold it starts at ([SegmentProposalOptions.exitCurvatureShare]), so a dip to the threshold
 *   on the way out neither splits it nor cuts it short.
 * - [movementBaselineMeters]: a fix is checked against the axis direction by its movement over at
 *   least this far ([com.flappedear.betterdriver.analysis.progress.projectLapTrace]).
 * - [progressWindowSeconds]: each lap's progress is read from the fixes within this long of each fix
 *   ([steadiedTrace]), so a sector boundary is crossed when they say, not when one noisy fix does.
 *
 * The values are the smallest that keep the corners, their ids, the theoretical best and the focus
 * of the real recordings and the made-up circuits within SegmentationRobustnessTest's bounds.
 */
data class AxisRobustness(
    val traceSmoothingMeters: Double = 0.0,
    val lineSmoothingMeters: Double = 0.0,
    val cornerExitShare: Double = 1.0,
    val movementBaselineMeters: Double = 0.0,
    val progressWindowSeconds: Double = 0.0,
) {
    companion object {
        val Telemetry = AxisRobustness()
        val App = AxisRobustness(
            traceSmoothingMeters = 4.0,
            lineSmoothingMeters = 16.0,
            cornerExitShare = 0.6,
            movementBaselineMeters = 3.0,
            progressWindowSeconds = 1.0,
        )
    }
}

/** The axis of [trace] as [robustness] builds it: from the path smoothed first, when it says so. */
fun buildRobustAxis(
    trace: LapTrace,
    origin: GeoCoordinate,
    gate: TimingGate,
    closed: Boolean,
    robustness: AxisRobustness,
): ProgressAxis =
    buildProgressAxis(smoothedLapTrace(trace, robustness.traceSmoothingMeters, closed), origin, gate, closed)

/** A Gaussian kernel reaches this many sigmas each side. */
private const val KernelSigmas = 3.0

/**
 * [trace] with each point moved to the Gaussian-weighted mean ([sigmaMeters]) of its neighbours along
 * the path, out to the first one more than three sigmas from it in a straight line; times kept.
 * Straight-line distance, not distance along the path: a slow car's fixes, closer together than
 * their noise, zigzag, and their path is several times longer than the road. On a [closed] lap the
 * path wraps from its end back to its start; on a run it does not, and its first and last points
 * (the gate crossings) stay where they are. [trace] itself when [sigmaMeters]
 * is not positive.
 */
fun smoothedLapTrace(trace: LapTrace, sigmaMeters: Double, closed: Boolean): LapTrace {
    val points = trace.points
    val n = points.size
    if (n < MinimumWindowFixes || !(sigmaMeters > 0.0)) return trace
    val reach = sigmaMeters * KernelSigmas
    // At most half way round a loop each way, so no point is counted twice.
    val steps = if (closed) (n - 1) / 2 else n - 1
    val smoothed = List(n) { i ->
        val p = points[i]
        // A run's ends are its start and finish gate crossings: kept where they are, or a one-sided
        // average would pull both inside the gates and shorten the run's axis.
        if (!closed && (i == 0 || i == n - 1)) return@List p
        var east = p.eastMeters
        var north = p.northMeters
        var total = 1.0
        for (direction in intArrayOf(-1, 1)) {
            var step = 1
            var q = neighbour(points, i + direction, closed)
            while (step <= steps && q != null && q.distanceTo(p) <= reach) {
                val w = gaussian(q.distanceTo(p) / sigmaMeters)
                east += q.eastMeters * w
                north += q.northMeters * w
                total += w
                step++
                q = neighbour(points, i + direction * step, closed)
            }
        }
        LapTracePoint(p.telemetryTime, east / total, north / total)
    }
    return trace.copy(points = smoothed)
}

/** The point at [index], wrapped round a [closed] lap; null past an open run's ends. */
private fun neighbour(points: List<LapTracePoint>, index: Int, closed: Boolean): LapTracePoint? = when {
    closed -> points[((index % points.size) + points.size) % points.size]
    index < 0 || index >= points.size -> null
    else -> points[index]
}

private fun LapTracePoint.distanceTo(other: LapTracePoint): Double =
    hypot(eastMeters - other.eastMeters, northMeters - other.northMeters)

/** A Gaussian's weight [sigmas] sigmas from its centre, relative to the centre's. */
private fun gaussian(sigmas: Double): Double = exp(-sigmas * sigmas / 2)

/**
 * [axis] with its points smoothed along it by a Gaussian of [sigmaMeters] (wrapping round a loop; at
 * an open axis's ends, over the points there are), keeping its progress metres and spacing, so
 * features and proposals made on it are on [axis]'s own progress. [axis] itself when it is invalid
 * or [sigmaMeters] is not positive.
 */
fun smoothedAxis(axis: ProgressAxis, sigmaMeters: Double): ProgressAxis {
    val n = axis.points.size
    val smoothable = axis.valid && n >= MinimumAxisPoints && axis.spacingMeters > 0.0
    if (!smoothable || !(sigmaMeters > 0.0)) return axis
    val sigmaPoints = sigmaMeters / axis.spacingMeters
    val reach = (sigmaPoints * KernelSigmas).roundToInt().coerceIn(1, (n - 1) / 2)
    val weights = DoubleArray(reach + 1) { gaussian(it / sigmaPoints) }
    val points = List(n) { i ->
        var east = 0.0
        var north = 0.0
        var total = 0.0
        for (offset in -reach..reach) {
            val j = i + offset
            val k = when {
                axis.closed -> ((j % n) + n) % n
                j < 0 || j >= n -> continue
                else -> j
            }
            val w = weights[abs(offset)]
            east += axis.points[k].eastMeters * w
            north += axis.points[k].northMeters * w
            total += w
        }
        MetricPoint(east / total, north / total)
    }
    return ProgressAxis(
        points = points,
        cumulative = axis.cumulative,
        lengthMeters = axis.lengthMeters,
        spacingMeters = axis.spacingMeters,
        origin = axis.origin,
        valid = true,
        closed = axis.closed,
    )
}

/** Fewer axis points than this are not smoothed. */
private const val MinimumAxisPoints = 4

/** Fewer fixes than this in a window leave a fix's progress as projected. */
private const val MinimumWindowFixes = 3

/** Below this spread of covered distance (squared, per fix) the window has not moved. */
private const val MinimumCoveredVariance = 1e-4

/**
 * [trace] (one lap projected onto an axis) with each fix's progress read from the fixes within
 * [halfWindowSeconds] of it (FBBD-215): progress fitted, by least squares, as a straight line in the
 * distance the lap's own speed channel has covered by then, and read at the fix. Speed is measured
 * from the Doppler shift, so it is far steadier than position: a timing line is crossed when the
 * fixes around it say, not when one noisy fix does, and the fit needs no assumption about braking or
 * acceleration, nor the speed's unit. Progress stays non-decreasing in each piece. A piece without
 * speed throughout, and a fix whose window does not move, keep their progress.
 */
fun steadiedTrace(
    trace: List<ProgressSegment>,
    session: TelemetrySession,
    halfWindowSeconds: Double,
): List<ProgressSegment> {
    if (!(halfWindowSeconds > 0.0)) return trace
    return trace.map { piece -> steadied(piece, session, halfWindowSeconds) }
}

private fun steadied(piece: ProgressSegment, session: TelemetrySession, halfWindow: Double): ProgressSegment {
    val samples = piece.samples
    val n = samples.size
    val speeds = samples.mapNotNull { session.valueAt(Aliases.SPEED, it.telemetryTime) }
    if (n < MinimumWindowFixes || speeds.size < n) return piece
    // Distance by the speed channel since the piece's first fix, in the channel's own unit times seconds.
    val covered = DoubleArray(n)
    for (i in 1 until n) {
        val dt = samples[i].telemetryTime - samples[i - 1].telemetryTime
        covered[i] = covered[i - 1] + (speeds[i] + speeds[i - 1]) / 2 * dt
    }
    val out = ProgressSegment()
    var from = 0
    var to = 0
    for (i in 0 until n) {
        val t = samples[i].telemetryTime
        while (samples[from].telemetryTime < t - halfWindow) from++
        while (to + 1 < n && samples[to + 1].telemetryTime <= t + halfWindow) to++
        var progress = fitted(samples, covered, from, to, i) ?: samples[i].progressMeters
        val previous = out.samples.lastOrNull()?.progressMeters
        if (previous != null && progress < previous) progress = previous
        out.samples += ProjectedSample(samples[i].telemetryTime, progress, samples[i].valid)
    }
    return out
}

/**
 * Progress at fix [i] from the straight line through fixes [from]..[to] in [covered]; null when it
 * cannot be fitted.
 */
private fun fitted(samples: List<ProjectedSample>, covered: DoubleArray, from: Int, to: Int, i: Int): Double? {
    val count = to - from + 1
    if (count < MinimumWindowFixes) return null
    var meanD = 0.0
    var meanP = 0.0
    for (j in from..to) {
        meanD += covered[j]
        meanP += samples[j].progressMeters
    }
    meanD /= count
    meanP /= count
    var cov = 0.0
    var variance = 0.0
    for (j in from..to) {
        val d = covered[j] - meanD
        cov += d * (samples[j].progressMeters - meanP)
        variance += d * d
    }
    return if (variance > MinimumCoveredVariance * count && cov > 0.0) {
        meanP + cov / variance * (covered[i] - meanD)
    } else {
        null
    }
}
