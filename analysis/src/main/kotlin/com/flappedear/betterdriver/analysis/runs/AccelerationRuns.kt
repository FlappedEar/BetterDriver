package com.flappedear.betterdriver.analysis.runs

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.StandardGravity
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.accelerationAliasInG
import com.flappedear.betterdriver.analysis.data.effectiveChannelUnit
import com.flappedear.betterdriver.analysis.data.lowerBound
import com.flappedear.betterdriver.analysis.data.metresPerSecondPerSpeedUnit
import com.flappedear.betterdriver.analysis.data.upperBound
import kotlin.math.max
import kotlin.math.sqrt

// Acceleration runs found in a saved session (FBBD-154), as a drag timer such as the Catalyst 2
// reports them: standing starts to a speed or a distance, and a rolling speed band. Everything is
// read from GNSS speed against sample time, with straight lines between samples.

private const val KmhPerMps = 3.6

/** One mile in metres. */
private const val MileMeters = 1609.344

/** One mile per hour in km/h. */
private const val KmhPerMph = MileMeters / 1000.0

private const val Kmh100 = 100.0
private const val Kmh200 = 200.0
private const val Mph60 = 60.0 * KmhPerMph
private const val EighthMileMeters = MileMeters / 8
private const val QuarterMileMeters = MileMeters / 4

/** What a run measures. Standing runs start from a standstill; a band may start rolling. */
enum class AccelerationRunKind(
    /** Speed the run starts at, km/h: 0 for a standing start. */
    val fromKmh: Double,
    /** Speed that ends the run, km/h; null for a distance run. */
    val toKmh: Double?,
    /** Distance that ends the run, metres; null for a speed run. */
    val distanceMeters: Double?,
) {
    ZeroTo100Kmh(0.0, Kmh100, null),
    From100To200Kmh(Kmh100, Kmh200, null),
    ZeroTo60Mph(0.0, Mph60, null),
    EighthMile(0.0, null, EighthMileMeters),
    QuarterMile(0.0, null, QuarterMileMeters),
    ;

    val standing: Boolean get() = fromKmh == 0.0
}

/** Why a run's time is less certain than the receiver allows at its best. */
enum class RunQualityIssue {
    /** GNSS speed came at under [MinimumRateHz] during the run. */
    LowRate,

    /** The receiver's horizontal accuracy was worse than [MaximumAccuracyMeters] during the run. */
    PoorAccuracy,

    /** Two speed samples inside the run are more than [MaximumInnerGapSeconds] apart. */
    Gap,
}

/**
 * One acceleration run. Times are session seconds; [timeSeconds] is [endSeconds] minus
 * [startSeconds]. [trapSpeedKmh] is the speed at the finish of a distance run, null for a speed
 * run. [peakG] is the largest forward acceleration during the run in g: from the session's
 * longitudinal acceleration channel when it has samples there, else from the change in GNSS speed;
 * null when neither can be read. A run with [quality] issues is less certain, not wrong.
 */
data class AccelerationRun(
    val kind: AccelerationRunKind,
    val startSeconds: Double,
    val endSeconds: Double,
    val trapSpeedKmh: Double?,
    val peakG: Double?,
    val quality: Set<RunQualityIssue>,
) {
    val timeSeconds: Double get() = endSeconds - startSeconds
    val lowQuality: Boolean get() = quality.isNotEmpty()
}

/** Below this speed, km/h, the car is standing. */
const val StandstillKmh = 1.0

/** A standing start needs the car below [StandstillKmh] for at least this long, seconds. */
const val StandstillSeconds = 1.0

/** A drop in speed of more than this below the run's highest, km/h, is braking or a lift: the run ends. */
const val InterruptionDropKmh = 3.0

/**
 * One foot, metres: the rollout a drag strip allows before its timer starts. Pass it as
 * `rolloutMeters` to time standing runs from the moment the car has moved this far, as a strip
 * does; the default is no rollout, timing from the first movement.
 */
const val OneFootRolloutMeters = 0.3048

