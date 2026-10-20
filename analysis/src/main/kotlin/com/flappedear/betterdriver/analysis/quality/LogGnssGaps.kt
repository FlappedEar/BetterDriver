package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.GnssSecond

// The longest gap of the drive checks from the continuous log (FBBD-183), against FBBD-8 and FBBD-46,
// from the receiver's first fix (FBBD-273).

private const val GapCriterion = "<= 200 ms, link drops left out (FBBD-8, FBBD-46)"
private const val NoFixTimes = "the receiver gave no fix times"

/** The seconds after the one with the receiver's first fix; all of them when it had none (FBBD-273). */
internal fun afterFix(seconds: List<GnssSecond>): List<GnssSecond> {
    val fixAt = seconds.indexOfFirst { it.fixes > 0 }
    return if (fixAt < 0) seconds else seconds.drop(fixAt + 1)
}

/**
 * As for dropped samples (FBBD-259), the gaps up to the second of the receiver's first fix are kept
 * apart and reported for information (FBBD-273); without any fix every gap is judged.
 */
internal fun logMaxGap(seconds: List<GnssSecond>): List<QualityMetric> {
    val fixAt = seconds.indexOfFirst { it.fixes > 0 }
    val from = if (fixAt < 0) 0 else fixAt + 1
    val before = seconds.take(from).mapNotNull { it.maxRxGapMillis }.maxOrNull()
    val firstFix = seconds.getOrNull(fixAt)?.let { it.firstFixMicros ?: it.tMicros }
    val info = maxGapBeforeFixInfo(before?.toDouble() ?: 0.0, firstFix)
    val how = "between consecutive samples, by the receiver's fix times"
    val max = afterFix(seconds).mapNotNull { it.maxRxGapMillis }.maxOrNull()
        ?: return listOf(notMeasured("gnss.max_gap", "Longest gap", GapCriterion, NoFixTimes), info)
    val judgedHow = if (fixAt < 0) how else "$how, from the receiver's first fix"
    return listOf(
        judged(
            "gnss.max_gap", "Longest gap", max.toDouble(), "ms", max * MicrosPerMilli <= MaxGapMicros, Basis.MEASURED,
            GapCriterion, judgedHow,
        ),
        info,
    )
}
