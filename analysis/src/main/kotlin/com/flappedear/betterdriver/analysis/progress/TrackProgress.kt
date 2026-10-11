@file:Suppress(
    // A line-by-line port of FlappedEar Telemetry's track progress; its structure is kept so the
    // two can be compared and the parity tests hold.
    "CyclomaticComplexMethod",
    "LongMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "ComplexCondition",
    "MagicNumber",
    "LongParameterList",
    "TooManyFunctions",
    "LoopWithTooManyJumpStatements",
)

package com.flappedear.betterdriver.analysis.progress

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.TimingGate
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.geoMidpoint
import com.flappedear.betterdriver.analysis.geo.hypot
import com.flappedear.betterdriver.analysis.geo.isValidCoordinate
import com.flappedear.betterdriver.analysis.geo.lround
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.laps.LapTrace
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Port of FlappedEar Telemetry packages/telemetry_core/lib/src/analysis/track_progress.dart
// (Apache 2.0), itself a port of FlappedEar Overlays' TrackProgress: a gate-anchored distance
// axis for one track, laps projected onto it, and the time delta between two laps by distance.

/** Coverage within this distance of either gate counts as reaching it. */
const val GateCoverageToleranceMeters = 15.0

/**
 * A dense (~2 m spacing), gate-anchored loop built from one representative lap. Progress 0 sits
 * where the lap's path crosses the timing gate. Points are local east/north metres around [origin].
 */
class ProgressAxis(
    val points: List<MetricPoint> = emptyList(),
    /** Arc length from points[0] to points[i]. */
    val cumulative: DoubleArray = DoubleArray(0),
    /** Length of the closed raw path (not of the resampled points). */
    val lengthMeters: Double = 0.0,
    /** [lengthMeters] / point count. */
    val spacingMeters: Double = 0.0,
    val origin: GeoCoordinate = GeoCoordinate(0.0, 0.0),
    val valid: Boolean = false,
)

/** Heading and curvature of the axis at one of its points. */
data class TrackFeatureSample(
    val progressMeters: Double,
    /** Smoothed direction of travel, atan2(north, east). */
    val headingRadians: Double,
    /** Signed rate of heading change per metre; positive turns left. */
    val curvaturePerMeter: Double,
)

class TrackFeatures(
    val samples: List<TrackFeatureSample> = emptyList(),
    val smoothingMeters: Double = 0.0,
    val valid: Boolean = false,
)

/** Rolling state between successive [projectSample] calls for one lap. */
class ProjectionContext {
    var hasLock = false
    var lastProgressMeters = 0.0
    var lastTelemetryTime = 0.0
}

/** One fix projected onto the axis. */
data class ProjectedSample(val telemetryTime: Double, val progressMeters: Double = 0.0, val valid: Boolean = false)

/** A run of locked samples (time increasing, progress never falling) with no gap in between. */
class ProgressSegment(val samples: MutableList<ProjectedSample> = mutableListOf())

/** The time difference between two laps at one progress value: A minus B, positive when A is behind. */
data class DeltaPoint(val progressMeters: Double, val deltaSeconds: Double)

private fun distance(a: MetricPoint, b: MetricPoint) = hypot(a.eastMeters - b.eastMeters, a.northMeters - b.northMeters)

private const val BackwardToleranceMeters = 3.0
private const val MaximumGapSeconds = 5.0
private const val SegmentStartBackwardMeters = 30.0
private const val MaximumPlausibleSpeedMetersPerSecond = 100.0
private const val OwnSpeedMargin = 1.5
private const val OwnSpeedFloorMetersPerSecond = 40.0
private const val MinimumHeadingMovementMeters = 0.5
private const val StandingSeconds = 0.5
private const val StandingMetersPerSecond = 1.0
private const val FlatBranchRatio = 0.95
private const val InsideBendShare = 0.5
private const val SameBranchCosine = 0.0

