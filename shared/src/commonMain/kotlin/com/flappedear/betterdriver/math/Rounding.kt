package com.flappedear.betterdriver.math

import kotlin.math.roundToLong

/**
 * java.lang.Math.round(double) for common code: nearest Long, ties towards positive infinity,
 * NaN to 0 and the infinities to the Long range, so results match the JVM.
 */
fun roundHalfUp(x: Double): Long = if (x.isNaN()) 0L else x.roundToLong()
