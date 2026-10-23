package com.flappedear.betterdriver.analysis.line

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Targets beyond his own best (FBBD-312). The line plan's target through a corner is a pass he has
// driven (FBBD-306); once his best lap matches it, the plan has nothing left to ask there. What is
// left is what the car did for him elsewhere on the same track the same day. Braking is the one
// measure taken here as advice: the car stops about as hard in every corner of a flat track, so a
// corner he brakes in at half the deceleration he reached in two others has room, and the room is
// a braking point. Grip at the slowest point is measured too, but not advised: a corner's grip
// depends on its camber and surface, and the line's curvature from GNSS positions is noisier than
// its speed, so it is not a high-confidence target (FBBD-177).

/** Braking only counts where the pass loses at least this much speed into the corner. */
const val BrakingDropKmh = 10.0

/**
 * A corner's braking has room when its peak is at least this far under the track's: the peak read
 * over 12 m of GNSS speed repeats to about 0.1 g lap to lap.
 */
const val BrakeRoomG = 0.2

/** One step of a braking point at a time: about two car lengths, then the next session proves it. */
const val BrakeStepMeters = 10.0

/** A step shorter than this is not worth saying: the braking point moves this much lap to lap. */
const val MinimumBrakeStepMeters = 5.0

/**
 * At most this many times his own average deceleration in a corner: the estimate stays within
 * what the same pedal can plausibly do, whatever the peak ratio says.
 */
const val MaximumBrakeScale = 1.5

/** Speed and acceleration are read over this many line points either side (12 m at [LineStepMeters]). */
private const val GSpanPoints = 3

/** The grip at the slowest point is the highest within this distance of it. */
private const val ApexReachMeters = 10.0

/** Keeps a division by a speed or a deceleration finite. */
private const val Tiny = 1e-6

private const val StandardGravity = 9.80665
private const val KmhPerMetrePerSecond = 3.6

/** How one pass uses the car through a corner's window, read from its line (GNSS speed and position). */
data class PassDynamics(
    /** Where braking starts: the fastest point before the slowest, metres along the axis. */
    val brakeStartMeters: Double,
    val entryKmh: Double,
    val minimumKmh: Double,
    /** Distance driven from [brakeStartMeters] to the slowest point. */
    val brakingMeters: Double,
    /** The hardest deceleration in that stretch, and its average, in g (positive). */
    val peakBrakeG: Double,
    val averageBrakeG: Double,
    /** The highest combined (lateral and longitudinal) acceleration near the slowest point, in g. */
    val apexG: Double,
) {
    /** Whether the pass brakes for the corner at all ([BrakingDropKmh]). */
    val brakes: Boolean get() = entryKmh - minimumKmh >= BrakingDropKmh && brakingMeters > 0.0
}

/** What the car did for him on this track: the second hardest of each, so it was done twice (FBBD-177). */
data class TrackLimits(
    val brakeG: Double,
    /** The corners whose braking sets [brakeG]: the hardest and the one that confirms it. */
    val brakeCorners: List<String>,
    val apexG: Double,
    val apexCorners: List<String>,
)

/**
 * A braking point to move: [laterMeters] later than the target pass, braking harder to the same
 * slowest speed, worth about [seconds]. [fullLaterMeters] and [fullSeconds] are what braking at the
 * track's limit all the way would give; the step is the first part of it.
 */
data class BrakeRoom(
    val peakG: Double,
    val trackG: Double,
    val laterMeters: Double,
    val seconds: Double,
    val fullLaterMeters: Double,
    val fullSeconds: Double,
)

/** One corner beyond his best: its braking room (the advice) and how much of the grip it uses (measured only). */
data class CornerBeyond(
    val segmentId: String,
    val name: String,
    /** The pass the room is measured on: the corner's driven target. */
    val target: LinePass,
    val dynamics: PassDynamics,
    /** Passes that agree: the target and every other pass given showed the same room. */
    val passes: Int,
    val brake: BrakeRoom?,
    val apexG: Double,
    val trackApexG: Double,
)