private fun cross(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx

private fun gateCrossing(closed: List<MetricPoint>, a: MetricPoint, b: MetricPoint): Pair<Int, MetricPoint>? {
    val gateX = b.eastMeters - a.eastMeters
    val gateY = b.northMeters - a.northMeters
    val midpoint = MetricPoint((a.eastMeters + b.eastMeters) / 2.0, (a.northMeters + b.northMeters) / 2.0)
    var result: Pair<Int, MetricPoint>? = null
    var bestDistance = Double.POSITIVE_INFINITY
    for (i in 0 until closed.size - 1) {
        val edgeX = closed[i + 1].eastMeters - closed[i].eastMeters
        val edgeY = closed[i + 1].northMeters - closed[i].northMeters
        val denominator = cross(edgeX, edgeY, gateX, gateY)
        if (!(abs(denominator) > 1e-12)) continue
        val offsetX = a.eastMeters - closed[i].eastMeters
        val offsetY = a.northMeters - closed[i].northMeters
        val alongEdge = cross(offsetX, offsetY, gateX, gateY) / denominator
        val alongGate = cross(offsetX, offsetY, edgeX, edgeY) / denominator
        if (alongEdge < 0.0 || alongEdge >= 1.0 || alongGate < 0.0 || alongGate > 1.0) continue
        val point = MetricPoint(closed[i].eastMeters + edgeX * alongEdge, closed[i].northMeters + edgeY * alongEdge)
        val d = distance(point, midpoint)
        if (d < bestDistance) {
            bestDistance = d
            result = i to point
        }
    }
    return result
}

private fun resampleByArcLength(points: List<MetricPoint>, count: Int): List<MetricPoint> {
    if (points.size < 2 || count < 1) return emptyList()
    val cumulative = DoubleArray(points.size)
    for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
    val length = cumulative.last()
    if (!(length > 0)) return emptyList()
    val result = ArrayList<MetricPoint>(count)
    var segment = 1
    for (i in 0 until count) {
        val target = length * i / count
        while (segment + 1 < cumulative.size && cumulative[segment] < target) segment++
        val span = cumulative[segment] - cumulative[segment - 1]
        if (!(span > 0)) return emptyList()
        val a = points[segment - 1]
        val b = points[segment]
        val ratio = (target - cumulative[segment - 1]) / span
        result += MetricPoint(
            a.eastMeters + (b.eastMeters - a.eastMeters) * ratio,
            a.northMeters + (b.northMeters - a.northMeters) * ratio,
        )
    }
    return result
}

/** (fraction along a→b, distance) of the closest point of segment a→b. */
private fun projectOntoSegment(point: MetricPoint, a: MetricPoint, b: MetricPoint): Pair<Double, Double> {
    val abx = b.eastMeters - a.eastMeters
    val aby = b.northMeters - a.northMeters
    val lengthSquared = abx * abx + aby * aby
    if (!(lengthSquared > 1e-9)) return 0.0 to distance(point, a)
    val apx = point.eastMeters - a.eastMeters
    val apy = point.northMeters - a.northMeters
    val t = ((apx * abx + apy * aby) / lengthSquared).coerceIn(0.0, 1.0)
    return t to distance(point, MetricPoint(a.eastMeters + abx * t, a.northMeters + aby * t))
}

private fun progressAtSegment(axis: ProgressAxis, segmentIndex: Int, fraction: Double): Double {
    val n = axis.points.size
    val next = (segmentIndex + 1) % n
    val segmentLength = if (next == 0) {
        axis.lengthMeters - axis.cumulative[segmentIndex]
    } else {
        axis.cumulative[next] - axis.cumulative[segmentIndex]
    }
    return axis.cumulative[segmentIndex] + segmentLength * fraction
}

private class Candidate {
    var index = -1
    var progressMeters = 0.0
    var distance = Double.POSITIVE_INFINITY
    var fraction = 0.0
}

private fun wrapIndex(index: Int, n: Int) = ((index % n) + n) % n

private fun bestCandidateInRange(
    axis: ProgressAxis,
    point: MetricPoint,
    startIndex: Int,
    count: Int,
    excludeIndex: Int? = null,
    minimumSeparation: Int = 0,
): Candidate {
    val best = Candidate()
    val n = axis.points.size
    val bounded = min(count, n)
    for (step in 0 until bounded) {
        val index = wrapIndex(startIndex + step, n)
        if (excludeIndex != null) {
            var separation = abs(index - excludeIndex)
            separation = min(separation, n - separation)
            if (separation < minimumSeparation) continue
        }
        val (fraction, d) = projectOntoSegment(point, axis.points[index], axis.points[(index + 1) % n])
        if (d < best.distance) {
            best.index = index
            best.distance = d
            best.fraction = fraction
            best.progressMeters = progressAtSegment(axis, index, fraction)
        }
    }
    return best
}

private fun runnerUpOnAnotherPart(
    axis: ProgressAxis,
    point: MetricPoint,
    best: Candidate,
    minimumSeparation: Int,
    ambiguityRatio: Double,
): Candidate {
    val n = axis.points.size
    fun indexAt(offset: Int) = wrapIndex(best.index + offset, n)
    val distances = DoubleArray(n)
    val fractions = DoubleArray(n)
    for (index in 0 until n) {
        val (fraction, d) = projectOntoSegment(point, axis.points[index], axis.points[(index + 1) % n])
        distances[index] = d
        fractions[index] = fraction
    }
    val (bx, by) = axisTangent(axis, best.index)
    fun sameBranch(index: Int): Boolean {
        if (!(best.distance > distances[index] * ambiguityRatio)) return false
        val (tx, ty) = axisTangent(axis, index)
        return tx * bx + ty * by >= SameBranchCosine
    }
    var behind = 0
    var ahead = 0
    while (behind + ahead + 1 < n && sameBranch(indexAt(-behind - 1))) behind++
    while (behind + ahead + 1 < n && sameBranch(indexAt(ahead + 1))) ahead++
    val runnerUp = Candidate()
    for (offset in -(n / 2) until n - n / 2) {
        val index = indexAt(offset)
        val onBranch = offset >= -behind && offset <= ahead
        if (onBranch && !(best.distance > distances[index] * FlatBranchRatio)) continue
        if (abs(offset) < minimumSeparation) continue
        if (distances[index] < runnerUp.distance) {
            runnerUp.index = index
            runnerUp.distance = distances[index]
            runnerUp.fraction = fractions[index]
            runnerUp.progressMeters = progressAtSegment(axis, index, fractions[index])
        }
    }
    return runnerUp
}

private fun insideBend(axis: ProgressAxis, point: MetricPoint, best: Candidate, ambiguityRatio: Double): Boolean {
    val n = axis.points.size
    fun wrap(index: Int) = wrapIndex(index, n)
    val (tx, ty) = axisTangent(axis, best.index)
    val a = axis.points[best.index]
    val b = axis.points[wrap(best.index + 1)]
    val footX = a.eastMeters + (b.eastMeters - a.eastMeters) * best.fraction
    val footY = a.northMeters + (b.northMeters - a.northMeters) * best.fraction
    val side = cross(tx, ty, point.eastMeters - footX, point.northMeters - footY)
    if (side == 0.0) return false
    fun near(index: Int): Boolean {
        val (_, d) = projectOntoSegment(point, axis.points[wrap(index)], axis.points[wrap(index + 1)])
        return best.distance > d * ambiguityRatio
    }
    fun tooTight(index: Int): Boolean {
        val (ax, ay) = axisTangent(axis, wrap(index - 2))
        val (bx, by) = axisTangent(axis, wrap(index + 2))
        val turn = atan2(cross(ax, ay, bx, by), ax * bx + ay * by)
        if (!(turn * side > 0)) return false
        val radius = 4 * axis.spacingMeters / abs(turn)
        return best.distance > InsideBendShare * radius
    }
    if (tooTight(best.index)) return true
    for (direction in intArrayOf(-1, 1)) {
        var step = 1
        while (step < n / 2 && near(best.index + direction * step)) {
            if (tooTight(best.index + direction * step)) return true
            step++
        }
    }
    return false
}

private fun nearestOtherLeg(
    axis: ProgressAxis,
    point: MetricPoint,
    index: Int,
    separation: Int,
    movement: Pair<Double, Double>,
): Double {
    val n = axis.points.size
    val (tx, ty) = axisTangent(axis, index)
    var nearest = Double.POSITIVE_INFINITY
    if (tx == 0.0 && ty == 0.0) return nearest
    val (mx, my) = movement
    val fit = mx * tx + my * ty
    for (offset in -separation + 1 until separation) {
        val other = wrapIndex(index + offset, n)
        val (ox, oy) = axisTangent(axis, other)
        if (ox * tx + oy * ty >= 0.0) continue
        if (mx * ox + my * oy < fit) continue
        val (_, d) = projectOntoSegment(point, axis.points[other], axis.points[(other + 1) % n])
        nearest = min(nearest, d)
    }
    return nearest
}

/** The axis point nearest [progressMeters] along the axis (modulo its length), by its cumulative distances. */
fun nearestAxisIndex(axis: ProgressAxis, progressMeters: Double): Int {
    val n = axis.points.size
    if (n == 0) return 0
    val cumulative = axis.cumulative
    val length = axis.lengthMeters
    if (cumulative.size != n || !(length > 0.0) || !progressMeters.isFinite()) {
        return wrapIndex(lround(progressMeters / axis.spacingMeters), n)
    }
    val progress = progressMeters - floor(progressMeters / length) * length
    var low = 0
    var high = n - 1
    while (low < high) {
        val middle = (low + high + 1) shr 1
        if (cumulative[middle] <= progress) low = middle else high = middle - 1
    }
    val next = if (low + 1 < n) cumulative[low + 1] else length
    return if (progress - cumulative[low] >= next - progress) (low + 1) % n else low
}

/** Unit tangent of the axis at [index], toward index + 1. Zero for a degenerate segment. */
private fun axisTangent(axis: ProgressAxis, index: Int): Pair<Double, Double> {
    val n = axis.points.size
    val a = axis.points[index]
    val b = axis.points[(index + 1) % n]
    val ex = b.eastMeters - a.eastMeters
    val ey = b.northMeters - a.northMeters
    val length = hypot(ex, ey)
    return if (length > 1e-6) (ex / length) to (ey / length) else 0.0 to 0.0
}

private fun headingAgrees(
    axis: ProgressAxis,
    index: Int,
    movement: Pair<Double, Double>,
    minimumCosine: Double,
): Boolean {
    val (mx, my) = movement
    val movementLength = hypot(mx, my)
    if (!(movementLength > 1e-6)) return true
    val (tx, ty) = axisTangent(axis, index)
    if (tx == 0.0 && ty == 0.0) return true
    return (mx * tx + my * ty) / movementLength >= minimumCosine
}

/** Signed shortest angle from [from] to [to], in (-π, π]. */
private fun angularDifference(to: Double, from: Double) = atan2(sin(to - from), cos(to - from))

/**
 * The progress axis of [referenceTrace] (a reference-eligible, gap-free lap) around [origin], with
 * index 0 where the lap's path crosses [gate] (else the point closest to its midpoint). Invalid for
 * fewer than 12 distinct points or a loop outside 50 m to 30 km.
 */
fun buildProgressAxis(referenceTrace: LapTrace, origin: GeoCoordinate, gate: TimingGate): ProgressAxis {
    val invalid = ProgressAxis()
    if (referenceTrace.points.size < 12) return invalid
    val points = mutableListOf<MetricPoint>()
    for (sample in referenceTrace.points) {
        val point = MetricPoint(sample.eastMeters, sample.northMeters)
        if (!point.eastMeters.isFinite() || !point.northMeters.isFinite()) return invalid
        if (points.isEmpty() || distance(points.last(), point) >= 0.5) points += point
    }
    if (points.size < 12) return invalid
    points += points.first()
    var length = 0.0
    for (i in 1 until points.size) length += distance(points[i - 1], points[i])
    if (!(length >= 50 && length <= 30000)) return invalid
    val pointCount = lround(length / 2.0).coerceIn(32, 15000)

    val crossing = gateCrossing(
        points,
        projectCoordinate(gate.endpointA, origin),
        projectCoordinate(gate.endpointB, origin),
    )
    val rotated: List<MetricPoint>
    if (crossing != null) {
        val (edge, point) = crossing
        val open = points.size - 1
        val restarted = mutableListOf(point)
        for (step in 1..open) {
            val next = points[(edge + step) % open]
            if (distance(next, restarted.last()) > 1e-9) restarted += next
        }
        if (distance(point, restarted.last()) > 1e-9) restarted += point
        rotated = resampleByArcLength(restarted, pointCount)
        if (rotated.size != pointCount) return invalid
    } else {
        val resampled = resampleByArcLength(points, pointCount)
        if (resampled.size != pointCount) return invalid
        val gateLocal = projectCoordinate(geoMidpoint(gate.endpointA, gate.endpointB), origin)
        var gateIndex = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in resampled.indices) {
            val d = distance(resampled[i], gateLocal)
            if (d < bestDistance) {
                bestDistance = d
                gateIndex = i
            }
        }
        rotated = List(resampled.size) { resampled[(gateIndex + it) % resampled.size] }
    }
    val cumulative = DoubleArray(rotated.size)
    for (i in 1 until rotated.size) cumulative[i] = cumulative[i - 1] + distance(rotated[i - 1], rotated[i])
    return ProgressAxis(rotated, cumulative, length, length / rotated.size, origin, true)
}