/** Speed at under this rate, Hz, makes a run [RunQualityIssue.LowRate]. */
const val MinimumRateHz = 10.0

/** Horizontal accuracy worse than this, metres, makes a run [RunQualityIssue.PoorAccuracy]. */
const val MaximumAccuracyMeters = 2.0

/** Two speed samples farther apart than this, seconds, inside a run make it [RunQualityIssue.Gap]. */
const val MaximumInnerGapSeconds = 0.3

/** A median interval this much longer than 1/[MinimumRateHz] still counts as that rate (clock jitter). */
private const val RateTolerance = 1.05

/** Speed is never bridged across a gap longer than this many seconds (or the channel's own gap threshold). */
private const val BreakGapSeconds = 1.0

/** Peak G from speed alone is the slope over at least this many seconds, to keep GNSS noise out. */
private const val SpeedSlopeSeconds = 0.3

/** A speed sample, time in session seconds and speed in km/h. */
private class Point(val t: Double, val kmh: Double)

/**
 * Every acceleration run in [session], best first within each kind (runs without a quality issue
 * ahead of those with one, then quickest first), kinds in [AccelerationRunKind] order. Empty
 * without a speed channel in a known unit.
 *
 * A standing run starts after the car has been below [StandstillKmh] for [StandstillSeconds]. Its
 * start is interpolated at the last sample below that speed: the line through it and the first
 * sample above is followed back to zero speed, but never before the last sample reading zero. With
 * [rolloutMeters] above zero the timer starts once the car has covered that distance, and the
 * distances count from there. A speed band starts at the last rising crossing of its lower speed
 * before the upper one. Any run ends without a time when speed drops more than
 * [InterruptionDropKmh] below its highest, when a standing car stops again, or at a loss of signal.
 */
@Suppress("ReturnCount")
fun findAccelerationRuns(session: TelemetrySession, rolloutMeters: Double = 0.0): List<AccelerationRun> {
    val speed = session.channel(Aliases.SPEED) ?: return emptyList()
    val unit = effectiveChannelUnit(session, speed.name)
    val mps = metresPerSecondPerSpeedUnit(unit) ?: return emptyList()
    val points = ArrayList<Point>(speed.sampleCount)
    for (i in speed.timestamps.indices) {
        val v = speed.values[i]
        if (v.isFinite()) points += Point(speed.timestamps[i], max(0.0, v * mps * KmhPerMps))
    }
    if (points.size < 2) return emptyList()
    val reader = RunReader(
        points = points,
        breakGap = speed.gapThreshold(BreakGapSeconds),
        accuracy = session.channel(Aliases.ACCURACY),
        acceleration = accelerationAliasInG(session, Aliases.LONGITUDINAL_ACCELERATION)
            ?.takeIf { it.supported }?.channel,
    )
    val runs = reader.standingRuns(max(0.0, rolloutMeters)) + reader.bandRuns(AccelerationRunKind.From100To200Kmh)
    return runs.sortedWith(
        compareBy<AccelerationRun> { it.kind.ordinal }.thenBy { it.lowQuality }.thenBy { it.timeSeconds },
    )
}