/** Every corner whose driven target has nothing left, with what the track's limits leave there. */
data class BeyondBest(val limits: TrackLimits, val corners: List<CornerBeyond>) {
    /** Corners with a braking point to move, most time first. */
    val ranked: List<CornerBeyond>
        get() = corners.filter { it.brake != null }.sortedByDescending { it.brake!!.seconds }

    /** The steps one after the other: what the next session can win if each is driven. */
    val stepSeconds: Double get() = corners.sumOf { it.brake?.seconds ?: 0.0 }
}

/** One corner as [beyondBest] reads it: the driven target, and other passes that must agree. */
data class CornerPasses(
    val segmentId: String,
    val name: String,
    val target: LinePass,
    val others: List<LinePass>,
    /** Time to find against the driven target; beyond-best targets are only for corners with none. */
    val gainSeconds: Double,
)

/**
 * Beyond-best targets for [corners] of one track: the track's limits from every corner's target pass,
 * and for each corner whose driven target has nothing left ([CornerPasses.gainSeconds] 0) the braking
 * point to move when its target and every other pass brake at least [BrakeRoomG] under the track's
 * limit. Null with fewer than two braking corners (no confirmed limit).
 */
@Suppress("ReturnCount")
fun beyondBest(corners: List<CornerPasses>): BeyondBest? {
    val read = corners.mapNotNull { c -> passDynamics(c.target)?.let { c to it } }
    val braking = read.filter { it.second.brakes }.sortedByDescending { it.second.peakBrakeG }
    val apex = read.sortedByDescending { it.second.apexG }
    if (braking.size < 2 || apex.size < 2) return null
    val limits = TrackLimits(
        braking[1].second.peakBrakeG, braking.take(2).map { it.first.name },
        apex[1].second.apexG, apex.take(2).map { it.first.name },
    )
    val beyond = read.filter { it.first.gainSeconds <= 0.0 }.map { (c, d) ->
        val others = c.others.mapNotNull { passDynamics(it) }
        val room = d.brakes && d.peakBrakeG <= limits.brakeG - BrakeRoomG &&
            others.all { it.brakes && it.peakBrakeG <= limits.brakeG - BrakeRoomG }
        CornerBeyond(
            c.segmentId, c.name, c.target, d, 1 + others.size,
            if (room) brakeRoom(c.target, d, limits.brakeG) else null,
            d.apexG, limits.apexG,
        )
    }
    return BeyondBest(limits, beyond)
}

/** The line plan's corners: the target against the session's fastest lap. */
fun LineAnalysis.beyondBest(): BeyondBest? =
    beyondBest(corners.map { CornerPasses(it.segmentId, it.name, it.target, listOf(it.reference), it.gainSeconds) })

/**
 * The day's corners: the target against the day's fastest lap and its typical pass, so the room
 * is there on every lap, not on one.
 */
fun DayAnalysis.beyondBest(): BeyondBest? = beyondBest(
    corners.map {
        CornerPasses(it.segmentId, it.name, it.target, listOfNotNull(it.bestLap, it.typical), it.bestLapGainSeconds)
    },
)

/** Speed (m/s), distance driven (m) and the axis position of each line point with a speed. */
private class Profile(val progress: DoubleArray, val s: DoubleArray, val v: DoubleArray, val g: DoubleArray) {
    val size get() = v.size

    /** Seconds from point [from] to [to] at speeds [speed]. */
    fun seconds(speed: DoubleArray, from: Int = 0, to: Int = size - 1): Double =
        (from + 1..to).sumOf { i -> 2 * (s[i] - s[i - 1]) / max(speed[i] + speed[i - 1], Tiny) }
}

@Suppress("ReturnCount")
private fun profile(pass: LinePass): Profile? {
    val points = pass.line.filter { it.speedKmh != null }
    if (points.size < 2 * GSpanPoints + 2) return null
    val n = points.size
    val s = DoubleArray(n)
    for (i in 1 until n) {
        s[i] = s[i - 1] + distance(points[i - 1], points[i])
    }
    val v = DoubleArray(n) { points[it].speedKmh!! / KmhPerMetrePerSecond }
    // Combined acceleration: lateral from the speed and the heading's change, longitudinal from the speed's.
    val g = DoubleArray(n)
    val k = GSpanPoints
    for (i in k until n - k) {
        val turn = wrap(heading(points[i], points[i + k]) - heading(points[i - k], points[i]))
        val span = s[i + k] - s[i - k]
        if (span <= 0.0) continue
        val lateral = v[i] * v[i] * abs(turn) / (span / 2)
        val longitudinal = (v[i + k] * v[i + k] - v[i - k] * v[i - k]) / (2 * span)
        g[i] = hypot(lateral, longitudinal) / StandardGravity
    }
    return Profile(DoubleArray(n) { points[it].progressMeters }, s, v, g)
}

