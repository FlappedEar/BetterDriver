package com.flappedear.betterdriver.format

/**
 * [value] with [decimals] digits after the point, as String.format(Locale.ROOT, "%.Nf"), which
 * common code cannot call. The JVM keeps String.format, so the Android app's text is unchanged.
 * iOS rounds half up from the binary value, so it can differ from the JVM in the last digit
 * on exact decimal ties.
 */
expect fun fixed(value: Double, decimals: Int): String

/** As [fixed] with a sign always shown, as "%+.Nf": +0.25, -0.25. */
fun signedFixed(value: Double, decimals: Int): String {
    val text = fixed(value, decimals)
    return if (text.startsWith("-") || text.startsWith("N")) text else "+$text"
}