/**
 * Heading at each axis point as the circular mean of the direction of travel within
 * [smoothingMeters] each side, wrapping around the loop; curvature as the signed change to the
 * next point's heading over the point spacing.
 */
fun computeTrackFeatures(axis: ProgressAxis, smoothingMeters: Double): TrackFeatures {
    if (!axis.valid || axis.points.size < 4 || !(axis.spacingMeters > 0.0) ||
        !smoothingMeters.isFinite() || smoothingMeters <= 0.0
    ) {
        return TrackFeatures()
    }
    val n = axis.points.size
    val radius = lround(smoothingMeters / axis.spacingMeters).coerceIn(1, n / 2 - 1)
    val tangents = Array(n) { axisTangent(axis, it) }
    val heading = DoubleArray(n)
    for (i in 0 until n) {
        var sumEast = 0.0
        var sumNorth = 0.0
        for (offset in -radius..radius) {
            val (tx, ty) = tangents[wrapIndex(i + offset, n)]
            sumEast += tx
            sumNorth += ty
        }
        heading[i] = atan2(sumNorth, sumEast)
    }
    val samples = List(n) {
        TrackFeatureSample(
            axis.cumulative[it],
            heading[it],
            angularDifference(heading[(it + 1) % n], heading[it]) / axis.spacingMeters,
        )
    }
    return TrackFeatures(samples, smoothingMeters, true)
}

