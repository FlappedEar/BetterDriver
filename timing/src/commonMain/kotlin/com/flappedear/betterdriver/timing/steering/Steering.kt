package com.flappedear.betterdriver.timing.steering

import com.flappedear.betterdriver.timing.geo.Xy
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt

// What the hands did through one corner (FBBD-321), from the steering wheel angle the gen-1 FR-S
// broadcasts on its CAN bus (FBBD-263, "obd_steering_angle", degrees, positive turning left). The
// lap analysis and the live coach both read a pass this way, so what the coach asks for on track is
// what the analysis shows after it and what the next session's proof measures. Angles here are
// signed into the corner: positive is wheel toward the inside of the turn, whichever way it turns.
//
// What it can tell, against the target pass of the same corner (the driver's own, FBBD-177):
// - steer more: the pass ran wider at the apex with less wheel than the target, so the hands, not
//   the grip, made the line wide;
// - push: more wheel than the target beyond what the path's bend needs, so the front tyres were
//   sliding (understeer); more wheel does not turn such a car, a slower entry does;
// - open the wheel earlier: the target left full lock sooner after the apex, which is what lets the
//   throttle go on earlier;
// - corrections: lock added again after it was eased, a corner taken in pieces;
// - countersteer before the wheel opens: the rear stepped out (oversteer).

/** The gen-1 FR-S / 86 / BRZ steering ratio, 13:1 (https://motormavens.com, FR-S road test specs). */
const val FrsSteeringRatio = 13.0

/** The gen-1 FR-S / 86 / BRZ wheelbase, 2.57 m. */
const val FrsWheelbaseMeters = 2.57

/** A pass whose wheel never turns this far into the corner is a kink: nothing to say about steering. */
const val MinCornerWheelDeg = 15.0

/** Turn-in is where the wheel first reaches this share of its peak, going back from the peak. */
const val TurnInShare = 0.3

/** Near full lock: at least this share of the peak. */
const val FullLockShare = 0.85

/** The wheel is open again once it is back to this share of the peak. */
const val OpenShare = 0.5

/** Lock eased by this much and then added again by this much is a correction. */
const val CorrectionDeg = 8.0

/** Wheel this far out of the corner before it opens is countersteer. */
const val CountersteerDeg = 5.0

/**
 * Less difference of wheel than this between two passes is the same: the hand repeats a corner to a
 * few degrees, and the push is read from GNSS bends, good to about 4° of wheel on the FR-S.
 */
const val WheelToleranceDeg = 8.0

/** Push (wheel beyond the bend's need) greater than the target's by this is the front sliding more. */
const val PushToleranceDeg = 12.0

/** Unwind points closer than this are the same: at 10 Hz and 100 km/h the car moves 2.8 m between fixes. */
const val UnwindToleranceMeters = 5.0

/** Never ask for more than this much earlier unwinding in one cue. */
const val MaxUnwindMeters = 20.0

/** Unwinding is asked for in steps of this. */
const val UnwindStepMeters = 5

/** Extra corrections against the target that make a pass worth smoothing. */
const val ExtraCorrections = 2

/** Half the distance a bend is read over: positions 10 m apart, so GNSS noise does not read as a bend. */
const val BendHalfMeters = 5.0

/**
 * One reading through a corner: [along] metres, the wheel in degrees (positive left), the path's
 * bend in 1/m (positive left).
 */
data class SteerSample(val along: Double, val wheelDeg: Double, val bendPerMeter: Double? = null)

