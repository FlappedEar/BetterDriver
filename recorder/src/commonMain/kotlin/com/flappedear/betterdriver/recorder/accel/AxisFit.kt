package com.flappedear.betterdriver.recorder.accel

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How one accelerometer axis relates to the same acceleration worked out from GPS: the axis reads
 * `sign * gps + bias`. [bias] is what a tilted mount adds from gravity (a RaceBox pitched back by 11°
 * reads -0.19 g standing still); [sign] is -1 for an axis that runs the other way (a receiver mounted
 * backwards, or a file with the opposite convention). [correlation] says how well the two agree;
 * [residual] is the mean square of what is left between the corrected axis and GPS, in g², which
 * [fuseDirection] weighs accelerometers by (NaN when not known).
 */
data class AxisFit(val sign: Double, val bias: Double, val correlation: Double, val residual: Double = Double.NaN) {
    /** The axis agrees with GPS well enough to be trusted over it. */
    val usable: Boolean get() = abs(correlation) >= MinimumAxisCorrelation

    /** [reading] of the axis as acceleration in the GPS's direction, in g, the mount's bias taken off. */
    fun correct(reading: Double): Double = (reading - bias) * sign
}

/**
 * Below this correlation with the GPS-derived acceleration an accelerometer axis is not used: it
 * is mounted across another axis, or broken. "No precise advice from weak evidence": the GPS's own
 * acceleration is shown instead. Arek's RaceBox reads 0.88 to 0.97 on his road drives.
 */
const val MinimumAxisCorrelation = 0.5

/** The fit needs at least this many pairs of readings. */
const val MinimumFitPairs = 50

/**
 * How [axis] readings relate to [gps] accelerations at the same moments (pairs with a non-finite
 * value are skipped), or null with fewer than [MinimumFitPairs] pairs or no spread in either. The
 * sign is that of their correlation. Both measure the same acceleration in g, so the bias is the
 * difference of their means with the sign applied; a fitted slope would not do, as GPS noise and
 * smoothing pull it below 1, and on a circuit driven one way round (a mean lateral G that is not 0)
 * that would push real cornering into the bias.
 */
@Suppress("ReturnCount")
fun fitAxis(axis: DoubleArray, gps: DoubleArray): AxisFit? {
    var n = 0
    var sumA = 0.0
    var sumG = 0.0
    for (i in axis.indices) {
        if (!axis[i].isFinite() || !gps[i].isFinite()) continue
        n++
        sumA += axis[i]
        sumG += gps[i]
    }
    if (n < MinimumFitPairs) return null
    val meanA = sumA / n
    val meanG = sumG / n
    var cov = 0.0
    var varA = 0.0
    var varG = 0.0
    for (i in axis.indices) {
        if (!axis[i].isFinite() || !gps[i].isFinite()) continue
        val da = axis[i] - meanA
        val dg = gps[i] - meanG
        cov += da * dg
        varA += da * da
        varG += dg * dg
    }
    if (varA <= 0.0 || varG <= 0.0) return null
    val sign = if (cov < 0.0) -1.0 else 1.0
    // (sign·(a - meanA) - (g - meanG))² summed over the pairs, per pair.
    val residual = maxOf(0.0, (varA + varG - 2 * sign * cov) / n)
    return AxisFit(sign, meanA - sign * meanG, cov / sqrt(varA * varG), residual)
}