/**
 * Projects one GPS fix ([localPoint], metres around the axis origin) onto [axis] with a bounded,
 * forward-biased search around the previous lock; the car's [movement] must roughly agree with
 * the axis direction at the match. Returns an invalid sample, never a guess, when no candidate is
 * unambiguous, continuous, heading-consistent and close enough. [context] changes only on success.
 */
fun projectSample(
    axis: ProgressAxis,
    localPoint: MetricPoint,
    telemetryTime: Double,
    speedMetersPerSecond: Double,
    movement: Pair<Double, Double>,
    context: ProjectionContext,
): ProjectedSample {
    val invalid = ProjectedSample(telemetryTime)
    if (!axis.valid || axis.points.size < 8 || !(axis.spacingMeters > 0)) return invalid
    if (!localPoint.eastMeters.isFinite() || !localPoint.northMeters.isFinite()) return invalid
    if (!telemetryTime.isFinite()) return invalid

    val coldStartProximityMeters = 20.0
    val lockProximityMeters = 20.0
    val ambiguityRatio = 0.7
    val coldStartSeparationMeters = 30.0
    val windowedSeparationMeters = 10.0
    val minimumHeadingCosine = 0.0

    val dt = if (context.hasLock) telemetryTime - context.lastTelemetryTime else 0.0
    val coldStart = !context.hasLock || !(dt > 0) || dt > MaximumGapSeconds
    val n = axis.points.size

    fun lock(progress: Double): ProjectedSample {
        context.hasLock = true
        context.lastProgressMeters = progress
        context.lastTelemetryTime = telemetryTime
        return ProjectedSample(telemetryTime, progress, true)
    }

    if (coldStart) {
        val best = bestCandidateInRange(axis, localPoint, 0, n)
        if (best.index < 0 || best.distance > coldStartProximityMeters) return invalid
        val minimumSeparation = max(4, lround(coldStartSeparationMeters / axis.spacingMeters))
        val second = bestCandidateInRange(axis, localPoint, 0, n, best.index, minimumSeparation)
        if (second.index >= 0 && best.distance > second.distance * ambiguityRatio) return invalid
        val otherLeg = nearestOtherLeg(axis, localPoint, best.index, minimumSeparation, movement)
        if (best.distance > otherLeg * ambiguityRatio) return invalid
        if (!headingAgrees(axis, best.index, movement, minimumHeadingCosine)) return invalid
        return lock(best.progressMeters)
    }

    val expectedTravel = dt * max(0.0, speedMetersPerSecond)
    val forwardWindow = (expectedTravel * 1.6).coerceIn(15.0, 150.0)
    val backwardWindow = min(15.0, forwardWindow * 0.3)
    val forwardCount = max(1, lround(forwardWindow / axis.spacingMeters))
    val backwardCount = max(1, lround(backwardWindow / axis.spacingMeters))
    val centerIndex = nearestAxisIndex(axis, context.lastProgressMeters)
    val startIndex = centerIndex - backwardCount
    val count = forwardCount + backwardCount + 1

    val best = bestCandidateInRange(axis, localPoint, startIndex, count)
    if (best.index < 0 || best.distance > lockProximityMeters) return invalid
    if (count < n && best.index == wrapIndex(startIndex + count - 1, n) && best.fraction >= 1.0) return invalid
    val minimumSeparation = max(3, lround(windowedSeparationMeters / axis.spacingMeters))
    val windowed = bestCandidateInRange(axis, localPoint, startIndex, count, best.index, minimumSeparation)
    if (windowed.index >= 0 && best.distance > windowed.distance * ambiguityRatio) {
        val second = runnerUpOnAnotherPart(axis, localPoint, best, minimumSeparation, ambiguityRatio)
        if (second.index >= 0 && best.distance > second.distance * ambiguityRatio) return invalid
        if (insideBend(axis, localPoint, best, ambiguityRatio)) return invalid
    }
    if (!headingAgrees(axis, best.index, movement, minimumHeadingCosine)) return invalid

    var delta = best.progressMeters - context.lastProgressMeters
    if (delta < -axis.lengthMeters / 2) {
        delta += axis.lengthMeters
    } else if (delta > axis.lengthMeters / 2) {
        delta -= axis.lengthMeters
    }
    if (delta < -BackwardToleranceMeters) return invalid
    return lock(best.progressMeters)
}

