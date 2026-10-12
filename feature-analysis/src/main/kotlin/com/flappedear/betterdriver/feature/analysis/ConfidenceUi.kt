package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.confidence.Graded
import com.flappedear.betterdriver.analysis.confidence.bestGapConfidence
import com.flappedear.betterdriver.recorder.Confidence

// No precise advice from weak evidence (FBBD-177): every conclusion the analysis screen draws
// carries its confidence next to it, and why it is not High. A Low one shows no number of
// seconds, metres or km/h; a Medium time to gain is said in words.

/** A conclusion's confidence as the screen shows it: [level], and [why] it is not High (empty when it is). */
data class ConfidenceUi(val level: Confidence = Confidence.HIGH, val why: String = "") {
    /** "High", "Medium" or "Low". */
    val label: String get() = level.label

    /** "Medium · only 2 usable laps"; just the label when there is nothing to say. */
    val text: String get() = if (why.isEmpty()) label else "$label · $why"

    /** Below High: shown in the warning colour. */
    val doubtful: Boolean get() = level != Confidence.HIGH

    /** The line under a conclusion: "High confidence", or "Medium confidence · only 2 usable laps". */
    val note: String get() = if (why.isEmpty()) "$label confidence" else "$label confidence · $why"

    companion object {
        val High = ConfidenceUi()
    }
}

/** [this] as the screen shows it. */
fun Graded.ui(): ConfidenceUi = ConfidenceUi(level, why)

/** A time lost or to gain at Medium confidence: no number, just that there is some. */
const val SomeTimeText = "some"

/** A time lost or to gain at Low confidence: nothing is said about it. */
const val UnclearText = "unclear"

/**
 * A time lost or to gain ([seconds]) as the screen shows it at [confidence]: [format]ted at High,
 * [SomeTimeText] at Medium, [UnclearText] at Low.
 */
fun timeText(seconds: Double, confidence: ConfidenceUi, format: (Double) -> String): String =
    when (confidence.level) {
        Confidence.HIGH -> format(seconds)
        Confidence.MEDIUM -> SomeTimeText
        Confidence.LOW -> UnclearText
    }

/**
 * Of the time to gain to the theoretical best (FBBD-177): the weakest confidence of the corners and
 * straights that add to it (each leaving more than [MeaningfulLossSeconds]), and for each of those
 * below High, its name and why.
 */
fun totalGainConfidence(analysis: SessionAnalysis): ConfidenceUi {
    val graded = analysis.summary?.gains.orEmpty()
        .filter { (it.lossSeconds ?: 0.0) > MeaningfulLossSeconds }
        .map { it.name to analysis.bestGapConfidence(it.segmentId, it.lossSeconds ?: 0.0, it.sourceLapReference) }
    val weak = graded.filter { it.second.level != Confidence.HIGH }.sortedBy { it.second.level }
    val level = Confidence.lowest(graded.map { it.second.level })
    return ConfidenceUi(level, weak.joinToString("; ") { (name, g) -> "$name: ${g.why}" })
}

/** A corner detail value ([text]) at [confidence]: [Missing] at Low, since nothing precise rests on it. */
fun valueText(text: String, confidence: ConfidenceUi?): String =
    if (confidence?.level == Confidence.LOW) Missing else text
