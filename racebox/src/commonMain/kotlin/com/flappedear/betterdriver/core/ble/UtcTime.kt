@file:Suppress("MagicNumber") // Calendar arithmetic.

package com.flappedear.betterdriver.core.ble

private const val SecondsPerDay = 86_400L
private const val SecondsPerHour = 3_600L
private const val SecondsPerMinute = 60L
private const val MillisPerSecond = 1_000L
private const val MaxHour = 23
private const val MaxMinute = 59
private const val MaxSecond = 59

/**
 * Milliseconds since 1970 for a UTC date and time, or null when the date or time does not exist,
 * as LocalDateTime.of(...).toInstant(ZoneOffset.UTC) would throw for it (java.time is JVM-only).
 */
@Suppress("LongParameterList", "ComplexCondition")
internal fun utcEpochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? {
    if (month !in 1..12 || day !in 1..daysInMonth(year, month) || hour !in 0..MaxHour ||
        minute !in 0..MaxMinute || second !in 0..MaxSecond
    ) {
        return null
    }
    val seconds = epochDay(year, month, day) * SecondsPerDay +
        hour * SecondsPerHour + minute * SecondsPerMinute + second
    return seconds * MillisPerSecond
}

private fun isLeap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (isLeap(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Days since 1970-01-01 in the proleptic Gregorian calendar (H. Hinnant's days_from_civil). */
private fun epochDay(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = y.floorDiv(400L)
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}
