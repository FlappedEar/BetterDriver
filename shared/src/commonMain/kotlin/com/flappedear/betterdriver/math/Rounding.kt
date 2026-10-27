package com.flappedear.betterdriver.math

import kotlin.math.floor

private const val Half = 0.5

/**
 * java.lang.Math.round(double) for common code: nearest Long, ties towards positive infinity,
 * NaN to 0 and values beyond the Long range to its ends. Spelled out instead of roundToLong,
 * whose results differ between Kotlin/Native and the JVM in edge cases; x - floor(x) is exact
 * for every double, so 0.49999999999999994 stays 0 as on the JVM.
 */
fun roundHalfUp(x: Double): Long {
    if (x.isNaN()) return 0L
    val down = floor(x)
    return (if (x - down >= Half) down + 1 else down).toLong()
}
