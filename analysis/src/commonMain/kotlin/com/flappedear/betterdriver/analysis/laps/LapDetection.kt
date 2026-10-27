@file:Suppress(
    // A line-by-line port of FlappedEar Telemetry's lap detector; its structure is kept so the
    // two can be compared and the parity tests hold.
    "CyclomaticComplexMethod",
    "LongMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "ComplexCondition",
    "MagicNumber",
    "LoopWithTooManyJumpStatements",
    "TooManyFunctions",
)

package com.flappedear.betterdriver.analysis.laps

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.TimingGate
import com.flappedear.betterdriver.analysis.data.TimingGateType
import com.flappedear.betterdriver.analysis.data.lowerBound
import com.flappedear.betterdriver.analysis.data.upperBound
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.Vector2
import com.flappedear.betterdriver.analysis.geo.geoMidpoint
import com.flappedear.betterdriver.analysis.geo.isValidCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.timing.live.MinimumGnssGapSeconds
import com.flappedear.betterdriver.timing.live.gnssGapThresholdSeconds
import kotlin.math.abs
import kotlin.math.min

// Port of FlappedEar Telemetry packages/telemetry_core/lib/src/laps/lap_detection.dart,
// lap_traces.dart and segment_geometry.dart (Apache 2.0).

/**
 * Laps from the recording's own start gate. Exactly one Start gate is required. With a Finish gate
 * too, the track is point to point and the laps are its runs ([detectRuns], FBBD-155).
 */
fun deriveSourceLapSession(
    session: TelemetrySession,
    options: LapDetectionOptions = LapDetectionOptions(),
): LapSession {
    var startGate: TimingGate? = null
    var finishGate: TimingGate? = null
    for (gate in session.timingGates) {
        if (gate.type == TimingGateType.Finish) {
            if (finishGate != null) return LapSession(LapSessionStatus.AmbiguousStartGate)
            finishGate = gate
        }
        if (gate.type != TimingGateType.Start) continue
        if (startGate != null) return LapSession(LapSessionStatus.AmbiguousStartGate)
        startGate = gate
    }
    if (startGate == null) return LapSession(LapSessionStatus.NoStartGate)
    if (finishGate != null) return detectRuns(session, startGate, finishGate, options)
    return detectLaps(session, startGate, options)
}

private class GpsSample(val time: Double, val point: Vector2)

/**
 * The longest step between two GPS fixes that is still continuous: three of the shorter channel's
 * median intervals, and at least [MinimumGpsGapSeconds] (FBBD-215). Three sample intervals alone made
 * two fixes missing in a row at 25 Hz (a receiver that already skips the odd fix) a loss of GPS that
 * took the whole lap or run out of the analysis. The same rule as the live lap timer's
 * ([gnssGapThresholdSeconds], audit A11).
 */
fun gpsGapThreshold(latitude: TelemetryChannel, longitude: TelemetryChannel): Double =
    gnssGapThresholdSeconds(min(latitude.baseIntervalSeconds, longitude.baseIntervalSeconds))

/** A step between fixes up to this long is never a GPS gap: a few missed fixes, not a loss of signal. */
const val MinimumGpsGapSeconds = MinimumGnssGapSeconds

private fun gpsSampleAt(lat: TelemetryChannel, lon: TelemetryChannel, index: Int, origin: GeoCoordinate): GpsSample? {
    if (index < 0 || index >= lat.timestamps.size || index >= lon.timestamps.size) return null
    val t = lat.timestamps[index]
    val c = GeoCoordinate(lat.values[index], lon.values[index])
    if (!t.isFinite() || t != lon.timestamps[index] || !isValidCoordinate(c)) return null
    val p = projectCoordinate(c, origin)
    if (!p.eastMeters.isFinite() || !p.northMeters.isFinite()) return null
    return GpsSample(t, Vector2(p.eastMeters, p.northMeters))
}