/** How the hands drove one pass; distances are metres along, as its samples were. */
@Serializable
data class SteeringPass(
    /** The most wheel into the corner, degrees. */
    val peakDeg: Double,
    val peakAt: Double,
    /** Where the wheel first reached [TurnInShare] of [peakDeg]. */
    val turnInAt: Double,
    /** Where the wheel last held [FullLockShare] of the peak before opening to [OpenShare]; null if it did not open. */
    val unwindAt: Double?,
    /** Times lock was added again by [CorrectionDeg] after being eased by as much, from turn-in to the unwind. */
    val corrections: Int,
    /** The wheel went [CountersteerDeg] out of the corner between turn-in and the unwind. */
    val countersteer: Boolean,
    /** Mean wheel beyond what the bend needs ([wheelForBend]) near full lock, degrees; null without bends. */
    val pushDeg: Double?,
)

/** The wheel in degrees a car with [ratio] and [wheelbaseMeters] needs to follow a bend of [bendPerMeter] slowly. */
fun wheelForBend(bendPerMeter: Double, ratio: Double = FrsSteeringRatio, wheelbaseMeters: Double = FrsWheelbaseMeters) =
    ratio * wheelbaseMeters * bendPerMeter * DegreesPerRadian

/**
 * The steering of one pass of a corner turning left ([left]) or right, from its [samples] in driving
 * order; null with fewer than three samples or a peak under [MinCornerWheelDeg].
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod")
fun steeringPass(samples: List<SteerSample>, left: Boolean): SteeringPass? {
    if (samples.size < MinSamples) return null
    val side = if (left) 1.0 else -1.0
    val w = DoubleArray(samples.size) { samples[it].wheelDeg * side }
    var peak = 0
    for (i in w.indices) if (w[i] > w[peak]) peak = i
    val top = w[peak]
    if (top < MinCornerWheelDeg) return null
    var turnIn = peak
    while (turnIn > 0 && w[turnIn - 1] >= top * TurnInShare) turnIn--
    // The wheel opens for good after its last moment near full lock: a dip and back (a slide caught,
    // lock eased and added again) is still inside the corner.
    val lastFull = (peak until w.size).last { w[it] >= top * FullLockShare }
    val unwind = lastFull.takeIf { (lastFull until w.size).any { w[it] <= top * OpenShare } }
    val end = unwind ?: (w.size - 1)
    var corrections = 0
    var high = w[turnIn]
    var low = Double.POSITIVE_INFINITY
    var easing = false
    for (i in turnIn..end) {
        if (!easing) {
            high = maxOf(high, w[i])
            if (high - w[i] >= CorrectionDeg) {
                easing = true
                low = w[i]
            }
        } else {
            low = min(low, w[i])
            if (w[i] - low >= CorrectionDeg) {
                corrections++
                easing = false
                high = w[i]
            }
        }
    }
    val countersteer = (turnIn..end).any { w[it] <= -CountersteerDeg }
    val pushes = (turnIn..end).filter { w[it] >= top * FullLockShare }
        .mapNotNull { i -> samples[i].bendPerMeter?.let { w[i] - wheelForBend(it * side) } }
    return SteeringPass(
        peakDeg = top,
        peakAt = samples[peak].along,
        turnInAt = samples[turnIn].along,
        unwindAt = unwind?.let { samples[it].along },
        corrections = corrections,
        countersteer = countersteer,
        pushDeg = pushes.takeIf { it.isNotEmpty() }?.average(),
    )
}

/** What to do with the hands next time (FBBD-321), in the order it is driven. */
@Serializable
sealed interface SteerAdvice {
    /** Wider than the target with less wheel: turn in more. */
    @Serializable
    data object More : SteerAdvice

    /** The front slid: more wheel than the target beyond the bend's need. Less wheel, a slower entry. */
    @Serializable
    data object Less : SteerAdvice

    /** Lock added again in pieces: one steering movement. */
    @Serializable
    data object Smoother : SteerAdvice

    /** The target left full lock [meters] earlier (a multiple of [UnwindStepMeters]): open the wheel sooner. */
    @Serializable
    data class OpenEarlier(val meters: Int) : SteerAdvice

    /** Countersteer before the wheel opened, which the target did not need: the rear stepped out. */
    @Serializable
    data object Oversteer : SteerAdvice
}

