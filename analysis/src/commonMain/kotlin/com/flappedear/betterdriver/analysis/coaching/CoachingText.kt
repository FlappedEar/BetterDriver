package com.flappedear.betterdriver.analysis.coaching

import com.flappedear.betterdriver.analysis.confidence.ConsistentLaps
import com.flappedear.betterdriver.analysis.focus.FocusMetric
import com.flappedear.betterdriver.analysis.progression.DayZone
import com.flappedear.betterdriver.analysis.progression.ProgressionDay
import com.flappedear.betterdriver.analysis.progression.dayMonthLabel
import com.flappedear.betterdriver.format.fixed
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// The coaching history in words (FBBD-180), as the session summary shows it beside the next-session
// focus. A number is said only where it is precise (every pass behind it graded High, its channel
// measured, its unit known); otherwise the same sentence says it without the number. Pass counts are
// said either way: they are not advice (FBBD-177). Common Kotlin: the Android and iOS summaries
// say it the same (FBBD-364).

/**
 * What came of [a] in its session, for example: "You worked on Corner 4 with the coach in this
 * session (5 cues there). The braking point moved 8 m later and the median corner time improved
 * by 0.18 s; 3 of 4 counted passes were faster than before. This is no longer a top-three loss."
 */
fun attemptText(a: FocusAttempt): String {
    val name = a.ask.name
    val cues = if (a.cues > 0) " (${plural(a.cues, "cue")} there)" else ""
    val who = if (a.coached) {
        "You worked on $name with the coach in this session$cues"
    } else {
        "$name was this session's focus$cues"
    }
    return when (a.verdict) {
        FocusVerdict.NotComparable ->
            "$name was this session's focus, but the track's corners were revised since, so it cannot be compared."
        FocusVerdict.NotDriven -> "$who, but no pass through it counts: none can be compared with before."
        FocusVerdict.TooFewPasses ->
            "$who. Too few passes count, before or now ($ConsistentLaps needed each), to tell whether it helped."
        FocusVerdict.NoGain, FocusVerdict.Improved -> {
            val parts = a.shifts.map { shiftPhrase(it) } + timePhrase(a.gainSeconds ?: 0.0, a.timePrecise)
            val faster = "; ${a.fasterPasses} of ${plural(a.passes, "counted pass")} were faster than before"
            val top = when (a.topThree) {
                true -> " It is still a top-three loss."
                false -> " This is no longer a top-three loss."
                null -> ""
            }
            "$who. ${joined(parts).replaceFirstChar { it.uppercase() }}$faster.$top"
        }
    }
}

/**
 * Whether [c]'s improvement held, for example: "Corner 4, worked on 12 Oct: the gain held. The
 * median corner time is 0.15 s better than before the focus, and the braking point 7 m later."
 * [zone] dates the session that worked on it.
 */
fun checkText(c: FocusCheck, zone: DayZone = DayZone.Utc): String {
    val held = when (c.persistence) {
        Persistence.Persisted -> "the gain held"
        Persistence.Partial -> "the gain partly held"
        Persistence.Reverted -> "the gain was lost"
    }
    val time = if (c.timePrecise) {
        val s = seconds(c.gainSeconds)
        if (c.gainSeconds >= 0) "the median corner time is $s better than before the focus" else
            "the median corner time is $s slower than before the focus"
    } else {
        when (c.persistence) {
            Persistence.Persisted -> "the median corner time is still better than before the focus"
            Persistence.Partial -> "the median corner time is better than before the focus, by less than it was"
            Persistence.Reverted -> "the median corner time is no better than before the focus"
        }
    }
    val parts = listOf(time) + c.shifts.map { nowPhrase(it) }
    val said = joined(parts).replaceFirstChar { it.uppercase() }
    return "${c.attempt.ask.name}, worked on ${dayOf(c.attempt.session, zone)}: $held. $said."
}

/** "the braking point moved 8 m later", or without its number when not precise. */
private fun shiftPhrase(s: MetricShift): String {
    val amount = if (s.precise) amount(s) else ""
    val by = if (amount.isEmpty()) "" else " by ${amount.trimEnd()}"
    return when (s.metric) {
        FocusMetric.BrakingPoint -> if (s.moved) {
            "the braking point moved ${amount}${if (s.delta > 0) "later" else "earlier"}"
        } else {
            "the braking point stayed where it was"
        }
        FocusMetric.MinimumSpeed -> if (s.moved) {
            "the minimum speed ${if (s.delta > 0) "rose" else "fell"}$by"
        } else {
            "the minimum speed stayed the same"
        }
        FocusMetric.ExitSpeed -> if (s.moved) {
            "the exit speed ${if (s.delta > 0) "rose" else "fell"}$by"
        } else {
            "the exit speed stayed the same"
        }
    }
}

/** Where a change that came with an improvement stands now, against before the focus. */
private fun nowPhrase(s: MetricShift): String {
    val amount = if (s.precise) amount(s) else ""
    val more = s.delta > 0
    return when (s.metric) {
        FocusMetric.BrakingPoint -> "the braking point ${amount}${if (more) "later" else "earlier"}"
        FocusMetric.MinimumSpeed -> "the minimum speed ${amount}${if (more) "higher" else "lower"}"
        FocusMetric.ExitSpeed -> "the exit speed ${amount}${if (more) "higher" else "lower"}"
    }
}

/** "the median corner time improved by 0.18 s", or without its number when not precise. */
private fun timePhrase(gain: Double, precise: Boolean): String = when {
    gain >= ImprovedSeconds -> "the median corner time improved" + if (precise) " by ${seconds(gain)}" else ""
    gain <= -ImprovedSeconds -> "the median corner time got " + if (precise) "${seconds(-gain)} slower" else "slower"
    else -> "the median corner time stayed the same"
}

/** "8 m ", "4 km/h ": the size of the change, rounded to a whole number, one at least. */
private fun amount(s: MetricShift): String = "${max(1, abs(s.delta).roundToInt())} ${s.unit.trim()} "

private fun seconds(value: Double) = "${fixed(abs(value), 2)} s"

private fun joined(parts: List<String>): String =
    if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()

private fun plural(n: Int, word: String) = when {
    n == 1 -> "1 $word"
    word.endsWith("s") -> "$n ${word}es"
    else -> "$n ${word}s"
}

/** "12 Oct": the day [record]'s session was driven, in [zone]. */
private fun dayOf(record: SessionCoachingRecord, zone: DayZone): String =
    ProgressionDay.at(record.sessionStartUtcMillis, zone).dayMonthLabel()
