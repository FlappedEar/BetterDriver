package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.GnssSecond

// The longest gap of the drive checks from the continuous log (FBBD-183), against FBBD-8 and FBBD-46,
// from the receiver's first fix (FBBD-273).

private const val GapCriterion = "<= 200 ms, link drops left out (FBBD-8, FBBD-46)"

/**
 * As for dropped samples (FBBD-259), the steps up to the one ending on the receiver's first fix are
 * reported apart, for information (FBBD-273). The recorder logs them apart since 1.3.0
 * ([GnssSecond.maxRxPreGapMillis]); an older line counts them with the rest, and they are judged.
 */
internal fun logMaxGap(seconds: List<GnssSecond>): List<QualityMetric> {
    val fixAt = seconds.firstOrNull { it.fixes > 0 }?.let { it.firstFixMicros ?: it.tMicros }
    // Without any fix every step is judged, as in the session report (Codex on #260).
    val split = fixAt != null && seconds.any { it.maxRxPreGapMillis != null }
    val before = if (fixAt == null) null else seconds.mapNotNull { it.maxRxPreGapMillis }.maxOrNull()
    val info = maxGapBeforeFixInfo(before?.toDouble() ?: 0.0, fixAt)
    val max = seconds.mapNotNull { g ->
        if (fixAt == null) listOfNotNull(g.maxRxGapMillis, g.maxRxPreGapMillis).maxOrNull() else g.maxRxGapMillis
    }.maxOrNull()
    if (max == null) {
        val why = if (split) "no step between the receiver's fix times after its first fix" else NoFixTimes
        return listOf(notMeasured("gnss.max_gap", "Longest gap", GapCriterion, why), info)
    }
    val how = "between consecutive samples, by the receiver's fix times" +
        if (split) ", from the receiver's first fix" else ""
    return listOf(
        judged(
            "gnss.max_gap", "Longest gap", max.toDouble(), "ms", max * MicrosPerMilli <= MaxGapMicros, Basis.MEASURED,
            GapCriterion, how,
        ),
        info,
    )
}

/** Notes for the steps over 200 ms; those before the first fix (FBBD-273) marked as not judged. */
internal fun logGapNotes(seconds: List<GnssSecond>): List<QualityNote> = seconds.flatMap { g ->
    val noFix = seconds.none { it.fixes > 0 }
    val early = if (noFix) "" else " before the first fix, not judged"
    listOfNotNull(
        g.maxRxPreGapMillis.takeIf { g.preFixLongGaps > 0 }?.let { QualityNote(g.tMicros, "gap $it ms$early") },
        g.maxRxGapMillis.takeIf { g.longGaps > 0 }?.let { QualityNote(g.tMicros, "gap $it ms") },
    )
}

/** Steps over 200 ms judged: those before the first fix too when the receiver had none (Codex on #260). */
internal fun logLongGaps(seconds: List<GnssSecond>): Int {
    val noFix = seconds.none { it.fixes > 0 }
    return seconds.sumOf { it.longGaps + if (noFix) it.preFixLongGaps else 0 }
}

private const val NoFixTimes = "the receiver gave no fix times"