private fun distance(a: LinePoint, b: LinePoint) = hypot(b.eastMeters - a.eastMeters, b.northMeters - a.northMeters)

private fun heading(a: LinePoint, b: LinePoint) = atan2(b.northMeters - a.northMeters, b.eastMeters - a.eastMeters)

private fun wrap(angle: Double): Double {
    var a = angle
    while (a > PI) a -= 2 * PI
    while (a < -PI) a += 2 * PI
    return a
}

/** [pass]'s braking and grip through its window; null when its line is too short to read. */
fun passDynamics(pass: LinePass): PassDynamics? {
    val p = profile(pass) ?: return null
    val k = GSpanPoints
    val (start, apex) = brakingOf(p)
    val peak = (max(start, k) until apex).maxOfOrNull { i ->
        -(p.v[i + k] * p.v[i + k] - p.v[i - k] * p.v[i - k]) / (2 * (p.s[i + k] - p.s[i - k]))
    } ?: 0.0
    val distance = p.s[apex] - p.s[start]
    val average = if (distance > 0.0) (p.v[start] * p.v[start] - p.v[apex] * p.v[apex]) / (2 * distance) else 0.0
    val near = (k until p.size - k).filter { abs(p.s[it] - p.s[apex]) <= ApexReachMeters }
    return PassDynamics(
        p.progress[start], p.v[start] * KmhPerMetrePerSecond, p.v[apex] * KmhPerMetrePerSecond, distance,
        max(peak, 0.0) / StandardGravity, max(average, 0.0) / StandardGravity, near.maxOf { p.g[it] },
    )
}

/**
 * [pass] braking later to the same slowest speed: the same braking, pressed into a shorter
 * stretch, so harder by the stretch's ratio. Harder by the track's limit over its peak
 * ([MaximumBrakeScale] at most) is the full move; the step is the first [BrakeStepMeters] of it, in
 * whole [MinimumBrakeStepMeters]; null when that is under one. Moving the braking [x] metres later
 * holds the entry speed over them instead of the braking's average speed, so it gains
 * x (1 / average - 1 / entry).
 */
@Suppress("ReturnCount")
private fun brakeRoom(pass: LinePass, d: PassDynamics, trackG: Double): BrakeRoom? {
    val p = profile(pass) ?: return null
    val (start, apex) = brakingOf(p)
    val zone = d.brakingMeters
    val perMeter = p.seconds(p.v, start, apex) / zone - 1 / p.v[start]
    val scale = min(trackG / max(d.peakBrakeG, Tiny), MaximumBrakeScale)
    val full = zone * (1 - 1 / scale)
    val step = floor(min(full, BrakeStepMeters) / MinimumBrakeStepMeters) * MinimumBrakeStepMeters
    if (step < MinimumBrakeStepMeters || perMeter <= 0.0) return null
    return BrakeRoom(d.peakBrakeG, trackG, step, step * perMeter, full, full * perMeter)
}

/**
 * The slowest point of [p] and where braking for it starts: the last point before it within
 * [PlateauMetresPerSecond] of the fastest, so a stretch held flat out is not counted as braking.
 */
private fun brakingOf(p: Profile): Pair<Int, Int> {
    val apex = (GSpanPoints until p.size - GSpanPoints).minBy { p.v[it] }
    val fastest = (0..apex).maxOf { p.v[it] }
    return (0..apex).last { p.v[it] >= fastest - PlateauMetresPerSecond } to apex
}

/** Half a km/h: the speed a flat-out stretch wanders by. */
private const val PlateauMetresPerSecond = 0.5 / KmhPerMetrePerSecond