private class PassageCluster {
    var active = false
    var firstTime = 0.0
    var lastTime = 0.0
    var firstPoint = Vector2(0.0, 0.0)
    var lastPoint = Vector2(0.0, 0.0)
    var candidateTime = 0.0
    var candidateDistance = Double.POSITIVE_INFINITY
    var candidateGateFraction = 0.0
    var firstSide = 0
    var lastSide = 0
    var reachedLineInSpan = false
}

/** The closest approach between a vehicle segment and a gate segment. */
internal data class ClosestSegments(val distanceMeters: Double, val vehicleFraction: Double, val gateFraction: Double)

internal fun clampedProjectionFraction(point: Vector2, segmentStart: Vector2, segmentEnd: Vector2): Double {
    val segment = segmentEnd - segmentStart
    val lengthSquared = segment.dot(segment)
    if (!lengthSquared.isFinite() || lengthSquared <= 0.0) return 0.0
    val fraction = (point - segmentStart).dot(segment) / lengthSquared
    return if (fraction < 0.0) 0.0 else if (fraction > 1.0) 1.0 else fraction
}

/** Distance 0 at the intersection when the segments cross; else the shortest endpoint distance. */
internal fun closestSegments(
    vehicleStart: Vector2,
    vehicleEnd: Vector2,
    gateStart: Vector2,
    gateEnd: Vector2,
): ClosestSegments {
    val vehicle = vehicleEnd - vehicleStart
    val gate = gateEnd - gateStart
    val denominator = vehicle.cross(gate)
    if (denominator.isFinite() && abs(denominator) > 1e-12) {
        val offset = gateStart - vehicleStart
        val vf = offset.cross(gate) / denominator
        val gf = offset.cross(vehicle) / denominator
        if (vf >= 0.0 && vf <= 1.0 && gf >= 0.0 && gf <= 1.0) return ClosestSegments(0.0, vf, gf)
    }
    var best = ClosestSegments(Double.POSITIVE_INFINITY, 0.0, 0.0)
    fun consider(first: Vector2, second: Vector2, vf: Double, gf: Double) {
        val d = (first - second).length
        if (d.isFinite() && d < best.distanceMeters) best = ClosestSegments(d, vf, gf)
    }
    val g0 = clampedProjectionFraction(vehicleStart, gateStart, gateEnd)
    consider(vehicleStart, gateStart + gate * g0, 0.0, g0)
    val g1 = clampedProjectionFraction(vehicleEnd, gateStart, gateEnd)
    consider(vehicleEnd, gateStart + gate * g1, 1.0, g1)
    val v0 = clampedProjectionFraction(gateStart, vehicleStart, vehicleEnd)
    consider(vehicleStart + vehicle * v0, gateStart, v0, 0.0)
    val v1 = clampedProjectionFraction(gateEnd, vehicleStart, vehicleEnd)
    consider(vehicleStart + vehicle * v1, gateEnd, v1, 1.0)
    return best
}

/**
 * Finds passes through [startGate] and the laps between them; see [findGatePasses] for what a pass
 * is. All passes take the direction that accepts the most passes (the faster ones on a tie).
 */
fun detectLaps(
    session: TelemetrySession,
    startGate: TimingGate,
    options: LapDetectionOptions = LapDetectionOptions(),
): LapSession {
    val found = findGatePasses(session, startGate, options, lock = 0)
    found.failure?.let { return LapSession(it, startGate) }
    val passes = found.passes
    val origin = found.origin

    val measured = mutableListOf<TimedLap>()
    for (index in 1 until passes.size) {
        val start = passes[index - 1].telemetryTime
        val end = passes[index].telemetryTime
        val duration = end - start
        if (!duration.isFinite() || duration <= 0.0) continue
        val lap = TimedLap(measured.size + 1, start, end, duration)
        val (issue, distance) = referenceIssueForLap(session, lap, origin)
        measured += lap.copy(referenceIssue = issue, distanceMeters = distance)
    }
    return rankedSession(session, startGate, found, measured, options)
}

