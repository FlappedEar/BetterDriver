package com.flappedear.betterdriver.analysis.map

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.StandardGravity
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.accelerationAliasInG
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.lowerBound
import com.flappedear.betterdriver.analysis.data.metresPerSecondPerSpeedUnit
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.hypot
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.progress.progressAtTime
import kotlin.math.atan2
import kotlin.math.roundToInt

/** The G-G diagram reads a lap every this many seconds. */
const val GgStepSeconds = 0.1

/** GPS positions and speed are averaged over this many seconds either side before differencing. */
const val GpsSmoothingSeconds = 0.3

/** GPS acceleration is the change over this many seconds either side. */
const val GpsDifferenceSeconds = 0.3

/** Accelerometer readings are averaged over this many seconds either side (engine and road vibration). */
const val AccelerometerSmoothingSeconds = 0.2

/** Below this speed the direction of travel is GPS noise, so no acceleration is derived. */
const val MinimumGgSpeedMetersPerSecond = 5.0

/** The accelerometer is used when it covers at least this share of the lap; else GPS. */
internal const val MinimumAccelerometerCoverage = 0.5

/** Seconds: readings this close to the lap's ends are within it. */
private const val TimeTolerance = 1e-9

private const val KmhToMetersPerSecond = 1 / 3.6

/** Where a G-G diagram's accelerations come from. */
enum class GgSource { Accelerometer, Gps }

/**
 * One reading: acceleration across the car (positive: toward the left, as in a left turn) and
 * along it (positive: speeding up), in g, at a distance on the session's axis (null off it).
 */
data class GgPoint(val lateralG: Double, val longitudinalG: Double, val distanceMeters: Double?)

/** One lap's accelerations for the G-G (traction circle) diagram. */
data class GgTrace(val source: GgSource, val points: List<GgPoint>)

/** A moment of a lap: position in metres and ground speed in m/s (null when not recorded). */
data class KinematicSample(val time: Double, val eastMeters: Double, val northMeters: Double, val speed: Double?)

/** Acceleration in g at [time]: across the car (positive left) and along it (positive speeding up). */
data class TimedG(val time: Double, val lateralG: Double, val longitudinalG: Double)

/**
 * Timed lap [lapIndex] of [analysis] as a G-G trace from [session]: from the accelerometer when
 * the session has both its longitudinal and lateral channels over most of the lap, else derived
 * from GPS speed and heading. An accelerometer channel is turned round when it runs against the
 * GPS-derived acceleration, so its mounting and sign convention do not matter. Null without data.
 */
@Suppress("ReturnCount")
fun lapGg(analysis: SessionAnalysis, session: TelemetrySession, lapIndex: Int): GgTrace? {
    val lap = analysis.laps.timedLaps.getOrNull(lapIndex) ?: return null
    val origin = analysis.axis?.origin ?: firstPosition(session, lap.startTelemetryTime) ?: return null
    val trace = analysis.traces.getOrNull(lapIndex)
    val steps = ((lap.endTelemetryTime - lap.startTelemetryTime) / GgStepSeconds).toInt()
    if (steps < 2) return null
    val times = DoubleArray(steps + 1) { lap.startTelemetryTime + it * GgStepSeconds }
    // GPS is read a little either side of the lap, so its smoothing reaches the lap's ends.
    val margin = gpsMarginSteps(GgStepSeconds)
    val around = (-margin..steps + margin).map {
        kinematicAt(session, lap.startTelemetryTime + it * GgStepSeconds, origin)
    }
    val within = (times.first() - TimeTolerance)..(times.last() + TimeTolerance)
    val gps = gpsG(around, GgStepSeconds).filter { it.time in within }
    val distance = { t: Double -> trace?.let { progressAtTime(it, t) } }
    accelerometerG(session, times, gps)?.let { measured ->
        val points = measured.map { GgPoint(it.lateralG, it.longitudinalG, distance(it.time)) }
        return GgTrace(GgSource.Accelerometer, points)
    }
    if (gps.isEmpty()) return null
    return GgTrace(GgSource.Gps, gps.map { GgPoint(it.lateralG, it.longitudinalG, distance(it.time)) })
}

