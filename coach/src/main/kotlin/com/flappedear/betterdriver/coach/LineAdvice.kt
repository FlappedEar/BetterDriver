package com.flappedear.betterdriver.coach

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

// Where to be on the track (FBBD-307): what the target pass of a corner does differently from the
// last one in its line at the apex, measured live by the same pass meter as braking and apex speed,
// so both passes are read the same way. Differences smaller than the receiver repeats are not
// advice. The words the coach says for it are Arek's pick on the UI review page (round 43); until
// then nothing here is said. Where the throttle goes back on needs the pedal, which the coach does
// not get yet: the speed alone does not show it (the car still slows on a part throttle).

/** Sideways differences smaller than this are the same line: the RaceBox repeats a line to about 1.5 m (FBBD-306). */
const val LineToleranceMeters = 1.5

/** Never ask for more than this much change of line in one cue. */
const val MaxLineMeters = 5

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
