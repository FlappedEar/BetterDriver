package com.flappedear.betterdriver.videoexport

import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.sharing.TransferState
import com.flappedear.betterdriver.timing.live.LapKind
import java.util.Locale

/** What "Video with data" exports (FBBD-152): [WholeVideo], or the lap with that number. */
typealias VideoPart = Int

/** The part that is all of the session's video. Lap numbers start at 1. */
const val WholeVideo: VideoPart = 0

/** One lap in the picker: its time, and whether it has video to export. */
data class LapOption(
    val number: Int,
    val kind: LapKind,
    val timeMillis: Long,
    val best: Boolean,
    val onVideo: Boolean,
)

/**
 * What the picker offers for one session: the whole video ([wholeMicros] long) and each lap; on a
 * point-to-point track ([pointToPoint], FBBD-155) the laps are runs.
 */
data class VideoExportOptions(val wholeMicros: Long, val laps: List<LapOption>, val pointToPoint: Boolean = false) {
    /** Chosen when the picker opens: the best lap when it is on video, else the whole video; null with no video. */
    val preselected: VideoPart?
        get() = laps.firstOrNull { it.best && it.onVideo }?.number ?: WholeVideo.takeIf { wholeMicros > 0 }
}

/**
 * The picker's line for a lap: "Lap 7 · 1:42.37" over "Best lap", "Out lap", "No video"…; for a
 * run of a point-to-point track, "Run 2 · 0:58.10" over "Best run".
 */
internal fun lapOptionText(o: LapOption, pointToPoint: Boolean = false): Pair<String, String> {
    val word = if (pointToPoint) "Run" else "Lap"
    val note = listOfNotNull(
        when {
            o.best -> "Best ${word.lowercase()}"
            o.kind == LapKind.Out -> "Out lap"
            o.kind == LapKind.In -> "In lap"
            else -> null
        },
        if (o.onVideo) null else "No video",
    ).joinToString(" · ")
    return "$word ${o.number} · ${formatLapTime(o.timeMillis)}" to note
}

/** "18 min of video", "45 s of video". */
internal fun wholeVideoText(micros: Long): String {
    val seconds = micros / MicrosPerSecond
    val minutes = (seconds + SecondsPerMinute / 2) / SecondsPerMinute
    return if (seconds >= SecondsPerMinute) "$minutes min of video" else "$seconds s of video"
}

/**
 * The file name offered when saving: BetterDriver-<id>-lap7.mp4 (-run2.mp4 for a point-to-point
 * run), or -video.mp4 for all of it.
 */
fun videoExportFileName(sessionId: String, part: VideoPart, pointToPoint: Boolean = false): String {
    val name = when {
        part == WholeVideo -> "video"
        pointToPoint -> "run$part"
        else -> "lap$part"
    }
    return "BetterDriver-$sessionId-$name.mp4"
}

/** The card's line while rendering: "Rendering video · 42%", with the bar. */
internal fun renderingState(fraction: Float?): TransferState {
    val percent = fraction?.let { String.format(Locale.ROOT, " · %d%%", (it * Percent).toInt()) }
    return TransferState("Rendering video" + (percent ?: "…"), fraction, running = true)
}

/** How a video export ended, for the card. */
internal enum class VideoExportEnd(val text: String, val failed: Boolean) {
    Saved("Video saved", false),
    Cancelled("Video export cancelled", false),
    Recording("Video export stopped: a session is recording", true),
    NoVideo("No video for this lap", true),
    Failed("Video export failed", true),
}

internal fun endState(end: VideoExportEnd) = TransferState(end.text, failed = end.failed)

private const val MicrosPerSecond = 1_000_000L
private const val SecondsPerMinute = 60L
private const val Percent = 100
