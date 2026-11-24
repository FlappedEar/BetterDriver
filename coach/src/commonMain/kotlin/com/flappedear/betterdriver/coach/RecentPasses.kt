package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.gear.Gearbox
import kotlin.math.roundToLong
import kotlin.math.sign

// What a corner cue measures from (FBBD-410): not the last lap's pass alone, which may hold a one-off
// mistake (braked 10 m early once while the other laps brake right), but the corner's recent passes
// at race pace, value by value. A cue asks for what the driver usually does differently from the
// target, and its result is judged against that usual pass.

/** A corner cue measures from at most this many of the corner's latest passes at race pace (FBBD-410). */
const val RecentPasses = 3

/**
 * The usual pass of [passes] (FBBD-410), the last one last: the median of each measured value (the
 * brake point, the apex and its speed, the speed in, the time, the line, the throttle point, the time
 * to the apex and the engine speed), so one pass unlike the others moves none of them, and of two
 * passes the middle. A value only some passes have is the median of those that have it, when at
 * least half of them do; else unknown. What cannot be put in the middle (the steering, the receiver's
 * gaps) is the last pass's, as are the corner and the lap. One pass is itself.
 */
fun typicalPass(passes: List<CornerPass>): CornerPass {
    val last = passes.last()
    if (passes.size == 1) return last
    fun known(values: List<Double?>): Double? =
        values.filterNotNull().takeIf { it.isNotEmpty() && it.size * 2 >= values.size }?.let(::median)
    return last.copy(
        brakeAt = median(passes.map { it.brakeAt }),
        apexAt = median(passes.map { it.apexAt }),
        entryKmh = median(passes.map { it.entryKmh }),
        minKmh = median(passes.map { it.minKmh }),
        timeMicros = median(passes.map { it.timeMicros.toDouble() }).roundToLong(),
        apexOffsetMeters = known(passes.map { it.apexOffsetMeters }),
        pickupAt = known(passes.map { it.pickupAt }),
        apexMicros = known(passes.map { it.apexMicros?.toDouble() })?.roundToLong(),
        apexRpm = known(passes.map { it.apexRpm }),
    )
}

/** The middle of [values], or the mean of the two middle ones. */
private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/**
 * Whether [a] and [b] ask for the same change the same way, whatever their numbers (FBBD-410): both
 * brake later, both a tighter line, both the throttle earlier.
 */
fun sameAsk(a: Advice, b: Advice): Boolean = when (a) {
    is Advice.Line -> b is Advice.Line && a.tighterMeters.sign == b.tighterMeters.sign
    is Advice.Throttle -> b is Advice.Throttle && a.earlierMeters.sign == b.earlierMeters.sign
    is Advice.Steer -> b is Advice.Steer && a.what::class == b.what::class
    else -> a::class == b::class
}

/**
 * Whether [last] still needs what [ask] asks for against [target] at [corner], whatever the number
 * (FBBD-410), by the same tolerances [cornerAdvice] uses: braked early, slow at the apex, off the line,
 * on the throttle late, the hands, the gear.
 */
// One reading for each kind of advice; the passes, the corner and the gear as cornerAdvice takes them.
@Suppress("CyclomaticComplexMethod", "LongParameterList")
fun stillNeeds(
    ask: Advice,
    last: CornerPass,
    target: CornerPass,
    corner: Corner,
    plannedGear: Int? = null,
    gearbox: Gearbox? = null,
): Boolean {
    val losing = last.timeMicros - target.timeMicros >= MinLossMicros
    val brake = last.brakeAt - target.brakeAt
    val speed = last.minKmh - target.minKmh
    return when (ask) {
        is Advice.BrakeLater -> losing && brake <= -brakeLaterTolerance(target)
        is Advice.BrakeEarlier -> losing && brake >= BrakeToleranceMeters && speed <= -SpeedToleranceKmh
        is Advice.CarrySpeed -> losing && speed <= -SpeedToleranceKmh
        is Advice.Line -> lineChange(last, target, corner)?.let { it.sign == ask.tighterMeters.sign } == true
        is Advice.Throttle -> pickupChange(last, target)?.let { it.sign == ask.earlierMeters.sign } == true
        is Advice.Steer -> steerChange(last, target, corner).any { it::class == ask.what::class }
        is Advice.Gear -> gearChange(last, plannedGear, gearbox) != null
        // The exercise (FBBD-418) is asked for on its own, never from a pass's advice: always still needed.
        is Advice.BrakeToThrottle -> true
    }
}

/**
 * What to ask for at [corner] (FBBD-410): [cornerAdvice] from the [usual] pass, only where the [last]
 * pass still needs it too ([stillNeeds]). A one-off mistake on the last lap asks for nothing the other
 * laps do not need, and a change the driver made on the last lap is not asked for again. The numbers
 * are the usual pass's.
 */
@Suppress("LongParameterList") // The passes, the corner and the gear as cornerAdvice takes them.
fun recentAdvice(
    usual: CornerPass,
    last: CornerPass,
    target: CornerPass,
    corner: Corner,
    plannedGear: Int? = null,
    gearbox: Gearbox? = null,
): List<Advice> {
    if (usual === last) return cornerAdvice(usual, target, corner, plannedGear, gearbox)
    // Filtered before the two-per-cue limit, so a change already made leaves room for one still needed.
    return cornerAdvice(usual, target, corner, plannedGear, gearbox) {
        stillNeeds(it, last, target, corner, plannedGear, gearbox)
    }
}
