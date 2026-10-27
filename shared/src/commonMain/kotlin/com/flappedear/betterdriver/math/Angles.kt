package com.flappedear.betterdriver.math

import kotlin.math.PI

// java.lang.Math.toRadians/toDegrees for common code. The factors are the JDK's own
// (DEGREES_TO_RADIANS, RADIANS_TO_DEGREES), so results match the JVM bit for bit.
private const val DegreesToRadians = PI / 180.0
private const val RadiansToDegrees = 180.0 / PI

/** Degrees to radians, as java.lang.Math.toRadians. */
fun toRadians(degrees: Double): Double = degrees * DegreesToRadians

/** Radians to degrees, as java.lang.Math.toDegrees. */
fun toDegrees(radians: Double): Double = radians * RadiansToDegrees