/**
 * Runs of a point-to-point track (FBBD-155): each from a pass of [startGate] to the next pass of
 * [finishGate]. Both gates count only when crossed forwards, the way the live timer reads a
 * BetterDriver line (left end A, right end B), so the drive back, which crosses them the other
 * way, is never a pass. A start with another start before any finish is an aborted run and is
 * dropped; a finish with no run on is ignored. The passes kept are the start gate's. Runs are
 * held to [LapDetectionOptions.minimumRunSeconds] and [LapDetectionOptions.minimumRunDistanceMeters]
 * rather than the circuit limits, so a short sprint counts.
 */
fun detectRuns(
    session: TelemetrySession,
    startGate: TimingGate,
    finishGate: TimingGate,
    options: LapDetectionOptions = LapDetectionOptions(),
): LapSession {
    val starts = findGatePasses(session, startGate, options, lock = ForwardsAcrossBetterDriverLine)
    starts.failure?.let { return LapSession(it, startGate) }
    val finishes = findGatePasses(session, finishGate, options, lock = ForwardsAcrossBetterDriverLine)
    finishes.failure?.let { return LapSession(it, startGate) }
    val crossings = starts.passes.map { it.telemetryTime to true } + finishes.passes.map { it.telemetryTime to false }
    val measured = mutableListOf<TimedLap>()
    var runStart: Double? = null
    for ((time, isStart) in crossings.sortedBy { it.first }) {
        val start = runStart
        if (isStart) {
            runStart = time
        } else if (start != null) {
            runStart = null
            val duration = time - start
            if (!duration.isFinite() || duration <= 0.0) continue
            val lap = TimedLap(measured.size + 1, start, time, duration)
            val (issue, distance) = referenceIssueForLap(session, lap, starts.origin)
            measured += lap.copy(referenceIssue = issue, distanceMeters = distance)
        }
    }
    val runLimits = options.copy(
        minimumLapSeconds = options.minimumRunSeconds,
        minimumLapDistanceMeters = options.minimumRunDistanceMeters,
    )
    return rankedSession(session, startGate, starts, measured, runLimits).copy(finishGate = finishGate)
}

/** BetterDriver's lines are crossed forwards towards the left of endpoint A to endpoint B: direction +1. */
private const val ForwardsAcrossBetterDriverLine = 1

/** [measured] laps checked, ranked and traced, as a [LapSession] of [gate] with the passes [found]. */
private fun rankedSession(
    session: TelemetrySession,
    gate: TimingGate,
    found: GatePasses,
    measured: List<TimedLap>,
    options: LapDetectionOptions,
): LapSession {
    val passes = found.passes
    val laps = markImplausibleLaps(measured, options)
    val (ranked, fastest) = rankLaps(laps)
    val status = when {
        passes.isEmpty() -> LapSessionStatus.NoAcceptedPasses
        ranked.isEmpty() -> LapSessionStatus.InsufficientPasses
        else -> LapSessionStatus.Available
    }
    val available = status == LapSessionStatus.Available
    val traces = if (available) buildLapTraces(session, ranked, found.origin) else emptyList()
    return LapSession(status, gate, passes, ranked, traces, fastest)
}

/** Accepted passes of one gate, or why there are none ([failure]); [origin] is the gate's centre. */
private class GatePasses(
    val origin: GeoCoordinate,
    val passes: List<GatePass> = emptyList(),
    val failure: LapSessionStatus? = null,
)

/**
 * Finds passes through [gate]. A pass is a run of GPS segments that comes within the inner corridor
 * of the gate and ends when the path leaves the outer corridor. It is accepted when it is short,
 * fast enough, crosses the gate rather than running along it, and really crosses the line. With
 * [lock] 0 all passes take the direction that accepts the most passes (the faster ones on a tie);
 * with +1 or -1 only passes that way count. A GPS gap or invalid fix breaks continuity.
 */
