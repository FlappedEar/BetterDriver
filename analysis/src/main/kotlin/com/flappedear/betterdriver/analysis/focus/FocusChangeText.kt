package com.flappedear.betterdriver.analysis.focus

import com.flappedear.betterdriver.recorder.Confidence
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [changes] in words: "Your 2 fastest passes braked 8 m later and carried 4 km/h more minimum
 * speed than your other 4." Numbers only at High confidence ([level]), and only for a change from
 * measured values ([FocusChange.measured]): an inferred braking point or a calculated speed is said
 * without its number. The pass counts are those the changes rest on ([FocusChange.fasterCount],
 * [FocusChange.otherCount]; [faster] and [other], the group sizes, where not known); when the
 * changes rest on different counts, none is said. Nothing when there is no difference worth a change.
 */
fun changeText(changes: List<FocusChange>, faster: Int, other: Int, level: Confidence): String {
    if (changes.isEmpty() || level == Confidence.LOW) return ""
    val precise = level == Confidence.HIGH
    val parts = changes.map { phrase(it, precise) }
    val joined = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    val counts = changes.map { c ->
        Pair(c.fasterCount.takeIf { it > 0 } ?: faster, c.otherCount.takeIf { it > 0 } ?: other)
    }.distinct().singleOrNull()
    val passes = if (precise && counts != null) "Your ${counts.first} fastest passes" else "Your fastest passes"
    val others = if (precise && counts != null) "your other ${counts.second}" else "your others"
    return "$passes $joined than $others."
}

private fun phrase(c: FocusChange, precise: Boolean): String {
    val more = c.delta > 0
    // An inferred braking point or a calculated speed carries no number (FBBD-177).
    val amount = if (precise && c.measured) amount(c) else ""
    return when (c.metric) {
        FocusMetric.BrakingPoint -> "braked ${amount}${if (more) "later" else "earlier"}"
        FocusMetric.MinimumSpeed -> "carried ${amount}${if (more) "more" else "less"} minimum speed"
        FocusMetric.ExitSpeed -> "left the corner ${amount}${if (more) "faster" else "slower"}"
    }
}

/** "8 m ", "4 km/h ": the size of the difference, rounded to a whole number, one at least. */
private fun amount(c: FocusChange): String {
    val n = max(1, abs(c.delta).roundToInt())
    val unit = c.unit.trim()
    return if (unit.isEmpty()) "$n " else String.format(Locale.ROOT, "%d %s ", n, unit)
}
