package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.steering.SteerSample
import com.flappedear.betterdriver.timing.steering.SteeringPass
import com.flappedear.betterdriver.timing.steering.bends
import com.flappedear.betterdriver.timing.steering.steeringPass

// What the hands did through a corner in the line analysis (FBBD-321): the same reading the live
// coach makes of a pass (timing/steering), here from the points of a pass's line.

/**
 * The steering of a pass from its [line] (FBBD-321): the wheel at each point, the bend of the path
 * through the points around it, positions along the window unwrapped across the start/finish line
 * of a loop of [length] so they compare between passes.
 */
internal fun steeringOf(line: List<LinePoint>, turnsLeft: Boolean, length: Double): SteeringPass? {
    val points = line.filter { it.steerDeg != null }
    if (points.size < 2) return null
    val along = DoubleArray(points.size)
    for (i in 1 until points.size) {
        val step = points[i].progressMeters - points[i - 1].progressMeters
        along[i] = along[i - 1] + if (step < 0 && length > 0) step + length else step
    }
    val start = points.first().progressMeters
    // The bend is the driven path's own: its heading change over the distance between its points,
    // not along the axis, so an inside and an outside line through the same corner bend differently.
    val xy = points.map { Xy(it.eastMeters, it.northMeters) }
    val driven = DoubleArray(xy.size)
    for (i in 1 until xy.size) driven[i] = driven[i - 1] + (xy[i] - xy[i - 1]).length()
    val bends = bends(xy, driven)
    val samples = points.indices.map { SteerSample(start + along[it], points[it].steerDeg!!, bends[it]) }
    return steeringPass(samples, turnsLeft)
}

/** The car's steering angle comes at about 50 Hz; a longer gap than this is the adapter losing it (FBBD-321). */
const val MaxSteeringGapSeconds = 0.5