private fun findGatePasses(
    session: TelemetrySession,
    gate: TimingGate,
    options: LapDetectionOptions,
    lock: Int,
): GatePasses {
    val origin = geoMidpoint(gate.endpointA, gate.endpointB)
    val gateValid = isValidCoordinate(gate.endpointA) && isValidCoordinate(gate.endpointB)
    if (!gateValid || !isValidCoordinate(origin)) {
        return GatePasses(origin, failure = LapSessionStatus.InvalidGate)
    }
    val pa = projectCoordinate(gate.endpointA, origin)
    val pb = projectCoordinate(gate.endpointB, origin)
    val gateA = Vector2(pa.eastMeters, pa.northMeters)
    val gateB = Vector2(pb.eastMeters, pb.northMeters)
    val gateVector = gateB - gateA
    val gateLength = gateVector.length
    if (!gateLength.isFinite() ||
        gateLength < options.minimumGateLengthMeters ||
        gateLength > options.maximumGateLengthMeters
    ) {
        return GatePasses(origin, failure = LapSessionStatus.InvalidGate)
    }
    val gateNormal = Vector2(-gateVector.y / gateLength, gateVector.x / gateLength)
    fun signedDistance(p: Vector2) = (p - gateA).dot(gateNormal)
    fun sideOf(p: Vector2): Int {
        val d = signedDistance(p)
        return if (d > 0.0) 1 else if (d < 0.0) -1 else 0
    }
    val spanTolerance = options.innerCorridorMeters / gateLength
    fun reachesLineInSpan(start: Vector2, end: Vector2): Boolean {
        val sd = signedDistance(start)
        val ed = signedDistance(end)
        if (sd == 0.0) return false
        if (ed != 0.0 && (ed > 0.0) == (sd > 0.0)) return false
        val fraction = sd / (sd - ed)
        val point = start + (end - start) * fraction
        val along = (point - gateA).dot(gateVector) / (gateLength * gateLength)
        return along.isFinite() && along >= -spanTolerance && along <= 1.0 + spanTolerance
    }

    val latitude = session.channel(Aliases.LATITUDE)
    val longitude = session.channel(Aliases.LONGITUDE)
    if (latitude == null || longitude == null) return GatePasses(origin, failure = LapSessionStatus.NoUsableGps)
    val sampleCount = maxOf(latitude.timestamps.size, longitude.timestamps.size)
    val gapThreshold = gpsGapThreshold(latitude, longitude)

    var usableSegments = 0
    fun scan(lock: Int): Pair<List<GatePass>, Int> {
        val passes = mutableListOf<GatePass>()
        var previous: GpsSample? = null
        var cluster = PassageCluster()
        var armed = false
        var acceptedDirection = lock
        var lastAcceptedTime: Double? = null
        var oppositeRejected = 0
        var usable = 0

        fun discardContinuity() {
            cluster = PassageCluster()
            previous = null
            armed = false
        }

        fun finalizeCluster() {
            if (!cluster.active) return
            val duration = cluster.lastTime - cluster.firstTime
            val displacement = cluster.lastPoint - cluster.firstPoint
            val groundSpeed = if (duration > 0.0) displacement.length / duration else 0.0
            val normalSpeed = if (duration > 0.0) displacement.dot(gateNormal) / duration else 0.0
            val normalRatio = if (groundSpeed > 0.0) abs(normalSpeed) / groundSpeed else 0.0
            val accepted = when {
                !duration.isFinite() || duration <= 0.0 || duration > options.maximumClusterSeconds -> false
                cluster.firstSide == 0 || cluster.lastSide == cluster.firstSide || !cluster.reachedLineInSpan -> false
                !groundSpeed.isFinite() || groundSpeed < options.minimumGroundSpeedMetersPerSecond -> false
                !normalSpeed.isFinite() || abs(normalSpeed) < options.minimumNormalSpeedMetersPerSecond ||
                    !normalRatio.isFinite() || normalRatio < options.minimumNormalMotionRatio -> false
                else -> true
            }
            if (accepted) {
                val direction = if (normalSpeed > 0.0) 1 else -1
                if (acceptedDirection != 0 && direction != acceptedDirection) {
                    oppositeRejected++
                } else {
                    if (acceptedDirection == 0) acceptedDirection = direction
                    passes += GatePass(
                        cluster.candidateTime, cluster.candidateDistance, direction,
                        cluster.candidateGateFraction, groundSpeed, normalSpeed,
                    )
                    lastAcceptedTime = cluster.candidateTime
                }
            }
            cluster = PassageCluster()
            armed = false
        }

        /**
         * The line crossed inside a GPS gap still ends the lap, as live timing counts it (audit A11):
         * a straight chord across the gap that crosses the line within the gate, fast enough and
         * across rather than along it. Both laps then hold the gap, so neither is a reference.
         */
        fun passAcrossGap(before: GpsSample, current: GpsSample) {
            val interval = current.time - before.time
            val firstSide = sideOf(before.point)
            val lastSide = sideOf(current.point)
            if (firstSide == 0 || lastSide == firstSide || !reachesLineInSpan(before.point, current.point)) return
            val last = lastAcceptedTime
            if (last != null && current.time - last < options.refractorySeconds) return
            val displacement = current.point - before.point
            val groundSpeed = displacement.length / interval
            val normalSpeed = displacement.dot(gateNormal) / interval
            val normalRatio = if (groundSpeed > 0.0) abs(normalSpeed) / groundSpeed else 0.0
            if (!groundSpeed.isFinite() || groundSpeed < options.minimumGroundSpeedMetersPerSecond ||
                !normalSpeed.isFinite() || abs(normalSpeed) < options.minimumNormalSpeedMetersPerSecond ||
                normalRatio < options.minimumNormalMotionRatio
            ) {
                return
            }
            val direction = if (normalSpeed > 0.0) 1 else -1
            if (acceptedDirection != 0 && direction != acceptedDirection) {
                oppositeRejected++
                return
            }
            if (acceptedDirection == 0) acceptedDirection = direction
            val closest = closestSegments(before.point, current.point, gateA, gateB)
            val time = before.time + closest.vehicleFraction * interval
            passes += GatePass(time, closest.distanceMeters, direction, closest.gateFraction, groundSpeed, normalSpeed)
            lastAcceptedTime = time
        }

        for (index in 0 until sampleCount) {
            val current = gpsSampleAt(latitude, longitude, index, origin)
            if (current == null) {
                discardContinuity()
                continue
            }
            val before = previous
            if (before == null) {
                previous = current
                continue
            }
            val interval = current.time - before.time
            if (!interval.isFinite() || interval <= 0.0 || gapThreshold <= 0.0 || interval > gapThreshold) {
                discardContinuity()
                if (interval.isFinite() && interval > 0.0 && gapThreshold > 0.0) passAcrossGap(before, current)
                previous = current
                continue
            }
            usable++
            val closest = closestSegments(before.point, current.point, gateA, gateB)
            if (!closest.distanceMeters.isFinite()) {
                discardContinuity()
                previous = current
                continue
            }
            val outsideOuter = closest.distanceMeters > options.outerCorridorMeters
            if (cluster.active && outsideOuter) finalizeCluster()
            if (!cluster.active && outsideOuter) {
                val last = lastAcceptedTime
                if (last == null || current.time - last >= options.refractorySeconds) armed = true
            }
            if (armed && !cluster.active && closest.distanceMeters <= options.innerCorridorMeters) {
                cluster.active = true
                cluster.firstTime = before.time
                cluster.lastTime = current.time
                cluster.firstPoint = before.point
                cluster.lastPoint = current.point
                cluster.candidateTime = before.time + closest.vehicleFraction * interval
                cluster.candidateDistance = closest.distanceMeters
                cluster.candidateGateFraction = closest.gateFraction
                cluster.firstSide = sideOf(before.point)
                cluster.lastSide = sideOf(current.point)
                cluster.reachedLineInSpan = reachesLineInSpan(before.point, current.point)
            } else if (cluster.active && !outsideOuter) {
                cluster.lastTime = current.time
                cluster.lastPoint = current.point
                cluster.lastSide = sideOf(current.point)
                if (reachesLineInSpan(before.point, current.point)) cluster.reachedLineInSpan = true
                if (closest.distanceMeters < cluster.candidateDistance) {
                    cluster.candidateTime = before.time + closest.vehicleFraction * interval
                    cluster.candidateDistance = closest.distanceMeters
                    cluster.candidateGateFraction = closest.gateFraction
                }
            }
            previous = current
        }
        finalizeCluster()
        usableSegments = usable
        return passes to oppositeRejected
    }

    fun across(passes: List<GatePass>) = passes.sumOf { abs(it.normalSpeedMetersPerSecond) }

    var (passes, opposite) = scan(lock)
    val usableFirst = usableSegments
    if (lock == 0 && opposite > 0 && passes.isNotEmpty()) {
        val (reversed, _) = scan(-passes.first().direction)
        if (reversed.size > passes.size || (reversed.size == passes.size && across(reversed) > across(passes))) {
            passes = reversed
        } else {
            usableSegments = usableFirst
        }
    }
    if (usableSegments == 0) return GatePasses(origin, failure = LapSessionStatus.NoUsableGps)
    return GatePasses(origin, passes)
}