/**
 * Projects every valid latitude/longitude sample of [session] in [startTime]..[endTime] onto
 * [axis], in time order. A GPS gap or a stretch [projectSample] cannot lock onto ends the current
 * segment instead of bridging it. Progress is unwrapped within the lap: a fix just after the lap's
 * timed start that projects just before progress 0 is a small negative value, and fixes past the
 * finish continue beyond the axis length. Progress never falls within a segment.
 */
fun projectLapTrace(
    axis: ProgressAxis,
    session: TelemetrySession,
    startTime: Double,
    endTime: Double,
): List<ProgressSegment> {
    val result = mutableListOf<ProgressSegment>()
    if (!axis.valid) return result
    val latitudeSegments = session.sampledSegments(Aliases.LATITUDE, startTime, endTime, 4000)
    val length = axis.lengthMeters
    val allowance = min(SegmentStartBackwardMeters, length / 4)

    var current = ProgressSegment()
    var context = ProjectionContext()
    var (previousLocal, previousTime) = fixBefore(session, startTime, axis.origin)
    val recentProgress = ArrayDeque<Pair<Double, Double>>()
    var lastProgress: Double? = null
    var lastProgressTime: Double? = null
    var fastest = 0.0
    var secondAgo = 0

    fun flush(keepMovement: Boolean = false) {
        if (current.samples.isNotEmpty()) {
            result += current
            current = ProgressSegment()
        }
        context = ProjectionContext()
        secondAgo = 0
        if (!keepMovement) {
            previousLocal = null
            previousTime = null
        }
    }

    fun reach(seconds: Double) = seconds * MaximumPlausibleSpeedMetersPerSecond + SegmentStartBackwardMeters
    fun ownReach(seconds: Double) =
        seconds *
            (fastest * OwnSpeedMargin).coerceIn(OwnSpeedFloorMetersPerSecond, MaximumPlausibleSpeedMetersPerSecond) +
            SegmentStartBackwardMeters

    fun conflictingRun(progress: Double, time: Double, behind: Boolean): Pair<Int, Double>? {
        val last = lastProgress!!
        for (k in result.indices.reversed()) {
            val first = result[k].samples.first().progressMeters
            if (last - first >= length / 4) return null
            val anchor: Double
            val anchorTime: Double
            if (k == 0) {
                anchor = 0.0
                anchorTime = startTime
            } else {
                val s = result[k - 1].samples.last()
                anchor = s.progressMeters
                anchorTime = s.telemetryTime
            }
            val from = progress + ceil((anchor - allowance - progress) / length) * length
            val fits = if (behind) {
                from < first - allowance && from - anchor <= reach(time - anchorTime)
            } else {
                from - anchor <= ownReach(time - anchorTime)
            }
            if (fits) return k to from
        }
        return null
    }

    for (segment in latitudeSegments) {
        val since = previousTime
        flush(keepMovement = since != null && segment.first().time - since <= MaximumGapSeconds)
        for (sample in segment) {
            val time = sample.time
            val latitude = sample.value
            val longitude = session.valueAt(Aliases.LONGITUDE, time, Interpolation.Longitude)
            if (longitude == null || !isValidCoordinate(GeoCoordinate(latitude, longitude))) {
                flush()
                continue
            }
            val local = projectCoordinate(GeoCoordinate(latitude, longitude), axis.origin)
            var speed = 0.0
            var movement = 0.0 to 0.0
            val lastLocal = previousLocal
            val lastTime = previousTime
            if (lastLocal != null && lastTime != null && time > lastTime) {
                movement = (local.eastMeters - lastLocal.eastMeters) to (local.northMeters - lastLocal.northMeters)
                val moved = hypot(movement.first, movement.second)
                speed = moved / (time - lastTime)
                if (!context.hasLock && moved < MinimumHeadingMovementMeters && standing(recentProgress, time)) {
                    movement = 0.0 to 0.0
                }
            }
            previousLocal = local
            previousTime = time

            val projected = projectSample(axis, local, time, speed, movement, context)
            if (!projected.valid) {
                flush(keepMovement = true)
                continue
            }

            var progress = projected.progressMeters
            val last = lastProgress
            if (last == null) {
                if (progress > length / 2 && time - startTime <= MaximumGapSeconds) progress -= length
            } else if (current.samples.isEmpty()) {
                progress += ceil((last - allowance - progress) / length) * length
                val ahead = progress - last
                val sinceLast = time - lastProgressTime!!
                var run = if (ahead > ownReach(sinceLast)) conflictingRun(progress, time, behind = true) else null
                if (run == null && ahead > reach(sinceLast)) run = conflictingRun(progress, time, behind = false)
                if (run != null) {
                    val (k, behindProgress) = run
                    while (result.size > k) result.removeAt(result.size - 1)
                    progress = behindProgress
                } else if (ahead > reach(sinceLast)) {
                    flush(keepMovement = true)
                    continue
                }
            } else {
                progress += ceil((last - BackwardToleranceMeters - progress) / length) * length
                progress = max(progress, last)
            }
            lastProgress = progress
            lastProgressTime = time
            current.samples += ProjectedSample(projected.telemetryTime, progress, true)
            while (recentProgress.isNotEmpty() && time - recentProgress.first().first > 1.0) {
                recentProgress.removeFirst()
            }
            // removeWhere in the original drops every fix older than a second; the deque is time-ordered.
            recentProgress.addLast(time to progress)
            val samples = current.samples
            while (secondAgo + 1 < samples.size && time - samples[secondAgo + 1].telemetryTime >= 1.0) secondAgo++
            val span = time - samples[secondAgo].telemetryTime
            if (span >= 1.0) fastest = max(fastest, (progress - samples[secondAgo].progressMeters) / span)
        }
    }
    if (current.samples.isNotEmpty()) result += current
    return result
}