private class RunReader(
    val points: List<Point>,
    val breakGap: Double,
    val accuracy: TelemetryChannel?,
    val acceleration: TelemetryChannel?,
) {
    private val standingKinds = AccelerationRunKind.entries.filter { it.standing }
    private val times = DoubleArray(points.size) { points[it].t }

    private fun gapBefore(i: Int) = i > 0 && points[i].t - points[i - 1].t > breakGap

    /** Every standing run: one per kind reached from each standing start. */
    @Suppress("LoopWithTooManyJumpStatements")
    fun standingRuns(rolloutMeters: Double): List<AccelerationRun> {
        val runs = mutableListOf<AccelerationRun>()
        var stillSince: Double? = null
        var i = 0
        while (i < points.size) {
            if (gapBefore(i)) stillSince = null
            val p = points[i]
            if (p.kmh < StandstillKmh) {
                if (stillSince == null) stillSince = p.t
                i++
                continue
            }
            val since = stillSince
            val launches = i > 0 && !gapBefore(i) && points[i - 1].kmh < StandstillKmh
            if (since == null || !launches || points[i - 1].t - since < StandstillSeconds) {
                stillSince = null
                i++
                continue
            }
            val launch = follow(i, since, rolloutMeters)
            runs += launch.runs
            // Back at a standstill without a run: the car never left it, so it keeps counting.
            if (!launch.stopped) stillSince = null
            i = launch.next
        }
        return runs
    }

    private class Launch(val runs: List<AccelerationRun>, val next: Int, val stopped: Boolean)

    /** The run that leaves a standstill at sample [first], the first one at or above [StandstillKmh]. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
    private fun follow(first: Int, stillSince: Double, rolloutMeters: Double): Launch {
        val below = points[first - 1]
        val above = points[first]
        val slope = (above.kmh - below.kmh) / (above.t - below.t)
        // Never before the last sample that reads the car stopped, nor before the standstill began.
        var still = first - 1
        while (still > 0 && points[still].kmh > 0.0 && points[still - 1].t >= stillSince) still--
        val floor = points[still].t
        val zero = if (slope > 0.0) below.t - below.kmh / slope else below.t
        // The line from zero speed at the start to the first moving sample stands in for the samples
        // below the standstill speed, so the distance and speed of the first instants are continuous.
        val path = mutableListOf(Point(zero.coerceIn(floor, below.t), 0.0))
        var top = above.kmh
        var j = first
        var stopped = false
        val lastTarget = standingKinds.maxOf { it.distanceMeters ?: 0.0 }
        val topSpeed = standingKinds.maxOf { it.toKmh ?: 0.0 }
        var distance = 0.0
        while (j < points.size) {
            val p = points[j]
            if (j > first && gapBefore(j)) break
            if (p.kmh < StandstillKmh) {
                stopped = true
                break
            }
            if (p.kmh < top - InterruptionDropKmh) break
            top = max(top, p.kmh)
            val last = path.last()
            distance += (last.kmh + p.kmh) / 2 / KmhPerMps * (p.t - last.t)
            path += p
            j++
            if (distance >= lastTarget + rolloutMeters && top >= topSpeed) break
        }
        if (stopped) return Launch(emptyList(), j, stopped = true)
        val start = if (rolloutMeters > 0.0) timeAtDistance(path, rolloutMeters) else path.first().t
        val runs = if (start == null) {
            emptyList()
        } else {
            standingKinds.mapNotNull { kind ->
                val end = kind.toKmh?.let { timeAtSpeed(path, it) }
                    ?: kind.distanceMeters?.let { timeAtDistance(path, it + rolloutMeters) }
                    ?: return@mapNotNull null
                val trap = if (kind.distanceMeters != null) speedAt(path, end) else null
                run(kind, start, end, trap, path)
            }
        }
        return Launch(runs, j, stopped = false)
    }

    /** Every [kind] run between two speeds within one continuous acceleration, rolling or not. */
    fun bandRuns(kind: AccelerationRunKind): List<AccelerationRun> {
        val low = kind.fromKmh
        val high = kind.toKmh ?: return emptyList()
        val runs = mutableListOf<AccelerationRun>()
        var startedAt = -1
        var start = 0.0
        var top = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (gapBefore(i)) {
                startedAt = -1
                continue
            }
            if (startedAt >= 0) {
                if (b.kmh < top - InterruptionDropKmh) startedAt = -1 else top = max(top, b.kmh)
            }
            // The last rising crossing of the lower speed counts: a dip back below it starts over.
            if (a.kmh < low && b.kmh >= low) {
                startedAt = i - 1
                start = crossing(a, b, low)
                top = b.kmh
            }
            if (startedAt >= 0 && a.kmh < high && b.kmh >= high) {
                val path = points.subList(startedAt, i + 1)
                runs += run(kind, start, crossing(a, b, high), null, path)
                startedAt = -1
            }
        }
        return runs
    }

    private fun run(kind: AccelerationRunKind, start: Double, end: Double, trap: Double?, path: List<Point>) =
        AccelerationRun(kind, start, end, trap, peakG(start, end, path), quality(start, end))

    /** Issues of the speed samples and the accuracy from [start] to [end]. */
    private fun quality(start: Double, end: Double): Set<RunQualityIssue> {
        val issues = mutableSetOf<RunQualityIssue>()
        // The samples around the run as well, so a gap at either end of it counts.
        val from = (upperBound(times, start) - 1).coerceAtLeast(0)
        val to = lowerBound(times, end).coerceAtMost(points.size - 1)
        val intervals = (from + 1..to).map { times[it] - times[it - 1] }
        if (intervals.any { it > MaximumInnerGapSeconds }) issues += RunQualityIssue.Gap
        val median = intervals.sorted().getOrNull(intervals.size / 2)
        if (median != null && median > RateTolerance / MinimumRateHz) issues += RunQualityIssue.LowRate
        accuracy?.let { channel ->
            val inside = (lowerBound(channel.timestamps, start) until upperBound(channel.timestamps, end))
                .map { channel.values[it] } + listOfNotNull(channel.valueAt(start), channel.valueAt(end))
            if (inside.any { it.isFinite() && it > MaximumAccuracyMeters }) issues += RunQualityIssue.PoorAccuracy
        }
        return issues
    }

    /** The largest forward acceleration from [start] to [end], g. */
    private fun peakG(start: Double, end: Double, path: List<Point>): Double? {
        acceleration?.let { channel ->
            val inside = (lowerBound(channel.timestamps, start) until upperBound(channel.timestamps, end))
                .map { channel.values[it] }.filter { it.isFinite() }
            if (inside.isNotEmpty()) return inside.max()
        }
        val within = path.filter { it.t >= start && it.t <= end }
        var peak: Double? = null
        var j = 0
        for ((i, a) in within.withIndex()) {
            j = max(j, i + 1)
            while (j < within.size && within[j].t - a.t < SpeedSlopeSeconds) j++
            if (j == within.size) break
            val b = within[j]
            val g = (b.kmh - a.kmh) / KmhPerMps / (b.t - a.t) / StandardGravity
            peak = max(peak ?: g, g)
        }
        return peak
    }
}