@Suppress("ReturnCount")
internal fun firstPosition(session: TelemetrySession, time: Double): GeoCoordinate? {
    val lat = session.valueAt(Aliases.LATITUDE, time) ?: return null
    val lon = session.valueAt(Aliases.LONGITUDE, time, Interpolation.Longitude) ?: return null
    return GeoCoordinate(lat, lon)
}

internal fun kinematicAt(session: TelemetrySession, time: Double, origin: GeoCoordinate): KinematicSample? {
    val position = firstPosition(session, time)?.let { projectCoordinate(it, origin) } ?: return null
    val speed = session.channel(Aliases.SPEED)?.let { channel ->
        val unit = effectiveChannelUnit(session, channel.name)
        channel.valueAt(time)?.let { it * (metresPerSecondPerSpeedUnit(unit) ?: KmhToMetersPerSecond) }
    }
    return KinematicSample(time, position.eastMeters, position.northMeters, speed)
}

/**
 * Accelerations derived from [samples], taken every [stepSeconds] (null: no fix, which splits the
 * lap into runs). Positions and speeds are smoothed over [smoothingSeconds] either side; the
 * longitudinal acceleration is the change in speed over [differenceSeconds] either side, the
 * lateral one is speed times the turn rate of the direction of travel over the same span. Speed
 * comes from the speed channel, else from the positions. Nothing is derived below
 * [MinimumGgSpeedMetersPerSecond] or within the smoothing and difference spans of a run's ends.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
fun gpsG(
    samples: List<KinematicSample?>,
    stepSeconds: Double,
    smoothingSeconds: Double = GpsSmoothingSeconds,
    differenceSeconds: Double = GpsDifferenceSeconds,
): List<TimedG> {
    val result = mutableListOf<TimedG>()
    val k = (smoothingSeconds / stepSeconds).roundToInt()
    val m = maxOf(1, (differenceSeconds / stepSeconds).roundToInt())
    for (run in runsOf(samples)) {
        val n = run.size
        // Near a run's end the smoothing window is one-sided, which bends the line: left out.
        val edge = k + m + 1
        if (n <= 2 * edge) continue
        val east = movingAverage(DoubleArray(n) { run[it].eastMeters }, k)
        val north = movingAverage(DoubleArray(n) { run[it].northMeters }, k)
        // Velocity by central difference of the smoothed positions.
        val ve = centralDifference(east, stepSeconds)
        val vn = centralDifference(north, stepSeconds)
        val recorded = run.all { it.speed != null }
        val speed = movingAverage(DoubleArray(n) { if (recorded) run[it].speed!! else hypot(ve[it], vn[it]) }, k)
        for (i in edge until n - edge) {
            val v = speed[i]
            if (!(v >= MinimumGgSpeedMetersPerSecond)) continue
            val before = i - m
            val after = i + m
            if (hypot(ve[before], vn[before]) <= 0.0 || hypot(ve[after], vn[after]) <= 0.0) continue
            val seconds = 2 * m * stepSeconds
            // The signed angle the direction of travel turns through, positive to the left.
            val cross = ve[before] * vn[after] - vn[before] * ve[after]
            val dot = ve[before] * ve[after] + vn[before] * vn[after]
            val turn = atan2(cross, dot)
            result += TimedG(
                run[i].time,
                v * turn / seconds / StandardGravity,
                (speed[after] - speed[before]) / seconds / StandardGravity,
            )
        }
    }
    return result
}

/** How many steps of [stepSeconds] near a run's ends [gpsG] leaves out. */
internal fun gpsMarginSteps(stepSeconds: Double) =
    (GpsSmoothingSeconds / stepSeconds).roundToInt() + maxOf(1, (GpsDifferenceSeconds / stepSeconds).roundToInt()) + 1