/** Whether the lap stands at [time]: barely moved over the half second before its last fix. */
private fun standing(recentProgress: ArrayDeque<Pair<Double, Double>>, time: Double): Boolean {
    if (recentProgress.isEmpty()) return false
    val (lastTime, lastProgress) = recentProgress.last()
    if (time - lastTime > StandingSeconds) return false
    for (i in recentProgress.indices.reversed()) {
        val (at, progress) = recentProgress[i]
        val span = lastTime - at
        if (span >= StandingSeconds) return lastProgress - progress < span * StandingMetersPerSecond
    }
    return false
}

/** The last fix before [time] in metres around [origin], and its time; nulls when too old or invalid. */
private fun fixBefore(session: TelemetrySession, time: Double, origin: GeoCoordinate): Pair<MetricPoint?, Double?> {
    val latitude = session.channel(Aliases.LATITUDE) ?: return null to null
    val timestamps = latitude.timestamps
    var low = 0
    var high = timestamps.size
    while (low < high) {
        val middle = low + ((high - low) shr 1)
        if (timestamps[middle] < time) low = middle + 1 else high = middle
    }
    if (low == 0 || !(time - timestamps[low - 1] <= MaximumGapSeconds)) return null to null
    val before = timestamps[low - 1]
    val longitude = session.valueAt(Aliases.LONGITUDE, before) ?: return null to null
    val coordinate = GeoCoordinate(latitude.values[low - 1], longitude)
    if (!isValidCoordinate(coordinate)) return null to null
    return projectCoordinate(coordinate, origin) to before
}

