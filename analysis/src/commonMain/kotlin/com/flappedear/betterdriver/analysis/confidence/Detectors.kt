@file:Suppress("TooManyFunctions") // One function per detector.

package com.flappedear.betterdriver.analysis.confidence

import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.math.toDegrees
import com.flappedear.betterdriver.recorder.Confidence
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

// The detectors of weak position evidence (FBBD-177). Each looks at one segment (a corner or a
// straight) of the axis, or at one lap's positions through it, and returns what it found, or null
// when the evidence there is sound. A finding caps the confidence of every conclusion drawn there:
// Low for evidence that can put a braking point or a time in the wrong place (a gap, a position on
// the other leg of a hairpin, a stop, the pit lane), Medium for evidence that blurs it (positions
// far apart, a different line, a neighbouring piece of track, directions that disagree).

/** A gap longer than this, and than [GnssGapIntervals] usual intervals, is a GNSS gap. */
const val GnssGapMinimumSeconds = 0.5
const val GnssGapIntervals = 3.0

/** More than this share of a lap's positions through a segment not placed on the lap is ambiguous. */
const val AmbiguousShare = 0.2

/** Another part of the track within this of a segment can take its positions. */
const val AdjacentMeters = 20.0

/** Around a segment, the track this far before and after it is its own, not another part. */
const val AdjacentMarginMeters = 60.0

/** Two legs of one segment running opposite ways this close make a hairpin. */
const val HairpinLegMeters = 45.0

/** ... at least this far apart along the track. */
const val HairpinArcMeters = 40.0

/** A position nearer a part of the track this far from where the lap was is on the other leg. */
const val CrossoverMeters = 15.0

/** The direction of travel disagreeing with the track by more than this (median) is poor. */
const val PoorHeadingDegrees = 25.0

/** Directions are taken between positions at least this far apart. */
const val HeadingMinimumMoveMeters = 1.0

/** Below this a lap stopped or spun: 15 km/h. */
const val VeryLowSpeedMetresPerSecond = 15.0 / 3.6

/** At least [PitLaneShare] of the positions further than this from the line: pit lane or off track. */
const val PitLaneMeters = 15.0
const val PitLaneShare = 0.3

/** A median distance from the fastest lap's line beyond this is a very different line. */
const val DifferentLineMeters = 5.0

/** Positions further apart than this (median) leave what is between them interpolated. */
const val WeakIntervalSeconds = 0.2

/** ... and further apart than this, too far to place a braking point or a minimum speed at all. */
const val VeryWeakIntervalSeconds = 0.5

/** Fewer positions than this through a segment say too little about it. */
const val MinimumSegmentFixes = 4

private const val MinimumDirections = 3
private const val OpposedCosine = -0.5

/** Reason codes, one per detector. */
object Codes {
    const val GNSS_GAP = "gnss_gap"
    const val NOT_COVERED = "not_covered"
    const val AMBIGUOUS = "projection_ambiguous"
    const val ADJACENT = "adjacent_track"
    const val HAIRPIN = "hairpin_crossover"
    const val HEADING = "poor_heading"
    const val VERY_LOW_SPEED = "very_low_speed"
    const val PIT_LANE = "pit_lane"
    const val DIFFERENT_LINE = "different_line"
    const val WEAK_INTERPOLATION = "weak_interpolation"
}

/** One lap's position through a segment: its time, where it is around the axis origin, and the lap's progress then. */
data class LapFix(val time: Double, val point: MetricPoint, val progress: Double? = null)


/**
 * A GNSS gap: positions at [times] (in order) between [from] and [to], seconds, with a longest
 * interval (counting from [from] and to [to]) over [GnssGapMinimumSeconds] and [GnssGapIntervals]
 * times the receiver's usual [interval]. Low.
 */
fun gnssGap(times: List<Double>, from: Double, to: Double, interval: Double): ConfidenceReason? {
    val all = listOf(from) + times.filter { it > from && it < to } + to
    val longest = all.zipWithNext { a, b -> b - a }.maxOrNull() ?: return null
    val threshold = maxOf(GnssGapMinimumSeconds, GnssGapIntervals * interval)
    return if (longest > threshold) {
        ConfidenceReason(Codes.GNSS_GAP, Confidence.LOW, "GPS gap of ${fixed(longest, 1)} s")
    } else {
        null
    }
}

