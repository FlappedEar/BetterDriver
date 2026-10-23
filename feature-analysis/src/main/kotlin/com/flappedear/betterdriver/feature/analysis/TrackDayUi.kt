package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.line.CornerAdvice
import com.flappedear.betterdriver.analysis.line.DayAnalysis
import com.flappedear.betterdriver.analysis.line.DayCorner
import com.flappedear.betterdriver.analysis.line.DayLoss
import com.flappedear.betterdriver.analysis.line.DayProof
import com.flappedear.betterdriver.analysis.line.PlanAdvice
import com.flappedear.betterdriver.analysis.line.ProofVerdict
import com.flappedear.betterdriver.analysis.line.PushLapShare
import com.flappedear.betterdriver.analysis.line.LineThresholds
import java.util.Locale
import kotlin.math.roundToInt

// Pure Kotlin (no Android): the track day page (FBBD-315, UI round 48: UI-211 A numbers and the
// sessions chart, UI-212 A corners in words with their kind, UI-213 A a small line per corner,
// UI-214 B next time here and what came of the last day), made from the day's analysis and tested
// on the JVM.

/** One session of the day in the chart: its best and typical lap. */
data class TrackDaySessionUi(val label: String, val bestSeconds: Double?, val typicalSeconds: Double?)

/** One corner where the day can get faster. */
data class TrackDayCornerUi(
    val tag: String,
    /** What a typical lap at the day's pace loses here. */
    val perLapSeconds: Double,
    /** "Even your best lap loses 0.11 s" or "Your best lap does it: repeat it". */
    val kind: String,
    /** Whether even the best lap loses here (shown in the warning colour). */
    val learn: Boolean,
    val todo: List<String>,
    /** Its typical pass in each session, in the order they were driven; null where it was not driven. */
    val trend: List<Double?>,
)

/** One focus corner of the last day here, on this one. */
data class TrackDayProofRowUi(val tag: String, val verdict: String, val tone: ProofTone, val line: String)

/** How a proof row reads. */
enum class ProofTone { Better, Same, Worse, Unknown }

/** What came of the last day's focus here. */
data class TrackDayProofUi(
    val title: String,
    val wonBack: String?,
    val better: Boolean,
    val rows: List<TrackDayProofRowUi>,
)

/** The track day page. */
data class TrackDayUi(
    /** "Jastrząb · Fri 29 Aug" and "6 sessions · 25 laps · 8 at the day's pace". */
    val title: String,
    val subtitle: String,
    val best: String,
    val typical: String,
    val perfect: String,
    /** "A typical lap at your pace loses 2.80 s to your targets; your best lap leaves 0.41 s." */
    val loss: String,
    val sessions: List<TrackDaySessionUi>,
    /** What the chart shows, in words. */
    val overDay: String?,
    /** Most per lap first; only corners with time to find. */
    val corners: List<TrackDayCornerUi>,
    /** The day's focus, at most three: what the next day here works on. */
    val next: List<TrackDayCornerUi>,
    /** What the coach does with [next] (UI-214 B); null when it does not coach here. */
    val coachLine: String?,
    val proof: TrackDayProofUi?,
)

/** What the track day page shows. */
sealed interface TrackDayState {
    data object Reading : TrackDayState

    data class Unavailable(val why: String) : TrackDayState

    data class Ready(val ui: TrackDayUi) : TrackDayState
}

/**
 * The page of [day] at [trackName] on [dayLabel] ("Fri 29 Aug"). [proof] is what came of the last
 * day here, [previousDayLabel] that day as the page names it. [coaches]: whether the coach works on
 * the day's focus at the next day's first session (UI-214 B), so the page says so.
 */
@Suppress("LongParameterList") // The day and how the page names it.
fun trackDayUi(
    day: DayAnalysis,
    trackName: String,
    dayLabel: String,
    proof: DayProof? = null,
    previousDayLabel: String? = null,
    coaches: Boolean = true,
): TrackDayUi {
    val corners = day.ranked.filter { it.perLapGainSeconds > 0.0 }.map(::cornerUi)
    val next = day.focus().map(::cornerUi)
    return TrackDayUi(
        title = listOf(trackName, dayLabel).filter { it.isNotBlank() }.joinToString(" · "),
        subtitle = "${day.sessions.size} sessions · ${plural(day.sessions.sumOf { it.laps }, "lap")} · " +
            "${day.sessions.sumOf { it.paceLaps }} at the day's pace",
        best = lineLapTime(day.referenceLapSeconds),
        typical = lineLapTime(day.typicalLapSeconds),
        perfect = lineLapTime(day.perfectLapSeconds),
        loss = "A typical lap at your pace loses ${s(day.perLapGainSeconds)} s to your targets; " +
            "your best lap leaves ${s(day.bestLapGainSeconds)} s.",
        sessions = day.sessions.map { TrackDaySessionUi(it.label, it.bestLapSeconds, it.typicalLapSeconds) },
        overDay = overDay(day),
        corners = corners,
        next = next,
        coachLine = if (coaches && next.isNotEmpty()) {
            "The coach starts your next day here on ${next.joinToString(", ") { it.tag }}; " +
                "later sessions work on what the session before them found."
        } else {
            null
        },
        proof = proof?.let { proofUi(it, previousDayLabel ?: it.previousDay) },
    )
}