private fun lowerBoundBy(samples: List<ProjectedSample>, value: Double, key: (ProjectedSample) -> Double): Int {
    var low = 0
    var high = samples.size
    while (low < high) {
        val middle = low + ((high - low) shr 1)
        if (key(samples[middle]) < value) low = middle + 1 else high = middle
    }
    return low
}

private fun timeAtProgressInSegment(segment: ProgressSegment, progress: Double): Double? {
    val samples = segment.samples
    if (samples.isEmpty()) return null
    if (progress < samples.first().progressMeters || progress > samples.last().progressMeters) return null
    val next = lowerBoundBy(samples, progress) { it.progressMeters }
    if (next == 0) return samples[next].telemetryTime
    if (next == samples.size) return samples[next - 1].telemetryTime
    val previous = samples[next - 1]
    val span = samples[next].progressMeters - previous.progressMeters
    if (!(span > 0)) return previous.telemetryTime
    val ratio = (progress - previous.progressMeters) / span
    return previous.telemetryTime + (samples[next].telemetryTime - previous.telemetryTime) * ratio
}

private fun referenceTime(lap: List<ProgressSegment>): Double {
    timeAtProgress(lap, 0.0)?.let { return it }
    for (segment in lap) if (segment.samples.isNotEmpty()) return segment.samples.first().telemetryTime
    return 0.0
}

/** Interpolated telemetry time at [progressMeters] from the first segment covering it; never bridged. */
fun timeAtProgress(lap: List<ProgressSegment>, progressMeters: Double): Double? {
    for (segment in lap) timeAtProgressInSegment(segment, progressMeters)?.let { return it }
    return null
}

/** Interpolated progress at [telemetryTime], or null when no segment spans that time. */
fun progressAtTime(lap: List<ProgressSegment>, telemetryTime: Double): Double? {
    for (segment in lap) {
        val samples = segment.samples
        if (samples.isEmpty()) continue
        if (telemetryTime < samples.first().telemetryTime || telemetryTime > samples.last().telemetryTime) continue
        val next = lowerBoundBy(samples, telemetryTime) { it.telemetryTime }
        if (next == 0) return samples[next].progressMeters
        val previous = samples[next - 1]
        val span = samples[next].telemetryTime - previous.telemetryTime
        if (!(span > 0.0)) return previous.progressMeters
        return previous.progressMeters +
            (samples[next].progressMeters - previous.progressMeters) * (telemetryTime - previous.telemetryTime) / span
    }
    return null
}

