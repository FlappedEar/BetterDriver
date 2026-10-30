package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.log.LogLine
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoEventText
import kotlin.math.roundToLong

// The frame rate phone video was recorded at (FBBD-246): Arek's 2026-10-19 2.7K·60 replay kept
// 48.4 fps, and no check said so. Arek dropped 2.7K on 2026-10-20 ("Dobra, porzućmy to 2.7k",
// FBBD-246 Won't do): a 2.7K file's rate is reported, not judged.

/**
 * How many [frames] a camera's [file] holds over [mediaMicros] of media, and the rate it was set to
 * record at ([setFps]) when the counter knows it (FBBD-246): counted in the MP4 by the app, or logged
 * by the own recorder when it closes a file. [wide] marks a file larger than 1080p (2.7K).
 */
data class FileFrames(
    val camera: String,
    val file: String,
    val frames: Long,
    val mediaMicros: Long,
    val setFps: Int? = null,
    val wide: Boolean = false,
)

/** Whether a [width]×[height] video, either way up, is between 1080p and 4K: 2.7K, which is not judged. */
fun isWide(width: Int, height: Int): Boolean = maxOf(width, height) in (FullHdLong + 1) until UhdLong

/** A file may record down to this share of the rate it was set to: 57 fps at 60 (FBBD-246). */
private const val MinFrameRateShare = 0.95

private const val FrameRateCriterion = ">= 95 % of the set frame rate, 57 fps at 60 (FBBD-239, FBBD-246)"

/** The long side of 1080p: anything larger is 2.7K, whose rate is not judged (FBBD-246 Won't do). */
private const val FullHdLong = 1920

/** The long side of 4K, which is judged like 1080p. */
private const val UhdLong = 3840

/**
 * The frame rate the camera's files hold: frames over media time, the worst file against the rate it
 * was set to (its counter's, else its video_start event's). Null for a camera with neither a counted
 * file nor a set rate, such as a GoPro, whose files the camera makes itself. 2.7K files are left
 * out of the verdict; with only those, their worst rate is reported for information.
 */
internal fun frameRate(camera: String, events: List<RecorderEvent>, counted: List<FileFrames>): QualityMetric? {
    val set = setRates(camera, events)
    val id = "video.$camera.frame_rate"
    val label = "Video frame rate ($camera)"
    val all = rated(counted, set)
    val wide = counted.filter { it.wide }.map { it.file }.toSet()
    val (wideRated, rated) = all.partition { it.file in wide }
    val worst = rated.minByOrNull { it.share }
    val worstWide = wideRated.minByOrNull { it.share }
    // A file without a rate (not counted, or no set rate) is named, not passed over: the verdict
    // covers the rated ones. It may be a 1080p file after a 2.7K one, so with one, 2.7K files alone
    // are not reported.
    val missed = ((set.keys + counted.map { it.file }) - all.map { it.file }.toSet()).sorted()
    val notCounted = missed.takeIf { it.isNotEmpty() }?.let { "not rated: ${it.joinToString(", ")}" }
    val notJudged = wideRated.takeIf { it.isNotEmpty() }?.let { "${it.size} 2.7K file(s) not judged" }
    return when {
        counted.isEmpty() && set.isEmpty() -> null
        worst == null && worstWide != null && notCounted == null -> {
            val how = "2.7K only, not judged (2.7K dropped, FBBD-246 Won't do); " +
                "worst of ${wideRated.size} file(s): ${worstWide.file}, set to ${worstWide.target} fps"
            info(id, label, worstWide.fps, "fps", Basis.MEASURED, how)
        }
        worst == null -> {
            val why = when {
                counted.isEmpty() -> "no file's frames counted"
                notJudged != null -> "$notCounted; $notJudged"
                else -> "no counted file with a set frame rate"
            }
            notMeasured(id, label, FrameRateCriterion, why)
        }
        else -> {
            val how = (listOf("frames in each file over its media time; worst of ${rated.size} file(s): " +
                "${worst.file}, set to ${worst.target} fps") + listOfNotNull(notCounted, notJudged)).joinToString("; ")
            val ok = worst.fps >= worst.target * MinFrameRateShare
            judged(id, label, worst.fps, "fps", ok, Basis.MEASURED, FrameRateCriterion, how)
        }
    }
}

/** The [counted] files with a recorded rate and a rate they were set to: their counter's, else [set]'s. */
private fun rated(counted: List<FileFrames>, set: Map<String, Int>): List<Rated> = counted.mapNotNull { c ->
    val target = c.setFps ?: set[c.file]
    val fps = recordedFps(c.frames, c.mediaMicros)
    if (target == null || target <= 0 || fps == null) null else Rated(c.file, fps, target)
}

/** A counted [file]'s recorded [fps] and the rate it was set to ([target]). */
private class Rated(val file: String, val fps: Double, val target: Int) {
    val share: Double get() = fps / target
}

/** The rate each of [camera]'s files was set to record at, from its video_start event. */
private fun setRates(camera: String, events: List<RecorderEvent>): Map<String, Int> =
    events.ofKind(EventKinds.VIDEO_START).mapNotNull { e ->
        val f = VideoEventText.parse(e.text)
        val fps = f[VideoEventText.FPS]?.toIntOrNull()
        val file = f[VideoEventText.FILE]
        if (f[VideoEventText.CAMERA] == camera && file != null && fps != null) file to fps else null
    }.toMap()

/** [frames] over [mediaMicros] in frames per second; null without two frames and some media time. */
internal fun recordedFps(frames: Long, mediaMicros: Long): Double? =
    if (frames < 2 || mediaMicros <= 0) null else frames * MicrosPerSecond.toDouble() / mediaMicros

/** "phone_0001.mp4: 2688x1512@60 21603 frames in 446.5 s = 48.4 fps 2045178095 bytes", from WideRecorder. */
private val WideFileLine = Regex("""^(\S+): (\d+)x(\d+)@(\d+) (\d+) frames in ([\d.]+) s\b""")
private const val WideRecorderTag = "WideRecorder"
private const val WideFile = 1
private const val WideWidth = 2
private const val WideHeight = 3
private const val WideFps = 4
private const val WideFrames = 5
private const val WideSeconds = 6

/**
 * The phone files whose frames the own recorder counted as it closed them (FBBD-246): 2.7K until
 * 1.5, 1080p60 since 1.6.4 and 4K60 since 1.7.1 (FBBD-278). Only 2.7K is left out of the verdict.
 */
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
            val wide = isWide(g[WideWidth].toInt(), g[WideHeight].toInt())
            FileFrames(CameraIds.PHONE, g[WideFile], count, micros, g[WideFps].toIntOrNull(), wide)
        }
    }
