package com.flappedear.betterdriver.format

import com.flappedear.betterdriver.math.roundHalfUp
import kotlin.math.abs

private const val Base = 10L

// Beyond this the scaled value no longer fits a Long; such numbers never reach the app's text.
private const val MaxScaled = 1e17

actual fun fixed(value: Double, decimals: Int): String {
    require(decimals >= 0) { "decimals must not be negative: $decimals" }
    var unit = 1L
    repeat(decimals) { unit *= Base }
    val scaled = abs(value) * unit
    return when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
        scaled >= MaxScaled -> value.toString()
        else -> {
            val n = roundHalfUp(scaled)
            // "%.1f" keeps the sign of a negative value that rounds to zero: -0.0.
            val sign = if (value < 0 || 1.0 / value < 0) "-" else ""
            val fraction = if (decimals == 0) "" else "." + (n % unit).toString().padStart(decimals, '0')
            "$sign${n / unit}$fraction"
        }
    }
}
