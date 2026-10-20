package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.GnssSecond

// The longest gap of the drive checks from the continuous log (FBBD-183), against FBBD-8 and FBBD-46,
// from the receiver's first fix (FBBD-273).

private const val GapCriterion = "<= 200 ms, link drops left out (FBBD-8, FBBD-46)"

/**
 * As for dropped samples (FBBD-259), the steps up to the one ending on the receiver's first fix are
 * reported apart, for information (FBBD-273). The recorder logs them apart since 1.3.1
 * ([GnssSecond.maxRxPreGapMillis]); an older line counts them with the rest, and they are judged.
 */
internal fun logMaxGap(seconds: List<GnssSecond>): List<QualityMetric> {
    val split = seconds.any { it.maxRxPreGapMillis != null }
    val fixAt = seconds.firstOrNull { it.fixes > 0 }?.let { it.firstFixMicros ?: it.tMicros }
    val before = seconds.mapNotNull { it.maxRxPreGapMillis }.maxOrNull()
    val info = maxGapBeforeFixInfo(before?.toDouble() ?: 0.0, fixAt)
    val max = seconds.mapNotNull { it.maxRxGapMillis }.maxOrNull()
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
    listOfNotNull(
        g.maxRxPreGapMillis.takeIf { g.preFixLongGaps > 0 }
            ?.let { QualityNote(g.tMicros, "gap $it ms before the first fix, not judged") },
        g.maxRxGapMillis.takeIf { g.longGaps > 0 }?.let { QualityNote(g.tMicros, "gap $it ms") },
    )
}

private const val NoFixTimes = "the receiver gave no fix times"
