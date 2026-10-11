package com.flappedear.betterdriver.feature.track

import kotlin.math.abs

private const val MillisPerMinute = 60_000L
private const val MillisPerSecond = 1_000L
private const val MillisPerHundredth = 10L
private const val HundredthsPerSecond = 100L

/** Formats a lap time as m:ss.SSS, for example 1:42.317. */
fun formatLapTime(millis: Long): String {
    require(millis >= 0) { "A lap time cannot be negative: $millis" }
    val minutes = millis / MillisPerMinute
    val seconds = millis % MillisPerMinute / MillisPerSecond
    val rest = millis % MillisPerSecond
    return "%d:%02d.%03d".format(minutes, seconds, rest)
}

/** Formats a delta in seconds with a sign, for example -0.42 or +1.07. */
fun formatDelta(millis: Long): String {
    val sign = if (millis < 0) "-" else "+"
    // Rounded to the nearest hundredth.
    val hundredths = (abs(millis) + MillisPerHundredth / 2) / MillisPerHundredth
    return "%s%d.%02d".format(sign, hundredths / HundredthsPerSecond, hundredths % HundredthsPerSecond)
}