/** Each lap's timed start and end, and the axis length, for [computeTimedDeltaSeries]. */
data class DeltaTiming(
    val lapStartA: Double,
    val lapEndA: Double,
    val lapStartB: Double,
    val lapEndB: Double,
    val lengthMeters: Double,
)

private fun anchoredAtGate(
    lap: List<ProgressSegment>,
    start: Double,
    end: Double,
    length: Double,
): List<ProgressSegment> {
    if (lap.isEmpty() || lap.first().samples.isEmpty() || lap.last().samples.isEmpty()) return lap
    val segments = lap.map { ProgressSegment(it.samples.toMutableList()) }
    val first = segments.first().samples
    if (first.first().progressMeters > 0.0 &&
        first.first().progressMeters <= GateCoverageToleranceMeters &&
        start < first.first().telemetryTime
    ) {
        first.add(0, ProjectedSample(start, valid = true))
    }
    val last = segments.last().samples
    if (last.last().progressMeters < length &&
        last.last().progressMeters >= length - GateCoverageToleranceMeters &&
        end > last.last().telemetryTime
    ) {
        last.add(ProjectedSample(end, length, true))
    }
    return segments
}

/**
 * Lap A's time from its timed start minus lap B's, every [progressStepMeters] from 0 to the axis
 * length (always the last step). A lap whose projection reaches within the gate tolerance is at
 * progress 0 at its timed start and at the axis length at its timed end, so the delta is 0 at the
 * start and the lap-time difference at the finish. Each uncovered stretch starts a new series.
 */
fun computeTimedDeltaSeries(
    lapA: List<ProgressSegment>,
    lapB: List<ProgressSegment>,
    progressStepMeters: Double,
    timing: DeltaTiming,
): List<List<DeltaPoint>> {
    val result = mutableListOf<List<DeltaPoint>>()
    val length = timing.lengthMeters
    if (lapA.isEmpty() || lapB.isEmpty() || !(progressStepMeters > 0)) return result
    if (!length.isFinite() || !(length > 0)) return result
    val anchoredA = anchoredAtGate(lapA, timing.lapStartA, timing.lapEndA, length)
    val anchoredB = anchoredAtGate(lapB, timing.lapStartB, timing.lapEndB, length)
    val steps = ceil(length / progressStepMeters).toInt()
    var current = mutableListOf<DeltaPoint>()
    for (i in 0..steps) {
        val progress = min(length, i * progressStepMeters)
        val timeA = timeAtProgress(anchoredA, progress)
        val timeB = timeAtProgress(anchoredB, progress)
        if (timeA == null || timeB == null) {
            if (current.isNotEmpty()) {
                result += current
                current = mutableListOf()
            }
            continue
        }
        current += DeltaPoint(progress, (timeA - timing.lapStartA) - (timeB - timing.lapStartB))
    }
    if (current.isNotEmpty()) result += current
    return result
}

/**
 * Lap A's time minus lap B's (each relative to its own start) every [progressStepMeters], only
 * where both laps have locked coverage of the same progress. Each uncovered stretch starts a new series.
 */
fun computeDeltaSeries(
    lapA: List<ProgressSegment>,
    lapB: List<ProgressSegment>,
    progressStepMeters: Double,
): List<List<DeltaPoint>> {
    val result = mutableListOf<List<DeltaPoint>>()
    if (lapA.isEmpty() || lapB.isEmpty() || !(progressStepMeters > 0)) return result
    var maxProgress = 0.0
    var haveCoverage = false
    for (segment in lapA + lapB) {
        if (segment.samples.isNotEmpty()) {
            maxProgress = max(maxProgress, segment.samples.last().progressMeters)
            haveCoverage = true
        }
    }
    if (!haveCoverage || !(maxProgress > 0)) return result
    val referenceA = referenceTime(lapA)
    val referenceB = referenceTime(lapB)
    val steps = floor(maxProgress / progressStepMeters).toInt()
    var current = mutableListOf<DeltaPoint>()
    for (i in 0..steps) {
        val progress = min(maxProgress, i * progressStepMeters)
        val timeA = timeAtProgress(lapA, progress)
        val timeB = timeAtProgress(lapB, progress)
        if (timeA == null || timeB == null) {
            if (current.isNotEmpty()) {
                result += current
                current = mutableListOf()
            }
            continue
        }
        current += DeltaPoint(progress, (timeA - referenceA) - (timeB - referenceB))
    }
    if (current.isNotEmpty()) result += current
    return result
}
