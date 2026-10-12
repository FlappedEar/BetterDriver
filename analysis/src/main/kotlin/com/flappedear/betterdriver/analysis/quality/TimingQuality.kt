package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import kotlin.math.abs
import kotlin.math.roundToLong

// The timing part of the session validation report (FBBD-183), against FBBD-12 and FBBD-61: laps
// that look like a phantom or a missed line, and the predicted lap time at the line.

private const val PhantomShare = 0.8
private const val MissedShare = 1.6
private const val MinLapsForMedian = 3
private const val MaxPredictedErrorMillis = 100L

/** A finished lap as live timing logged it, or as a replay found it. */
private class LapLine(val atMicros: Long, val number: Int, val kind: String, val millis: Long, val predicted: Long?)

internal fun timingSection(data: SessionData, track: Track?): QualitySection {
    val logged = data.events.ofKind(EventKinds.LAP_LINE).mapNotNull(::lapLine)
    val replayed = if (logged.isEmpty() && track != null && track.timeable) replay(data, track) else null
    if (logged.isEmpty() && replayed == null) {
        val why = "no lap logged and no saved track with a start/finish line to time the session against"
        return QualitySection("Timing", listOf(notMeasured("timing.laps", "Timed laps", null, why)))
    }
    val (laps, basis, source) = if (logged.isEmpty()) {
        Triple(replayed.orEmpty(), Basis.DERIVED, "the session's fixes replayed through the live lap timer")
    } else {
        Triple(logged, Basis.MEASURED, "laps live timing logged at the line")
    }
    val timed = laps.filter { it.kind == LapKind.Timed.name }
    val (suspect, notes) = suspectLaps(timed)
    val metrics = listOf(
        info("timing.laps", "Timed laps", timed.size.toDouble(), "", basis, source),
        suspect,
        predictedError(logged),
    )
    return QualitySection("Timing", metrics, notes)
}

private fun lapLine(e: RecorderEvent): LapLine? {
    val f = fields(e.text)
    val number = f["lap"]?.toIntOrNull()
    val millis = f["time_ms"]?.toLongOrNull()
    return if (number == null || millis == null) {
        null
    } else {
        LapLine(e.tMicros, number, f["kind"].orEmpty(), millis, f["predicted_ms"]?.toLongOrNull())
    }
}

private fun replay(data: SessionData, track: Track): List<LapLine> {
    val fixes = mutableListOf<Fix>()
    for (set in data.sampleSets.sortedBy { it.tMicros }) {
        val fix = set.toFix() ?: continue
        if (fixes.isEmpty() || fix.tMicros > fixes.last().tMicros) fixes += fix
    }
    return timeSession(track, fixes.asSequence()).laps.map {
        LapLine(it.endMicros, it.number, it.kind.name, it.timeMillis, null)
    }
}

private const val SuspectCriterion = "0 missed and 0 phantom laps (FBBD-12, FBBD-61)"

/**
 * Timed laps far from the median: under 80 % may be a phantom line crossing, over 160 % may hide a
 * missed one. A slow lap behind traffic stays under 160 %. Flagged, not proven.
 */
private fun suspectLaps(timed: List<LapLine>): Pair<QualityMetric, List<QualityNote>> {
    val label = "Suspect laps"
    if (timed.size < MinLapsForMedian) {
        return notMeasured("timing.suspect", label, SuspectCriterion, "fewer than three timed laps") to emptyList()
    }
    val median = timed.map { it.millis.toDouble() }.sorted()[(timed.size - 1) / 2]
    val notes = timed.mapNotNull { lap ->
        val share = lap.millis / median
        val why = when {
            share < PhantomShare -> "possible phantom lap"
            share > MissedShare -> "possible missed line"
            else -> null
        }
        val percent = (share * Percent).roundToLong()
        why?.let { QualityNote(lap.atMicros, "lap ${lap.number}: $it ($percent % of the median)") }
    }
    val how = "timed laps under 80 % or over 160 % of the median lap time (${median.roundToLong()} ms); " +
        "flagged for a check against RaceChrono, not proven"
    val count = notes.size.toDouble()
    return judged("timing.suspect", label, count, "", notes.isEmpty(), Basis.DERIVED, SuspectCriterion, how) to notes
}

private const val PredictedCriterion = "within 0.1 s of the lap time on every lap (FBBD-12)"

/** The predicted lap time shown on the fix before each line, against the lap time. */
private fun predictedError(logged: List<LapLine>): QualityMetric {
    val label = "Predicted lap time at the line"
    val errors = logged.filter { it.kind == LapKind.Timed.name }
        .mapNotNull { lap -> lap.predicted?.let { abs(it - lap.millis) } }
    val worst = errors.maxOrNull()
    return when {
        logged.isEmpty() -> notMeasured(
            "timing.predicted", label, PredictedCriterion,
            "the predicted lap time at the line is logged from this version on",
        )
        worst == null -> notMeasured(
            "timing.predicted", label, PredictedCriterion, "no lap had a best lap before it to predict from",
        )
        else -> judged(
            "timing.predicted", label, worst.toDouble(), "ms", worst <= MaxPredictedErrorMillis, Basis.MEASURED,
            PredictedCriterion,
            "worst difference between the predicted lap time shown just before the line and the lap time, " +
                "${errors.size} lap(s)",
        )
    }
}
