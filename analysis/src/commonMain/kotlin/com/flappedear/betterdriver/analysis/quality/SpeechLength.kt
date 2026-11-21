package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.RecorderEvent
import kotlin.math.abs
import kotlin.math.roundToLong

private const val P95 = 0.95
private const val MaxSayErrorMicros = 200_000L
private const val SayCriterion = "p95 |real - expected| <= 200 ms per language (FBBD-406)"

/**
 * How well the coach's phrase length estimate fits (FBBD-406): each cue's expected length as logged
 * (`say_ms`, at the set speech rate) against its sound's start to its end, when it played to the end.
 * Per language, as Polish and English share one time per word that no log has checked yet. Info only:
 * it tells the estimate to fit, not a fault of the session.
 */
internal fun speechLength(played: List<Pair<LoggedCue, Voiced>>, ends: List<RecorderEvent>): QualityMetric {
    val label = "Phrase length error p95"
    val endOf = ends.mapNotNull { e ->
        val f = fields(e.text)
        val seq = f["seq"]?.toIntOrNull()
        if (seq == null || f["end"] != "done") null else seq to e.tMicros
    }.toMap()
    val errors = played.mapNotNull { (c, v) ->
        val say = c.sayMicros ?: return@mapNotNull null
        val end = endOf[c.seq] ?: return@mapNotNull null
        c.lang to (end - v.tMicros - say).toDouble()
    }
    if (errors.isEmpty()) {
        val why = "no cue with its expected length and the end of its sound logged"
        return notMeasured("coach.say_length", label, SayCriterion, why)
    }
    val byLang = errors.groupBy({ it.first }, { it.second }).entries.sortedBy { it.key }
    val parts = byLang.map { (lang, es) ->
        val p95 = nearestRank(es.map(::abs), P95) ?: 0.0
        val bias = es.average()
        val ok = if (p95 <= MaxSayErrorMicros) "ok" else "over"
        "$lang: p95 ${(p95 / MicrosPerMilli).roundToLong()} ms ($ok), mean " +
            "${if (bias >= 0) "+" else ""}${(bias / MicrosPerMilli).roundToLong()} ms, ${es.size} cue(s)"
    }
    val worst = byLang.maxOf { (_, es) -> nearestRank(es.map(::abs), P95) ?: 0.0 }
    return info(
        "coach.say_length", label, worst / MicrosPerMilli, "ms", Basis.MEASURED,
        "$SayCriterion; sound start to end against the logged estimate, + = longer than expected; " +
            parts.joinToString("; "),
    )
}
