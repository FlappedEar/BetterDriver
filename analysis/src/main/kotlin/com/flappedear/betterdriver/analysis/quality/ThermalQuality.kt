package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.video.VideoEventText

// The thermal part of the session validation report (FBBD-183). Arek, 2026-10-19: "as long as we
// won't trigger 0x06, we don't care about temperature and I don't ever want to hear about it". So
// the report has no thermal rows at all unless Android reported THERMAL_STATUS_SHUTDOWN (0x06); then
// that shutdown fails, and so does a phone video file the camera did not end before it.

/** Android's PowerManager.THERMAL_STATUS_SHUTDOWN. */
private const val Shutdown = 6

private const val ShutdownCriterion = "no Android thermal shutdown (status 0x06) (FBBD-46)"
private const val FinishedCriterion = "every phone video file ended at a thermal shutdown (FBBD-46)"

/**
 * The thermal section, or null when Android never reported a shutdown: the phone's thermal status
 * comes from the camera while it records and from the recorder's health reports every 30 s.
 */
internal fun thermalSection(events: List<RecorderEvent>): QualitySection? {
    val fromCamera = events.ofKind(EventKinds.THERMAL_STATUS).mapNotNull { e ->
        fields(e.text)["status"]?.toIntOrNull()?.let { e.tMicros to it }
    }
    val fromRecorder = events.ofKind(EventKinds.DEVICE).mapNotNull { e ->
        fields(e.text)["thermal"]?.toIntOrNull()?.let { e.tMicros to it }
    }
    val at = (fromCamera + fromRecorder).filter { it.second >= Shutdown }.minOfOrNull { it.first } ?: return null
    val open = phoneVideoEnds(events).count { it == Long.MAX_VALUE }
    val metrics = listOf(
        judged(
            "thermal.shutdown", "Thermal shutdown", seconds(at), "s", false, Basis.MEASURED, ShutdownCriterion,
            "Android reported thermal shutdown at this session time",
        ),
        judged(
            "thermal.shutdown_video", "Video files left unfinished", open.toDouble(), "", open == 0, Basis.MEASURED,
            FinishedCriterion, "phone video files with no logged end",
        ),
    )
    return QualitySection("Thermal shutdown", metrics, listOf(QualityNote(at, "Android thermal shutdown")))
}

/** When each phone video file stopped; Long.MAX_VALUE for a file still recording when the session ended. */
private fun phoneVideoEnds(events: List<RecorderEvent>): List<Long> {
    val stops = events.ofKind(EventKinds.VIDEO_STOP).mapNotNull { e ->
        VideoEventText.parse(e.text).takeIf { it[VideoEventText.CAMERA] == "phone" }?.get(VideoEventText.FILE)
            ?.let { it to e.tMicros }
    }.toMap()
    return events.ofKind(EventKinds.VIDEO_START).mapNotNull { e ->
        VideoEventText.parse(e.text).takeIf { it[VideoEventText.CAMERA] == "phone" }?.get(VideoEventText.FILE)
            ?.let { stops[it] ?: Long.MAX_VALUE }
    }
}