/** [laps] with the ones that cannot be a lap of the circuit marked [LapReferenceIssue.ImplausibleLap]. */
private fun markImplausibleLaps(laps: List<TimedLap>, options: LapDetectionOptions): List<TimedLap> {
    fun outsideLimits(lap: TimedLap): Boolean {
        val duration = lap.durationSeconds
        if (duration < options.minimumLapSeconds || duration > options.maximumLapSeconds) return true
        val distance = lap.distanceMeters ?: return false
        return distance < options.minimumLapDistanceMeters ||
            distance / duration > options.maximumAverageSpeedMetersPerSecond
    }
    val distances = laps
        .filter { it.referenceIssue == LapReferenceIssue.None && !outsideLimits(it) }
        .mapNotNull { it.distanceMeters }
        .sorted()
    val median = when {
        distances.isEmpty() -> null
        distances.size % 2 == 1 -> distances[distances.size / 2]
        else -> (distances[distances.size / 2 - 1] + distances[distances.size / 2]) / 2.0
    }
    fun implausible(lap: TimedLap): Boolean {
        if (outsideLimits(lap)) return true
        val distance = lap.distanceMeters
        return distance != null && distances.size >= 2 && median != null &&
            distance < options.minimumLapDistanceRatio * median
    }
    return laps.map {
        if (it.referenceIssue == LapReferenceIssue.None && implausible(it)) {
            it.copy(referenceIssue = LapReferenceIssue.ImplausibleLap)
        } else {
            it
        }
    }
}