/**
 * What [pass] does differently with the wheel from [target] through the same corner. [tighterMeters]
 * is how much tighter the target was at the apex (negative: wider), null when not known. Only call it
 * for a pass that lost time to the target: a faster pass needs no advice.
 */
fun steeringAdvice(pass: SteeringPass, target: SteeringPass, tighterMeters: Double?): List<SteerAdvice> {
    val push = if (pass.pushDeg != null && target.pushDeg != null) pass.pushDeg - target.pushDeg else null
    val wheel = when {
        push != null && push >= PushToleranceDeg -> SteerAdvice.Less
        tighterMeters != null && tighterMeters >= TighterMeters && pass.peakDeg <= target.peakDeg - WheelToleranceDeg ->
            SteerAdvice.More
        else -> null
    }
    val smoother = SteerAdvice.Smoother.takeIf { pass.corrections - target.corrections >= ExtraCorrections }
    val open = if (pass.unwindAt != null && target.unwindAt != null) {
        (pass.unwindAt - target.unwindAt).takeIf { it >= UnwindToleranceMeters }?.let {
            SteerAdvice.OpenEarlier(
                maxOf(UnwindStepMeters, (min(it, MaxUnwindMeters) / UnwindStepMeters).roundToInt() * UnwindStepMeters),
            )
        }
    } else {
        null
    }
    val oversteer = SteerAdvice.Oversteer.takeIf { pass.countersteer && !target.countersteer }
    return listOfNotNull(wheel, smoother, open, oversteer)
}

/**
 * Whether [after] did what [advice] asked, from [before]: at least half of an asked unwind; for the
 * others the difference [steeringAdvice] read is gone.
 */
fun improvedSteering(advice: SteerAdvice, before: SteeringPass, after: SteeringPass): Boolean = when (advice) {
    SteerAdvice.More -> after.peakDeg - before.peakDeg >= WheelToleranceDeg / 2
    SteerAdvice.Less -> before.pushDeg != null && after.pushDeg != null &&
        before.pushDeg - after.pushDeg >= PushToleranceDeg / 2
    SteerAdvice.Smoother -> after.corrections < before.corrections
    is SteerAdvice.OpenEarlier -> before.unwindAt != null && after.unwindAt != null &&
        before.unwindAt - after.unwindAt >= advice.meters / 2.0
    SteerAdvice.Oversteer -> !after.countersteer
}

/**
 * The bend of a path at each of its [points] (1/m, positive turning left, counter-clockwise), from
 * the heading [BendHalfMeters] before to as far after; null where the path does not reach that far
 * on both sides. [along] is metres from the first point at each.
 */
fun bends(points: List<Xy>, along: DoubleArray, halfMeters: Double = BendHalfMeters): List<Double?> {
    require(points.size == along.size) { "points and distances differ in size" }
    var lo = 0
    var hi = 0
    return points.indices.map { i ->
        // lo: the last point at least halfMeters back; hi: the first at least halfMeters ahead.
        while (lo + 1 < i && along[lo + 1] <= along[i] - halfMeters) lo++
        hi = maxOf(hi, i)
        while (hi < points.size - 1 && along[hi] < along[i] + halfMeters) hi++
        if (along[i] - along[lo] < halfMeters || along[hi] - along[i] < halfMeters) return@map null
        val turn = wrap(heading(points[i], points[hi]) - heading(points[lo], points[i]))
        turn / ((along[hi] - along[lo]) / 2)
    }
}

private fun heading(from: Xy, to: Xy): Double = atan2(to.y - from.y, to.x - from.x)

private fun wrap(angle: Double): Double {
    var a = angle
    while (a > PI) a -= 2 * PI
    while (a < -PI) a += 2 * PI
    return a
}

private const val MinSamples = 3
private const val TighterMeters = 1.5
private const val DegreesPerRadian = 180.0 / PI
