package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.format.fixed

/** Decimals of a value in the log line, trailing zeros then dropped. */
private const val ValueDecimals = 3

/**
 * The report as lines of the drive's log, written when the session ends (FBBD-183): a summary, then
 * one line per check with its verdict, value and FBBD keys. "gnss.dropped FAIL value=4.05 unit=%
 * keys=FBBD-8,FBBD-46". The full report, with how each value was found, stays in quality.json.
 */
fun QualityReport.logLines(): List<String> {
    val summary = "summary pass=${count(Verdict.PASS)} fail=${count(Verdict.FAIL)} " +
        "not_measured=${count(Verdict.NOT_MEASURED)}"
    return listOf(summary) + metrics.map { m ->
        val value = m.value?.let { fixed(it, ValueDecimals).trimEnd('0').trimEnd('.') } ?: "-"
        val unit = m.unit.ifBlank { "-" }.replace(' ', '_')
        val keys = m.keys.joinToString(",").ifEmpty { "-" }
        "${m.id} ${m.verdict} value=$value unit=$unit keys=$keys"
    }
}
