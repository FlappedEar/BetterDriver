@file:Suppress("MagicNumber") // Calendar arithmetic and field widths.

package com.flappedear.betterdriver.recorder.vbo

import com.flappedear.betterdriver.format.fixed

private const val MillisPerDay = 86_400_000L

/** "dd/MM/yyyy @ HH:mm:ss" in UTC: the date line of a VBO file. */
internal fun vboDate(utcMillis: Long): String {
    val (y, mo, d) = civil(utcMillis.floorDiv(MillisPerDay))
    val ms = utcMillis.mod(MillisPerDay)
    return "${vboPadded(d, 2)}/${vboPadded(mo, 2)}/${vboPadded(y, 4)} @ " +
        "${vboPadded(ms / 3_600_000, 2)}:${vboPadded(ms / 60_000 % 60, 2)}:${vboPadded(ms / 1_000 % 60, 2)}"
}

/** As "%0Nd": [n] in at least [width] characters, zero padded after a minus sign. */
internal fun vboPadded(n: Long, width: Int): String =
    if (n < 0) "-" + (-n).toString().padStart(width - 1, '0') else n.toString().padStart(width, '0')

/**
 * As "%0W.Df" (or "%+0W.Df" with [plus]): [v] with [decimals] digits after the point in at least
 * [width] characters, zero padded after the sign.
 */
internal fun vboPadded(v: Double, decimals: Int, width: Int, plus: Boolean = false): String {
    val text = fixed(v, decimals)
    val negative = text.startsWith("-")
    val sign = if (negative) "-" else if (plus) "+" else ""
    val digits = if (negative) text.substring(1) else text
    return sign + digits.padStart(width - sign.length, '0')
}

/**
 * Year, month and day of the [days]th day after 1970-01-01, proleptic Gregorian
 * (H. Hinnant's civil_from_days).
 */
private fun civil(days: Long): Triple<Long, Long, Long> {
    val z = days + 719_468
    val era = z.floorDiv(146_097)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
}