/** Whether the GPS covers [lap] without a gap or invalid fix, and the length of that path. */
private fun referenceIssueForLap(
    session: TelemetrySession,
    lap: TimedLap,
    origin: GeoCoordinate,
): Pair<LapReferenceIssue, Double?> {
    val latitude = session.channel(Aliases.LATITUDE)
    val longitude = session.channel(Aliases.LONGITUDE)
    if (latitude == null || longitude == null ||
        latitude.timestamps.size != longitude.timestamps.size || latitude.timestamps.isEmpty()
    ) {
        return LapReferenceIssue.InvalidGps to null
    }
    val times = latitude.timestamps
    var first = lowerBound(times, lap.startTelemetryTime)
    if (first != 0 && (first == times.size || times[first] > lap.startTelemetryTime)) first--
    val last = lowerBound(times, lap.endTelemetryTime, first)
    if (first == times.size || times[first] > lap.startTelemetryTime || last == times.size) {
        return LapReferenceIssue.GpsGap to null
    }
    val threshold = gpsGapThreshold(latitude, longitude)
    var previous: GpsSample? = null
    var distance = 0.0
    for (index in first..last) {
        val sample = gpsSampleAt(latitude, longitude, index, origin) ?: return LapReferenceIssue.InvalidGps to null
        val p = previous
        if (p != null) {
            if (sample.time <= p.time || threshold <= 0.0 || sample.time - p.time > threshold) {
                return LapReferenceIssue.GpsGap to null
            }
            distance += (sample.point - p.point).length
        }
        previous = sample
    }
    return LapReferenceIssue.None to (if (distance.isFinite()) distance else null)
}

