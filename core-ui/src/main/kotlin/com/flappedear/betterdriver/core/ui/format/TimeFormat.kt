package com.flappedear.betterdriver.core.ui.format

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** U+2212, close to the width of "+", unlike the hyphen. NumberText keeps it in place. */
const val Minus = "−"

private const val MillisPerHundredth = 10.0
private const val MillisPerTenth = 100L
private const val HundredthsPerSecond = 100L
private const val HundredthsPerMinute = 6_000L
private const val TenthsPerSecond = 10L
private const val TenthsPerMinute = 600L
private const val SecondsPerMinute = 60L
private const val MaxDeltaHundredths = 9_999L

/**
 * Lap time as m:ss.cc, for example 1:52.07. Hundredths are rounded.
 * Negative input is treated as zero.
 */
fun formatLapTime(millis: Long): String {
    val centis = (millis.coerceAtLeast(0) / MillisPerHundredth).roundToLong()
    val minutes = centis / HundredthsPerMinute
    val seconds = (centis / HundredthsPerSecond) % SecondsPerMinute
    val hundredths = centis % HundredthsPerSecond
    return String.format(Locale.ROOT, "%d:%02d.%02d", minutes, seconds, hundredths)
}

/**
 * Running lap clock as m:ss.c, for example 0:47.3. Tenths are truncated so
 * the clock never shows a time that has not happened yet.
 */
fun formatRunningTime(millis: Long): String {
    val tenths = millis.coerceAtLeast(0) / MillisPerTenth
    return String.format(
        Locale.ROOT,
        "%d:%02d.%d",
        tenths / TenthsPerMinute,
        (tenths / TenthsPerSecond) % SecondsPerMinute,
        tenths % TenthsPerSecond,
    )
}

/**
 * Signed gap in seconds with two decimals, for example −0.34 or +0.08.
 * Minus means ahead (gain). Zero is shown as +0.00 after rounding.
 * Gaps beyond ±99.99 s are clamped so the text width stays fixed.
 */
fun formatDelta(seconds: Double): String {
    val hundredths = deltaHundredths(seconds)
    val sign = if (hundredths < 0) Minus else "+"
    val a = abs(hundredths)
    return String.format(Locale.ROOT, "%s%d.%02d", sign, a / HundredthsPerSecond, a % HundredthsPerSecond)
}

/**
 * True when the gap shown by [formatDelta] is a gain (shown with a minus).
 * Colour from this, not the raw sign, so a gap that rounds to +0.00 is never
 * coloured as a gain.
 */
fun isDeltaGain(seconds: Double): Boolean = deltaHundredths(seconds) < 0

private fun deltaHundredths(seconds: Double): Long =
    (seconds * HundredthsPerSecond).roundToLong().coerceIn(-MaxDeltaHundredths, MaxDeltaHundredths)