/**
 * The times of [fixes] that are new positions: a position repeated unchanged is the receiver
 * holding its last fix through a dropout, not a new one, so [gnssGap] sees the dropout.
 */
fun freshTimes(fixes: List<LapFix>): List<Double> = fixes.filterIndexed { i, f ->
    i == 0 || distance(f.point, fixes[i - 1].point) > HeldPositionMeters
}.map { it.time }

/** Positions closer than this to the one before are the same position, held. */
private const val HeldPositionMeters = 0.01

/** Projection ambiguity:more than [AmbiguousShare] of the [fixes] positions could not be [placed] on the lap. Low. */
fun projectionAmbiguity(fixes: Int, placed: Int): ConfidenceReason? {
    if (fixes < MinimumSegmentFixes) return null
    val lost = fixes - placed.coerceIn(0, fixes)
    return if (lost > AmbiguousShare * fixes) {
        ConfidenceReason(Codes.AMBIGUOUS, Confidence.LOW, "$lost of $fixes positions could not be placed on the track")
    } else {
        null
    }
}

/**
 * Adjacent track: another part of [axis] (beyond [AdjacentMarginMeters] before and after the segment
 * from [start] to [end]) runs within [AdjacentMeters] of it, so a position there may belong to either.
 * Medium.
 */
fun adjacentTrack(axis: ProgressAxis, start: Double, end: Double): ConfidenceReason? {
    val own = AxisWindow(axis, start, end, 0.0).indices
    val near = AxisWindow(axis, start, end, AdjacentMarginMeters)
    var closest = Double.POSITIVE_INFINITY
    for (j in axis.points.indices) {
        if (near.contains(axis.cumulative[j])) continue
        for (i in own) closest = minOf(closest, distance(axis.points[i], axis.points[j]))
    }
    return if (closest <= AdjacentMeters) {
        ConfidenceReason(
            Codes.ADJACENT, Confidence.MEDIUM, "another part of the track runs ${fixed(closest, 0)} m away",
        )
    } else {
        null
    }
}

/**
 * Whether the segment from [start] to [end] is a hairpin: two of its points at least
 * [HairpinArcMeters] apart along the track run opposite ways within [HairpinLegMeters] of each other.
 */
fun hairpinLegs(axis: ProgressAxis, start: Double, end: Double): Boolean {
    val own = AxisWindow(axis, start, end, 0.0)
    val idx = own.indices
    for (a in idx.indices) {
        for (b in a + 1 until idx.size) {
            val i = idx[a]
            val j = idx[b]
            val apart = abs(own.offset(axis.cumulative[j]) - own.offset(axis.cumulative[i]))
            if (apart < HairpinArcMeters || distance(axis.points[i], axis.points[j]) > HairpinLegMeters) continue
            if (dot(tangent(axis, i), tangent(axis, j)) < OpposedCosine) return true
        }
    }
    return false
}

/**
 * Hairpin crossover: in a hairpin ([hairpinLegs]), one of a lap's [fixes] is nearer the other leg
 * than the part of the track the lap was on ([LapFix.progress]), by more than [CrossoverMeters] along
 * it: a position that a projection can put on the wrong leg. Low. [legs] saves working out
 * [hairpinLegs] again for every lap.
 */
fun hairpinCrossover(
    axis: ProgressAxis,
    start: Double,
    end: Double,
    fixes: List<LapFix>,
    legs: Boolean = hairpinLegs(axis, start, end),
): ConfidenceReason? {
    if (!legs) return null
    val window = AxisWindow(axis, start, end, AdjacentMarginMeters)
    val crossed = fixes.count { fix ->
        val expected = fix.progress ?: return@count false
        val nearest = window.nearest(fix.point) ?: return@count false
        abs(window.difference(axis.cumulative[nearest], expected)) > CrossoverMeters
    }
    return if (crossed > 0) {
        val what = if (crossed == 1) "a position" else "$crossed positions"
        ConfidenceReason(Codes.HAIRPIN, Confidence.LOW, "$what on the other leg of the hairpin")
    } else {
        null
    }
}

/**
 * Poor heading agreement: the direction of travel between [fixes] (at least
 * [HeadingMinimumMoveMeters] apart) differs from the track's at the nearest point by more than
 * [PoorHeadingDegrees], as a median. Medium.
 */
