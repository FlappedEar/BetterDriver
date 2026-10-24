package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.gear.Gearbox
import com.flappedear.betterdriver.timing.steering.SteerAdvice
import com.flappedear.betterdriver.timing.steering.steeringAdvice
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

// Where to be on the track and where to accelerate (FBBD-307): what the target pass of a corner
// does differently from the last one in its line at the apex and where the pedal goes back on,
// measured live by the same pass meter as braking and apex speed, so both passes are read the same
// way. Differences smaller than the receiver repeats are not advice. The words are Arek's picks on
// the UI review page, round 43: "tighter at the apex" without a number (UI-168 B), "throttle 5
// after the apex", the target only (UI-169 C), and at most two things in one cue (UI-170 B). The
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

/**
 * What to do with the hands at [corner] (FBBD-321): [steeringAdvice] of [pass]'s wheel against
 * [target]'s, only for a pass that lost at least [MinLossMicros], and only with the wheel on both.
 */
fun steerChange(pass: CornerPass, target: CornerPass, corner: Corner): List<SteerAdvice> {
    val p = pass.steering
    val t = target.steering
    if (p == null || t == null || pass.timeMicros - target.timeMicros < MinLossMicros) return emptyList()
    return steeringAdvice(p, t, tighterBy(pass, target, corner.turn))
}

/**
 * The gear to be in at the slowest point of a corner (FBBD-326, UI-279 A): [plannedGear], the line
 * plan's, when [pass] was in a higher one there by [gearbox]. Not tied to a time lost: the gear comes
 * from how hard the car pulls in each gear, not from a pass he drove. Null without the engine speed.
 */
@Suppress("ReturnCount")
fun gearChange(pass: CornerPass, plannedGear: Int?, gearbox: Gearbox?): Int? {
    val rpm = pass.apexRpm
    if (plannedGear == null || gearbox == null || rpm == null) return null
    val now = gearbox.gearAt(rpm, pass.minKmh / KmhPerMps) ?: return null
    return plannedGear.takeIf { now > it }
}

/** Whether [after] was in [gear] or lower at the slowest point, by [gearbox], as [improved]. */
fun improvedGear(gear: Int, after: CornerPass, gearbox: Gearbox?): Boolean {
    val now = after.apexRpm?.let { gearbox?.gearAt(it, after.minKmh / KmhPerMps) }
    return now != null && now <= gear
}

private const val KmhPerMps = 3.6

/** At most this many things to change are said in one corner cue (UI-170 B). */
const val MaxAdvicePerCue = 2

/**
 * What to ask for at [corner] (FBBD-307, UI-170 B), in the order it is driven: the entry (the brake
 * or apex speed advice, [advise]), the apex ([lineChange]) and the exit ([pickupChange]), at most
 * [MaxAdvicePerCue]. With the FR-S's wheel (FBBD-321, round 54) the hands come in: a front that slid
 * takes the entry's place ("front slid, slower in": braking later or carrying more would slide it
 * more) and leaves the line be; running wide with less wheel takes the line's ("ran wide, turn in
 * more"); a corner in pieces is said at the apex when nothing else is; straightening earlier goes
 * with the throttle as one exit cue (UI-271 B); the rear stepping out takes the whole exit ("gentler
 * throttle" against "throttle earlier"). With more than two, the half of the corner that lost more
 * time against [target] is kept: before the apex the entry and the apex, after it the exit, or the
 * apex and the first of the exit. Without the time to the apex on both passes, the first two.
 * The gear ([gearChange], FBBD-326) goes with the entry, as "Turn 7, brake at 80, second" (UI-279 A).
 */
@Suppress("CyclomaticComplexMethod") // Each kind of advice with its place in the corner.
fun cornerAdvice(
    pass: CornerPass,
    target: CornerPass,
    corner: Corner,
    plannedGear: Int? = null,
    gearbox: Gearbox? = null,
): List<Advice> {
    val steer = steerChange(pass, target, corner)
    val slid = SteerAdvice.Less in steer
    val smoother = Advice.Steer(SteerAdvice.Smoother).takeIf { SteerAdvice.Smoother in steer }
    val entry = if (slid) Advice.Steer(SteerAdvice.Less) else advise(pass, target, corner)
    val apex = when {
        slid -> smoother
        SteerAdvice.More in steer -> Advice.Steer(SteerAdvice.More)
        else -> lineChange(pass, target, corner)?.let { Advice.Line(it, turn = corner.turn) } ?: smoother
    }
    val exit = if (SteerAdvice.Oversteer in steer) {
        listOf(Advice.Steer(SteerAdvice.Oversteer))
    } else {
        listOfNotNull(
            steer.firstOrNull { it is SteerAdvice.OpenEarlier }?.let { Advice.Steer(it) },
            pickupChange(pass, target)?.let { Advice.Throttle(it, afterApex = pickupAfterApex(target)) },
        )
    }
    val gear = gearChange(pass, plannedGear, gearbox)?.let { Advice.Gear(it) }
    val all = listOfNotNull(entry, gear, apex) + exit
    val a = pass.apexMicros
    val b = target.apexMicros
    return when {
        all.size <= MaxAdvicePerCue -> all
        a == null || b == null -> all.take(MaxAdvicePerCue)
        a - b >= (pass.timeMicros - a) - (target.timeMicros - b) -> all.take(MaxAdvicePerCue)
        exit.size == MaxAdvicePerCue -> exit
        else -> listOfNotNull(gear ?: apex, exit.firstOrNull()).take(MaxAdvicePerCue)
    }
}
