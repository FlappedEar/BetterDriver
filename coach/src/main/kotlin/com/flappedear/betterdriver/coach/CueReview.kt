package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.RecorderEvent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One spoken cue and, for a corner cue, the pass right after it; null until the corner was driven again. */
data class ReviewedCue(val cue: Cue, val result: CoachEvents.Result?)

/**
 * What the coach said in one session and what followed (FBBD-28, FBBD-99), read back from the
 * session's coach events. [problems] counts cues that could not be said (too late, or no voice).
 */
data class CueReview(val cues: List<ReviewedCue>, val problems: Int) {
    /** Corner cues with a pass after them. */
    val judged: Int get() = cues.count { it.result != null }
    val improved: Int get() = cues.count { it.result?.improved == true }

    /** For example "12 cues · 5 of 8 corners better"; "No cues" when the coach said nothing. */
    val summary: String
        get() = when {
            cues.isEmpty() -> "No cues"
            judged == 0 -> plural(cues.size, "cue")
            else -> "${plural(cues.size, "cue")} · $improved of $judged corners better"
        }

    companion object {
        /** The review of a session's [events]; events other than the coach's are ignored. */
        fun of(events: List<RecorderEvent>): CueReview {
            val results = events.filter { it.kind == CoachEvents.RESULT }
                .mapNotNull { CoachEvents.parseResult(it.text) }
                .associateBy { it.seq }
            val cues = events.filter { it.kind == CoachEvents.CUE }
                .mapNotNull { CoachEvents.parseCue(it.tMicros, it.text) }
                .map { ReviewedCue(it, results[it.seq]) }
            return CueReview(cues, events.count { it.kind == CoachEvents.PROBLEM })
        }
    }
}

/**
 * What a corner cue led to, in words for the review: the change it asked for, how far from the best
 * pass that left the driver, and the corner time. Null for a lap-time cue or before the next pass.
 */
fun ReviewedCue.outcome(): String? {
    val r = result
    val before = cue.before
    if (r == null || before == null) return null
    val target = cue.target
    val change = when (cue.advice) {
        is Advice.CarrySpeed -> speedChange(r.after.minKmh - before.minKmh, target?.let { it.minKmh - r.after.minKmh })
        else -> brakeChange(r.after.brakeAt - before.brakeAt, target?.let { it.brakeAt - r.after.brakeAt })
    }
    return "$change · ${timeChange(r.gainMicros)}"
}

private const val NearMeters = 2
private const val NearKmh = 1
private const val MicrosPerSecond = 1_000_000.0

/** "Braked 9 m later, 4 m short of best"; [toBest] is how much later the best pass braked. */
private fun brakeChange(moved: Double, toBest: Double?): String {
    val m = moved.roundToInt()
    val first = when {
        abs(m) < NearMeters -> "Braked at the same point"
        m > 0 -> "Braked $m m later"
        else -> "Braked ${-m} m earlier"
    }
    val gap = toBest?.roundToInt() ?: return first
    val second = when {
        abs(gap) < NearMeters -> "at the best point"
        gap > 0 -> "$gap m short of best"
        else -> "${-gap} m past best"
    }
    return "$first, $second"
}

/** "Minimum speed 3 km/h higher, 2 km/h under best". */
private fun speedChange(moved: Double, toBest: Double?): String {
    val v = moved.roundToInt()
    val first = when {
        abs(v) < NearKmh -> "Same minimum speed"
        v > 0 -> "Minimum speed $v km/h higher"
        else -> "Minimum speed ${-v} km/h lower"
    }
    val gap = toBest?.roundToInt() ?: return first
    val second = when {
        abs(gap) < NearKmh -> "at best"
        gap > 0 -> "$gap km/h under best"
        else -> "${-gap} km/h over best"
    }
    return "$first, $second"
}

/** "0.12 s gained" or "0.05 s lost" across the corner. */
private fun timeChange(gainMicros: Long): String {
    val s = String.format(Locale.ROOT, "%.2f s", abs(gainMicros) / MicrosPerSecond)
    return if (gainMicros >= 0) "$s gained" else "$s lost"
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