fun poorHeading(axis: ProgressAxis, start: Double, end: Double, fixes: List<LapFix>): ConfidenceReason? {
    val window = AxisWindow(axis, start, end, AdjacentMarginMeters)
    val errors = mutableListOf<Double>()
    var from: LapFix? = fixes.firstOrNull()
    for (fix in fixes.drop(1)) {
        val a = from ?: fix
        val mx = fix.point.eastMeters - a.point.eastMeters
        val my = fix.point.northMeters - a.point.northMeters
        val moved = hypot(mx, my)
        val i = if (moved < HeadingMinimumMoveMeters) null else window.nearest(fix.point)
        if (moved >= HeadingMinimumMoveMeters) from = fix
        if (i != null) {
            val t = tangent(axis, i)
            val cosine = ((mx * t.first + my * t.second) / moved).coerceIn(-1.0, 1.0)
            errors += toDegrees(acos(cosine))
        }
    }
    if (errors.size < MinimumDirections) return null
    val median = median(errors)
    return if (median > PoorHeadingDegrees) {
        ConfidenceReason(
            Codes.HEADING, Confidence.MEDIUM, "direction of travel ${fixed(median, 0)}° off the track's",
        )
    } else {
        null
    }
}

/** Very low speed: the lap went slower than [VeryLowSpeedMetresPerSecond] ([minimum], m/s), a stop or a spin. Low. */
fun veryLowSpeed(minimum: Double?): ConfidenceReason? =
    if (minimum != null && minimum.isFinite() && minimum < VeryLowSpeedMetresPerSecond) {
        ConfidenceReason(
            Codes.VERY_LOW_SPEED, Confidence.LOW, "down to ${fixed(minimum * KmhPerMps, 0)} km/h: a stop or a spin",
        )
    } else {
        null
    }

private const val KmhPerMps = 3.6

/** Pit lane: at least [PitLaneShare] of the positions ([offsets], m) over [PitLaneMeters] from the line. Low. */
fun pitLane(offsets: List<Double>): ConfidenceReason? {
    if (offsets.isEmpty()) return null
    val off = offsets.count { abs(it) > PitLaneMeters }
    return if (off >= PitLaneShare * offsets.size) {
        val text = "more than ${fixed(PitLaneMeters, 0)} m off the line: pit lane or off track"
        ConfidenceReason(Codes.PIT_LANE, Confidence.LOW, text)
    } else {
        null
    }
}

/**
 * A very different line: positions a median of more than [DifferentLineMeters] from the fastest
 * lap's line ([offsets], m), not the pit lane. Distances along it then compare less well. Medium.
 */
fun differentLine(offsets: List<Double>): ConfidenceReason? {
    if (offsets.isEmpty() || pitLane(offsets) != null) return null
    val median = median(offsets.map(::abs))
    return if (median > DifferentLineMeters) {
        ConfidenceReason(
            Codes.DIFFERENT_LINE, Confidence.MEDIUM, "a line ${fixed(median, 0)} m from the fastest lap's",
        )
    } else {
        null
    }
}

/**
 * Weak interpolation: positions at [times] too few ([MinimumSegmentFixes]) or too far apart to place
 * a braking point or a minimum speed between them. Medium beyond [WeakIntervalSeconds] (under 5 Hz),
 * Low beyond [VeryWeakIntervalSeconds] or with too few.
 */
fun weakInterpolation(times: List<Double>): ConfidenceReason? {
    if (times.size < MinimumSegmentFixes) {
        val text = if (times.size == 1) "only 1 position" else "only ${times.size} positions"
        return ConfidenceReason(Codes.WEAK_INTERPOLATION, Confidence.LOW, text)
    }
    val interval = median(times.zipWithNext { a, b -> b - a })
    return when {
        interval > VeryWeakIntervalSeconds -> ConfidenceReason(
            Codes.WEAK_INTERPOLATION, Confidence.LOW, "positions only every ${fixed(interval, 1)} s",
        )
        interval > WeakIntervalSeconds -> ConfidenceReason(
            Codes.WEAK_INTERPOLATION,
            Confidence.MEDIUM,
            "positions every ${fixed(interval, 1)} s, interpolated between",
        )
        else -> null
    }
}