private const val MaximumPointsPerLapTrace = 4096

/**
 * The path of every eligible lap, in metres around [origin]: the interpolated position at the
 * start, the recorded fixes in between (thinned evenly to at most 4,096 points), and the
 * interpolated position at the end.
 */
fun buildLapTraces(session: TelemetrySession, laps: List<TimedLap>, origin: GeoCoordinate): List<LapTrace> {
    val latitude = session.channel(Aliases.LATITUDE) ?: return emptyList()
    val longitude = session.channel(Aliases.LONGITUDE) ?: return emptyList()

    fun appendInterpolated(points: MutableList<LapTracePoint>, time: Double) {
        // The same floor as lap detection (FBBD-215): a lap it keeps across a few missed fixes at a
        // gate still starts and ends on the gate.
        val lat = session.valueAt(Aliases.LATITUDE, time, minimumGapSeconds = MinimumGpsGapSeconds) ?: return
        val lon = session.valueAt(Aliases.LONGITUDE, time, Interpolation.Longitude, MinimumGpsGapSeconds) ?: return
        val c = GeoCoordinate(lat, lon)
        if (!isValidCoordinate(c)) return
        val p = projectCoordinate(c, origin)
        if (!p.eastMeters.isFinite() || !p.northMeters.isFinite()) return
        if (points.isNotEmpty() && abs(points.last().telemetryTime - time) <= 1e-9) return
        points += LapTracePoint(time, p.eastMeters, p.northMeters)
    }

    val traces = mutableListOf<LapTrace>()
    val times = latitude.timestamps
    for (lap in laps) {
        if (!lap.referenceEligible) continue
        val points = mutableListOf<LapTracePoint>()
        appendInterpolated(points, lap.startTelemetryTime)
        val first = upperBound(times, lap.startTelemetryTime)
        val end = lowerBound(times, lap.endTelemetryTime, first)
        val stride = ((end - first + MaximumPointsPerLapTrace - 3) / (MaximumPointsPerLapTrace - 2)).coerceAtLeast(1)
        var index = first
        var ordinal = 0
        while (index < times.size && times[index] < lap.endTelemetryTime) {
            val sameFix = index < longitude.timestamps.size && times[index] == longitude.timestamps[index]
            if (ordinal % stride == 0 && sameFix) {
                val c = GeoCoordinate(latitude.values[index], longitude.values[index])
                if (isValidCoordinate(c)) {
                    val p = projectCoordinate(c, origin)
                    if (p.eastMeters.isFinite() && p.northMeters.isFinite()) {
                        points += LapTracePoint(times[index], p.eastMeters, p.northMeters)
                    }
                }
            }
            index++
            ordinal++
        }
        appendInterpolated(points, lap.endTelemetryTime)
        if (points.size >= 2) traces += LapTrace(lap.number, lap.startTelemetryTime, lap.durationSeconds, points)
    }
    return traces
}
