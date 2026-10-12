package com.flappedear.betterdriver.analysis.confidence

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.quality.provenanceText
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.Provenance
import java.util.Locale

// The confidence of each conclusion the analysis draws (FBBD-177): a time lost or to gain in a
// corner, a braking point, a minimum speed. Each takes the evidence of the laps it rests on
// (SessionConfidence) and adds what the conclusion itself claims: how many laps show it, and
// whether it is larger than the laps' usual spread there.

/** Laps that show a gain, at least, for it to be advice: never a single lap. */
const val MinimumSupportingLaps = 2

/** Fewer usable laps than this through a segment leave its conclusions Medium. */
const val ConsistentLaps = 3

/** Reason codes of what a conclusion claims. */
object ClaimCodes {
    const val ONE_LAP = "one_lap"
    const val SPREAD = "within_spread"
    const val FEW_LAPS = "few_laps"
    const val NOT_MEASURED = "not_measured"
}

/** The timed laps (by index) advice through [segmentId] may rest on: counted laps not graded Low there. */
fun SessionAnalysis.usableLaps(segmentId: String): List<Int> = laps.timedLaps.indices.filter {
    laps.timedLaps[it].referenceEligible && confidence.lapOnly(segmentId, it).level != Confidence.LOW
}

/** Lap [index]'s time through [segmentId], seconds; null when not fully covered. */
fun SessionAnalysis.segmentSeconds(segmentId: String, index: Int): Double? =
    lapTimes.getOrNull(index)?.sector(segmentId)?.seconds

/**
 * Of "lap [lapIndex] can gain [gainSeconds] through [segmentId]", as the fastest time there shows:
 * the lap's evidence, how many usable laps were at least half the gain faster ([MinimumSupportingLaps],
 * below it [oneLap]), whether the gain is larger than the usual spread of the usable laps there, and
 * whether there are [ConsistentLaps] of them.
 */
fun SessionAnalysis.gainConfidence(
    segmentId: String,
    lapIndex: Int,
    gainSeconds: Double,
    oneLap: Confidence = Confidence.MEDIUM,
): Graded {
    val usable = usableLaps(segmentId)
    val own = segmentSeconds(segmentId, lapIndex)
    val supporting = own?.let { t ->
        usable.count { it != lapIndex && (segmentSeconds(segmentId, it) ?: t) <= t - gainSeconds / 2 }
    } ?: 0
    var graded = confidence.lap(segmentId, lapIndex) + spread(segmentId, usable, gainSeconds)
    if (supporting < MinimumSupportingLaps) {
        val text = if (supporting == 1) "seen on one lap only" else "not seen on any usable lap"
        graded += ConfidenceReason(ClaimCodes.ONE_LAP, oneLap, text)
    }
    return graded
}

/**
 * Of "the fastest lap leaves [gapSeconds] through [segmentId]", against the fastest time there set
 * by lap [source] (a lap number): [gainConfidence] of the fastest lap, and the source lap's own
 * evidence. High without a fastest lap to grade.
 */
fun SessionAnalysis.bestGapConfidence(
    segmentId: String,
    gapSeconds: Double,
    source: Any?,
    oneLap: Confidence = Confidence.MEDIUM,
): Graded {
    val fastest = laps.fastestLapIndex ?: return Graded.High
    val sourceIndex = laps.timedLaps.indexOfFirst { it.number == source }
    val graded = gainConfidence(segmentId, fastest, gapSeconds, oneLap)
    return if (sourceIndex < 0) graded else graded + confidence.lapOnly(segmentId, sourceIndex)
}

/**
 * Of "lap [lapIndex] loses [lossSeconds] through [segmentId] against the fastest lap": both laps'
 * evidence, the usual spread there and the number of usable laps.
 */
fun SessionAnalysis.lossConfidence(segmentId: String, lapIndex: Int, lossSeconds: Double): Graded {
    val fastest = laps.fastestLapIndex
    val usable = usableLaps(segmentId)
    var graded = confidence.lap(segmentId, lapIndex) + spread(segmentId, usable, lossSeconds)
    if (fastest != null && fastest != lapIndex) graded += confidence.lapOnly(segmentId, fastest)
    return graded
}

/**
 * Of a value measured on lap [lapIndex] in [segmentId] (a braking point, a minimum speed) whose
 * values come from [provenance]: the lap's evidence, and Medium when it is not measured.
 */
fun SessionAnalysis.measureConfidence(segmentId: String, lapIndex: Int, provenance: Provenance?): Graded =
    confidence.lap(segmentId, lapIndex) + notMeasured(provenance)

/**
 * Of a spread over the laps through [segmentId] (how repeatable a braking point or a minimum speed
 * is): what applies to every lap there, and whether there are [ConsistentLaps] usable laps.
 */
fun SessionAnalysis.spreadConfidence(segmentId: String, provenance: Provenance? = null): Graded {
    val usable = usableLaps(segmentId)
    return confidence.segment(segmentId) + fewLaps(usable.size) + notMeasured(provenance)
}

/** Medium when the values come from something other than a measurement. */
fun notMeasured(provenance: Provenance?): ConfidenceReason? = provenance?.takeIf { !it.measured }?.let {
    ConfidenceReason(ClaimCodes.NOT_MEASURED, Confidence.MEDIUM, provenanceText(it) + ", not measured")
}

/** Medium below [ConsistentLaps] usable laps. */
internal fun fewLaps(count: Int): ConfidenceReason? = if (count < ConsistentLaps) {
    val text = when (count) {
        0 -> "no usable laps to compare"
        1 -> "only 1 usable lap"
        else -> "only $count usable laps"
    }
    ConfidenceReason(ClaimCodes.FEW_LAPS, Confidence.MEDIUM, text)
} else {
    null
}

/**
 * Medium when [claimSeconds] is no larger than the usual spread (interquartile range) of the [usable]
 * laps' times, or when there are too few of them.
 */
private fun SessionAnalysis.spread(segmentId: String, usable: List<Int>, claimSeconds: Double): Graded {
    val times = usable.mapNotNull { segmentSeconds(segmentId, it) }
    val summary = summarizeConsistency(times)
    val iqr = summary.interquartileRange
    val within = if (summary.available && iqr != null && claimSeconds <= iqr) {
        ConfidenceReason(
            ClaimCodes.SPREAD, Confidence.MEDIUM,
            String.format(Locale.ROOT, "within the usual lap-to-lap spread of %.2f s", iqr),
        )
    } else {
        null
    }
    return Graded.of(fewLaps(usable.size), within)
}