/** Where speed along a's and b's line reaches [kmh]. */
private fun crossing(a: Point, b: Point, kmh: Double) = a.t + (kmh - a.kmh) / (b.kmh - a.kmh) * (b.t - a.t)

/** When speed along [path] first reaches [kmh], or null. */
private fun timeAtSpeed(path: List<Point>, kmh: Double): Double? {
    for (i in 1 until path.size) {
        val a = path[i - 1]
        val b = path[i]
        if (a.kmh < kmh && b.kmh >= kmh) return crossing(a, b, kmh)
    }
    return null
}

/**
 * When the distance covered along [path] from its first point reaches [meters], or null. Speed is
 * a straight line between points, so the distance within one interval is a quadratic in time.
 */
private fun timeAtDistance(path: List<Point>, meters: Double): Double? {
    var covered = 0.0
    for (i in 1 until path.size) {
        val a = path[i - 1]
        val b = path[i]
        val span = b.t - a.t
        val va = a.kmh / KmhPerMps
        val vb = b.kmh / KmhPerMps
        val step = (va + vb) / 2 * span
        if (covered + step >= meters) {
            val rest = meters - covered
            val k = (vb - va) / span
            // Solves k/2·τ² + va·τ = rest in a form that holds for k = 0 too.
            val root = sqrt(max(0.0, va * va + 2 * k * rest))
            return a.t + if (va + root > 0.0) 2 * rest / (va + root) else 0.0
        }
        covered += step
    }
    return null
}

/** Speed along [path] at [t], km/h. */
private fun speedAt(path: List<Point>, t: Double): Double? {
    for (i in 1 until path.size) {
        val a = path[i - 1]
        val b = path[i]
        if (t >= a.t && t <= b.t) return a.kmh + (b.kmh - a.kmh) * (t - a.t) / (b.t - a.t)
    }
    return null
}