private fun cornerUi(c: DayCorner): TrackDayCornerUi {
    val learn = c.loss == DayLoss.BestLap
    return TrackDayCornerUi(
        tag = cornerTag(c.name),
        perLapSeconds = c.perLapGainSeconds,
        kind = if (learn) "Even your best lap loses ${s(c.bestLapGainSeconds)} s" else RepeatText,
        learn = learn,
        todo = adviceTodo(c.advice.plan()).ifEmpty { listOf(NoAdviceText) },
        trend = c.sessions.map { it.typicalSeconds },
    )
}

/** A corner where the day's best lap already matches the target. */
internal const val RepeatText = "Your best lap does it: repeat it"

/** A corner with time per lap but no difference in the line, the brakes or the speed beyond GPS accuracy. */
internal const val NoAdviceText = "Nothing measurably different from your target: drive it as on your best pass."

private fun CornerAdvice.plan() = PlanAdvice(brakeMeters, apexMeters, tighterMeters, minimumSpeedKmh, pickupMeters)

/**
 * The chart in words: how much the typical lap came down from the day's first session to its
 * quickest, and how much it went back up in the last one when it did.
 */
internal fun overDay(day: DayAnalysis): String? {
    val typical = day.sessions.filter { it.typicalLapSeconds != null }
    if (typical.size < 2) return null
    val first = typical.first()
    val quickest = typical.minBy { it.typicalLapSeconds!! }
    val last = typical.last()
    val noise = LineThresholds().gainSeconds
    val down = first.typicalLapSeconds!! - quickest.typicalLapSeconds!!
    val parts = mutableListOf<String>()
    if (quickest !== first && down >= noise) {
        parts += "Your typical lap got ${roundSeconds(down)} s faster from ${first.label} to ${quickest.label}"
    } else {
        parts += "Your typical lap did not get faster after ${first.label}"
    }
    val up = last.typicalLapSeconds!! - quickest.typicalLapSeconds!!
    if (last !== quickest && up >= noise) parts += "at ${last.label} it was ${s(up)} s slower than at ${quickest.label}"
    return parts.joinToString("; ") + "."
}

private fun proofUi(proof: DayProof, previous: String): TrackDayProofUi {
    val compared = proof.corners.any { it.wonBackSeconds != null }
    return TrackDayProofUi(
        title = "Last day here ($previous) · what came of it",
        wonBack = if (compared) "${s(proof.wonBackSeconds)} s" else null,
        better = proof.wonBackSeconds > 0.0,
        rows = proof.corners.map { c ->
            val tag = cornerTag(c.name)
            val after = c.afterSeconds
            when (c.verdict) {
                ProofVerdict.Faster -> TrackDayProofRowUi(
                    tag, "Faster", ProofTone.Better,
                    "${s(c.wonBackSeconds!!)} s back a lap · typical ${s(c.beforeSeconds)} → ${s(after!!)} s",
                )
                ProofVerdict.Slower -> TrackDayProofRowUi(
                    tag, "Slower", ProofTone.Worse,
                    "${s(-c.wonBackSeconds!!)} s slower a lap · typical ${s(c.beforeSeconds)} → ${s(after!!)} s",
                )
                ProofVerdict.Same -> TrackDayProofRowUi(
                    tag, "Same", ProofTone.Same, "Within GPS timing · typical ${s(c.beforeSeconds)} → ${s(after!!)} s",
                )
                ProofVerdict.NotDriven -> TrackDayProofRowUi(
                    tag, "Not driven", ProofTone.Unknown, "No lap through it today.",
                )
                ProofVerdict.NotComparable -> TrackDayProofRowUi(
                    tag, "Not comparable", ProofTone.Unknown, "The track's corners changed since that day.",
                )
            }
        },
    )
}

private fun s(value: Double) = String.format(Locale.ROOT, "%.2f", value)

private fun roundSeconds(value: Double): String =
    if (value >= WholeSecondsFrom) value.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", value)

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

/** From this many seconds, a whole-day change is said in whole seconds. */
private const val WholeSecondsFrom = 10.0

/** For the page's own explanation of the typical lap. */
internal val PaceSharePercent: Int = ((PushLapShare - 1.0) * 100).roundToInt()
