package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.feature.analysis.AnalysisState
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.util.Locale

// The lap analysis never ends in a spinner that does not stop or the app closing, and writes its
// numbers as the rest of the app does (FBBD-219).

private const val MetersPerKm = 1_000.0

/**
 * "2.41": a length in km as every other number of the app is written, with a point whatever the
 * phone's language (never "2,41" beside "1:52.07").
 */
internal fun kmText(meters: Double): String = String.format(Locale.ROOT, "%.2f", meters / MetersPerKm)

/** Said when the analysis of a session that could be read fails: the screen says so instead of the app closing. */
internal const val NotAnalysedText =
    "This session could not be analysed. The log in Settings › Diagnostics has the details."

/**
 * [block]'s result, or when it throws, [failed] with [NotAnalysedText]: a session the analysis trips
 * over shows that on its screen, never a spinner that does not end or the app closing.
 */
@Suppress("TooGenericExceptionCaught") // Whatever the analysis throws, the screen says so.
internal fun <T> analysedOr(id: String, failed: (AnalysisState.Unavailable) -> T, block: () -> T): T = try {
    block()
} catch (e: Exception) {
    DebugLog.w("SessionAnalysis", "analysing session $id failed", e)
    failed(AnalysisState.Unavailable(NotAnalysedText))
}