/** The rate of change of [values] taken every [stepSeconds]: central inside, one-sided at the ends. */
private fun centralDifference(values: DoubleArray, stepSeconds: Double): DoubleArray {
    val n = values.size
    return DoubleArray(n) {
        val from = maxOf(0, it - 1)
        val to = minOf(n - 1, it + 1)
        (values[to] - values[from]) / ((to - from) * stepSeconds)
    }
}

private fun runsOf(samples: List<KinematicSample?>): List<List<KinematicSample>> {
    val runs = mutableListOf<List<KinematicSample>>()
    var run = mutableListOf<KinematicSample>()
    for (s in samples) {
        if (s != null) {
            run += s
        } else if (run.isNotEmpty()) {
            runs += run
            run = mutableListOf()
        }
    }
    if (run.isNotEmpty()) runs += run
    return runs
}

/** Each value averaged with up to [half] neighbours either side (fewer at the ends). */
fun movingAverage(values: DoubleArray, half: Int): DoubleArray {
    if (half <= 0) return values.copyOf()
    val n = values.size
    val prefix = DoubleArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]
    return DoubleArray(n) {
        val from = maxOf(0, it - half)
        val to = minOf(n - 1, it + half)
        (prefix[to + 1] - prefix[from]) / (to - from + 1)
    }
}

/**
 * The accelerometer's readings at [times], smoothed, or null when the session lacks a longitudinal
 * or lateral channel in g or they cover less than half of [times]. Each axis is turned round when
 * it runs against [gps], the same lap's GPS-derived accelerations.
 */
@Suppress("ReturnCount")
internal fun accelerometerG(session: TelemetrySession, times: DoubleArray, gps: List<TimedG>): List<TimedG>? {
    val longitudinal = accelerationAliasInG(session, Aliases.LONGITUDINAL_ACCELERATION)?.takeIf { it.supported }
    val lateral = accelerationAliasInG(session, Aliases.LATERAL_ACCELERATION)?.takeIf { it.supported }
    if (longitudinal == null || lateral == null) return null
    val half = AccelerometerSmoothingSeconds
    val readings = times.asList().mapNotNull { t ->
        val x = smoothedAt(longitudinal.channel, t, half) ?: return@mapNotNull null
        val y = smoothedAt(lateral.channel, t, half) ?: return@mapNotNull null
        TimedG(t, y, x)
    }
    if (readings.size < times.size * MinimumAccelerometerCoverage) return null
    // Which way round each axis reads, from its agreement with GPS at the same moments.
    val byTime = gps.associateBy { it.time }
    var lateralAgreement = 0.0
    var longitudinalAgreement = 0.0
    for (r in readings) {
        val g = byTime[r.time] ?: continue
        lateralAgreement += r.lateralG * g.lateralG
        longitudinalAgreement += r.longitudinalG * g.longitudinalG
    }
    val lateralSign = if (lateralAgreement < 0.0) -1.0 else 1.0
    val longitudinalSign = if (longitudinalAgreement < 0.0) -1.0 else 1.0
    return readings.map { r -> TimedG(r.time, r.lateralG * lateralSign, r.longitudinalG * longitudinalSign) }
}

/** The mean of [channel]'s samples within [half] seconds of [time], or its value there; null without one. */
internal fun smoothedAt(channel: TelemetryChannel, time: Double, half: Double): Double? {
    val ts = channel.timestamps
    var i = lowerBound(ts, time - half)
    var sum = 0.0
    var count = 0
    while (i < ts.size && ts[i] <= time + half) {
        val v = channel.values[i]
        if (v.isFinite()) {
            sum += v
            count++
        }
        i++
    }
    return if (count > 0) sum / count else channel.valueAt(time)
}
