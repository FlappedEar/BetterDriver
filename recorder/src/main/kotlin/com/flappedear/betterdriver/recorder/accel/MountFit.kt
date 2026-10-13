package com.flappedear.betterdriver.recorder.accel

import kotlin.math.sqrt

/**
 * One direction in an accelerometer's own axes ([weights], a unit vector, one weight per axis) and
 * how the acceleration along it relates to GPS ([fit]): `fit.correct(weights · reading)` is the
 * car's acceleration in g, the mount's tilt taken off.
 */
class AxisDirection(val weights: DoubleArray, val fit: AxisFit) {
    val usable: Boolean get() = fit.usable

    /** The reading [axes] (one value per axis) along this direction, corrected. */
    fun correct(axes: DoubleArray): Double {
        var along = 0.0
        for (k in weights.indices) along += weights[k] * axes[k]
        return fit.correct(along)
    }
}

/** Where the car's forward and left point in an accelerometer's axes; either is null when it cannot be found. */
class MountFit(val forward: AxisDirection?, val left: AxisDirection?)

/** Below this a 2×2 determinant (in g⁴ per pair²) means GPS shows one of the two directions too little. */
private const val MinDeterminant = 1e-12

/**
 * How an accelerometer sits in the car (FBBD-218), from [axes] (each axis's readings) and the
 * accelerations GPS shows at the same moments, [forward] (speeding up positive) and [left] (turning
 * left positive). Readings with a non-finite value are skipped. The receiver may sit in any way: a
 * RaceBox turned round or by 90°, a phone in a tilted holder.
 *
 * The two directions are found together, as the orthonormal pair that best maps the readings onto
 * GPS (orthogonal Procrustes: R = (M Mᵀ)^-½ M, M the 2 × axes cross-covariance), so braking while
 * turning does not leak cornering into forward. When they cannot both be found (GPS shows too
 * little of one, as on a straight line, or the accelerometer has one axis), only the one the
 * readings follow better is ([oneDirection]). Each direction then gets its sign, the bias of the
 * mount's tilt and its agreement with GPS from [fitAxis].
 */
@Suppress("ReturnCount")
fun fitMount(axes: List<DoubleArray>, forward: DoubleArray, left: DoubleArray): MountFit? {
    val k = axes.size
    val rows = forward.indices.filter { i ->
        forward[i].isFinite() && left[i].isFinite() && axes.all { it[i].isFinite() }
    }
    if (k == 0 || rows.size < MinimumFitPairs) return null
    val mean = DoubleArray(k) { a -> rows.sumOf { axes[a][it] } / rows.size }
    val meanF = rows.sumOf { forward[it] } / rows.size
    val meanL = rows.sumOf { left[it] } / rows.size
    // M: row 0 forward, row 1 left; column per axis.
    val m = Array(2) { DoubleArray(k) }
    for (i in rows) for (a in 0 until k) {
        val d = axes[a][i] - mean[a]
        m[0][a] += (forward[i] - meanF) * d
        m[1][a] += (left[i] - meanL) * d
    }
    val directions = procrustes(m)
        ?: oneDirection(m, rows.sumOf { sq(forward[it] - meanF) }, rows.sumOf { sq(left[it] - meanL) })
    fun along(w: DoubleArray?, gps: DoubleArray): AxisDirection? {
        w ?: return null
        val projected = DoubleArray(forward.size) { i -> w.indices.sumOf { a -> w[a] * axes[a][i] } }
        return fitAxis(projected, gps)?.let { AxisDirection(w, it) }
    }
    return MountFit(along(directions[0], forward), along(directions[1], left))
}

/**
 * When the two directions cannot both be found (GPS shows one too little, or the readings move the
 * same way for both, as a one-axis accelerometer does): only the direction whose GPS acceleration
 * the readings explain better (covariance² over the GPS variance: squared correlation times the
 * readings' spread), the other left unavailable, so one reading never comes out as both forward
 * and left.
 */
private fun oneDirection(m: Array<DoubleArray>, forwardVariance: Double, leftVariance: Double): Array<DoubleArray?> {
    fun explained(row: Int, gpsVariance: Double) =
        if (gpsVariance > 0.0) m[row].dot(m[row]) / gpsVariance else 0.0
    return if (explained(0, forwardVariance) >= explained(1, leftVariance)) {
        arrayOf(unit(m[0]), null)
    } else {
        arrayOf(null, unit(m[1]))
    }
}

private fun sq(x: Double) = x * x

/** (M Mᵀ)^-½ M for a 2 × k [m], whose rows are then orthonormal; null when M Mᵀ is near singular. */
private fun procrustes(m: Array<DoubleArray>): Array<DoubleArray?>? {
    val a = m[0].dot(m[0])
    val b = m[0].dot(m[1])
    val d = m[1].dot(m[1])
    val det = a * d - b * b
    val scale = (a + d) * (a + d)
    if (scale <= 0.0 || det <= MinDeterminant * scale) return null
    // For S = [[a, b], [b, d]], symmetric positive definite: √S = (S + s I) / t with s = √det and
    // t = √(a + d + 2s), so S^-½ = t · (S + s I)^-1.
    val s = sqrt(det)
    val t = sqrt(a + d + 2 * s)
    val p = a + s
    val q = d + s
    val inv = p * q - b * b
    val r00 = t * q / inv
    val r01 = -t * b / inv
    val r11 = t * p / inv
    val k = m[0].size
    return arrayOf(
        DoubleArray(k) { r00 * m[0][it] + r01 * m[1][it] },
        DoubleArray(k) { r01 * m[0][it] + r11 * m[1][it] },
    )
}

private fun DoubleArray.dot(o: DoubleArray): Double {
    var s = 0.0
    for (i in indices) s += this[i] * o[i]
    return s
}

private fun unit(v: DoubleArray): DoubleArray? {
    val n = sqrt(v.dot(v))
    return if (n > 0.0) DoubleArray(v.size) { v[it] / n } else null
}
