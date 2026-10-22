package com.flappedear.betterdriver.coach

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

// Where to be on the track and where to accelerate (FBBD-307): what the target pass of a corner
// does differently from the last one in its line at the apex and where the pedal goes back on,
// measured live by the same pass meter as braking and apex speed, so both passes are read the same
// way. Differences smaller than the receiver repeats are not advice. The words the coach says for
// them are Arek's pick on the UI review page (round 43); until then nothing here is said. The
// throttle point needs the pedal from OBD: the speed alone does not show it (the car still slows on
// a part throttle).

/** Sideways differences smaller than this are the same line: the RaceBox repeats a line to about 1.5 m (FBBD-306). */
const val LineToleranceMeters = 1.5

/** Never ask for more than this much change of line in one cue. */
const val MaxLineMeters = 5

/** Throttle points closer than this are the same: at 10 Hz and 100 km/h the car moves 2.8 m between fixes. */
const val PickupToleranceMeters = 3.0

/** Never ask for more than this much earlier or later throttle in one cue. */
const val MaxPickupMeters = 20.0

/**
 * Whole metres tighter at the apex (toward the inside of the turn, negative wider) that [target]
 * was than [pass] at [corner], or null if nothing to say: like [advise], only for a pass that lost at
 * least [MinLossMicros] against the target, and only beyond [LineToleranceMeters].
 */
fun lineChange(pass: CornerPass, target: CornerPass, corner: Corner): Int? {
    if (pass.timeMicros - target.timeMicros < MinLossMicros) return null
    return tighterBy(pass, target, corner.turn)?.takeIf { abs(it) >= LineToleranceMeters }
        ?.let { (sign(it) * min(abs(it), MaxLineMeters.toDouble())).roundToInt() }
}

/**
 * How much tighter [after] was at the apex than [before] at a corner turning [turn], in metres
 * toward the inside; null for a corner without a turn or a pass without its line.
 */
fun tighterBy(before: CornerPass, after: CornerPass, turn: Turn): Double? {
    val a = before.apexOffsetMeters
    val b = after.apexOffsetMeters
    return when {
        a == null || b == null -> null
        turn == Turn.Left -> b - a
        turn == Turn.Right -> a - b
        else -> null
    }
}

/** Whether [after] moved [tighterMeters] (negative: wider) from [before], at least half of it, as [improved]. */
fun improvedLine(tighterMeters: Int, before: CornerPass, after: CornerPass, turn: Turn): Boolean {
    val moved = tighterBy(before, after, turn) ?: return false
    return moved * sign(tighterMeters.toDouble()) >= abs(tighterMeters) / 2.0
}

/**
 * How much earlier [target] went back on throttle than [pass] (negative: later), in metres to
 * [MetresStep], or null if nothing to say: only for a pass that lost at least [MinLossMicros], only
 * with the pedal on both, and only beyond [PickupToleranceMeters].
 */
fun pickupChange(pass: CornerPass, target: CornerPass): Int? {
    if (pass.timeMicros - target.timeMicros < MinLossMicros) return null
    val a = pass.pickupAt
    val b = target.pickupAt
    val earlier = if (a != null && b != null) a - b else null
    return earlier?.takeIf { abs(it) >= PickupToleranceMeters }?.let {
        sign(it).toInt() * maxOf(MetresStep, (min(abs(it), MaxPickupMeters) / MetresStep).roundToInt() * MetresStep)
    }
}

/** Where [target] goes back on throttle, metres after its apex to [MetresStep] (negative: before); null without. */
fun pickupAfterApex(target: CornerPass): Int? =
    target.pickupAt?.let { ((it - target.apexAt) / MetresStep).roundToInt() * MetresStep }

/** Whether [after] went back on throttle [earlierMeters] earlier (negative: later) than [before], at least halfway. */
fun improvedPickup(earlierMeters: Int, before: CornerPass, after: CornerPass): Boolean {
    val b = before.pickupAt
    val a = after.pickupAt
    return b != null && a != null && (b - a) * sign(earlierMeters.toDouble()) >= abs(earlierMeters) / 2.0
}
