package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.log.LogLine
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoEventText
import kotlin.math.roundToLong

// The frame rate phone video was recorded at (FBBD-246): Arek's 2026-10-19 2.7K·60 replay kept
// 48.4 fps, and no check said so.

/**
 * How many [frames] a camera's [file] holds over [mediaMicros] of media, and the rate it was set to
 * record at ([setFps]) when the counter knows it (FBBD-246): counted in the MP4 by the app, or logged
 * by the 2.7K recorder when it closes a file.
 */
data class FileFrames(
    val camera: String,
    val file: String,
    val frames: Long,
    val mediaMicros: Long,
    val setFps: Int? = null,
)

/** A file may record down to this share of the rate it was set to: 57 fps at 60 (FBBD-246). */
private const val MinFrameRateShare = 0.95

private const val FrameRateCriterion = ">= 95 % of the set frame rate, 57 fps at 60 (FBBD-239, FBBD-246)"

/**
 * The frame rate the camera's files hold: frames over media time, the worst file against the rate it
 * was set to (its counter's, else its video_start event's). Null for a camera with neither a counted
 * file nor a set rate, such as a GoPro, whose files the camera makes itself.
 */
internal fun frameRate(camera: String, events: List<RecorderEvent>, counted: List<FileFrames>): QualityMetric? {
    val set = events.ofKind(EventKinds.VIDEO_START).mapNotNull { e ->
        val f = VideoEventText.parse(e.text)
        val fps = f[VideoEventText.FPS]?.toIntOrNull()
        val file = f[VideoEventText.FILE]
        if (f[VideoEventText.CAMERA] == camera && file != null && fps != null) file to fps else null
    }.toMap()
    val id = "video.$camera.frame_rate"
    val label = "Video frame rate ($camera)"
    val rated = counted.mapNotNull { c ->
        val target = c.setFps ?: set[c.file]
        val fps = recordedFps(c.frames, c.mediaMicros)
        if (target == null || target <= 0 || fps == null) null else Triple(c.file, fps, target)
    }
    val worst = rated.minByOrNull { (_, fps, target) -> fps / target }
    return when {
        counted.isEmpty() && set.isEmpty() -> null
        worst == null -> {
            val why = if (counted.isEmpty()) "no file's frames counted" else "no counted file with a set frame rate"
            notMeasured(id, label, FrameRateCriterion, why)
        }
        else -> {
            val (file, fps, target) = worst
            // A file a counter missed is named, not passed over: the verdict covers the counted ones.
            val missed = (set.keys - rated.map { it.first }.toSet()).sorted()
            val how = "frames in each file over its media time; " +
                "worst of ${rated.size} file(s): $file, set to $target fps" +
                if (missed.isEmpty()) "" else "; not counted: ${missed.joinToString(", ")}"
            judged(id, label, fps, "fps", fps >= target * MinFrameRateShare, Basis.MEASURED, FrameRateCriterion, how)
        }
    }
}

/** [frames] over [mediaMicros] in frames per second; null without two frames and some media time. */
internal fun recordedFps(frames: Long, mediaMicros: Long): Double? =
    if (frames < 2 || mediaMicros <= 0) null else frames * MicrosPerSecond.toDouble() / mediaMicros

/** "phone_0001.mp4: 2688x1512@60 21603 frames in 446.5 s = 48.4 fps 2045178095 bytes", from WideRecorder. */
private val WideFileLine = Regex("""^(\S+): \d+x\d+@(\d+) (\d+) frames in ([\d.]+) s\b""")
private const val WideRecorderTag = "WideRecorder"
private const val WideFile = 1
private const val WideFps = 2
private const val WideFrames = 3
private const val WideSeconds = 4

/** The phone files whose frames the 2.7K recorder counted as it closed them (FBBD-246). */
internal fun loggedFrames(lines: List<LogLine>): List<FileFrames> = lines.filter { it.tag == WideRecorderTag }
    .mapNotNull { line ->
        val g = WideFileLine.find(line.message)?.groupValues ?: return@mapNotNull null
        val count = g[WideFrames].toLongOrNull()
        // The line's time runs from the first frame to the last: N frames span N-1 frame times, so
        // the file's media time is that span stretched by one frame, as an MP4's duration counts it.
        val micros = g[WideSeconds].toDoubleOrNull()?.takeIf { count != null && count > 1 }
            ?.let { (it * MicrosPerSecond * count!! / (count - 1)).roundToLong() }
        if (micros == null || count == null) {
            null
        } else {
            FileFrames(CameraIds.PHONE, g[WideFile], count, micros, g[WideFps].toIntOrNull())
        }
    }
