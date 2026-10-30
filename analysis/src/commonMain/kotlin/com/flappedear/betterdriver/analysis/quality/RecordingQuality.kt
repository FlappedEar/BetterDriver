package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState

// The recording part of the session validation report (FBBD-183), against FBBD-72: how the
// session ended, samples the recorder dropped, and the data lost when its process was restarted.

private const val MaxRestartLossMicros = 2_000_000L

internal fun recordingSection(data: SessionData, samples: GnssSamples, previous: PreviousSession?): QualitySection {
    val notes = mutableListOf<QualityNote>()
    val ended = if (data.state == SessionState.RECOVERED) {
        val at = data.end?.tMicros ?: samples.t.lastOrNull() ?: 0L
        notes += QualityNote(at, "cut off here; the session the restarted recorder carried on in measures the loss")
        "interrupted, then recovered: ${data.truncatedBytes} bytes cut off at a chunk end, " +
            "${data.skippedBytes} damaged bytes skipped"
    } else {
        "stopped: ${data.end?.reason ?: "no end record"}"
    }
    val restarts = data.events.ofKind(EventKinds.RECORDER_RESTART)
    val metrics = buildList {
        add(info("recording.end", "How it ended", null, "", Basis.NONE, ended))
        add(queueDrops(data.events))
        add(
            info(
                "recording.restarts", "Recorder restarts", restarts.size.toDouble(), "", Basis.MEASURED,
                "restarts after the recorder process was killed",
            ),
        )
        restarts.firstOrNull()?.let { add(restartLoss(fields(it.text)["previous"].orEmpty(), data, samples, previous)) }
    }
    return QualitySection("Recording", metrics, notes)
}

private fun queueDrops(events: List<RecorderEvent>): QualityMetric {
    val dropped = events.ofKind(EventKinds.DROPPED_SAMPLES).mapNotNull { it.text.toLongOrNull() }.maxOrNull() ?: 0L
    return judged(
        "recording.queue_drops", "Samples dropped by the recorder", dropped.toDouble(), "", dropped == 0L,
        Basis.MEASURED, "0 samples lost (FBBD-72)", "sample sets dropped because the recorder's write queue was full",
    )
}

private const val RestartId = "recording.restart_loss"
private const val RestartLabel = "Data lost at recorder restart"
private const val RestartCriterion = "<= 2 s of data lost when the recorder is killed (FBBD-72)"

/**
 * Data lost between the last sample of [previous] and the first of this session: on the receiver's
 * clock when both have fix times, else on the phone's.
 */
private fun restartLoss(
    previousId: String,
    data: SessionData,
    s: GnssSamples,
    previous: PreviousSession?,
): QualityMetric {
    val first = s.t.firstOrNull()
    val firstUtc = s.utc.firstOrNull { it != GnssSamples.NoUtc }
    val lastUtc = previous?.lastUtcMillis
    val lastSample = previous?.lastSampleMicros
    return when {
        previous == null || previous.id != previousId || first == null -> notMeasured(
            RestartId, RestartLabel, RestartCriterion, "the session it carried on from ($previousId) is not readable",
        )
        lastUtc != null && firstUtc != null -> restartJudged(
            (firstUtc - lastUtc) * MicrosPerMilli,
            Basis.MEASURED,
            "receiver fix time of the last sample of $previousId to the first of this session",
        )
        lastSample != null -> restartJudged(
            (data.info.startUtcMillis - previous.startUtcMillis) * MicrosPerMilli + first - lastSample,
            Basis.DERIVED,
            "phone clock: each session's start time plus its last or first sample; the receiver gave no fix times",
        )
        else -> notMeasured(RestartId, RestartLabel, RestartCriterion, "$previousId has no GNSS sample")
    }
}

private fun restartJudged(lostMicros: Long, basis: Basis, how: String) = judged(
    RestartId, RestartLabel, seconds(lostMicros), "s", lostMicros <= MaxRestartLossMicros, basis, RestartCriterion, how,
)
