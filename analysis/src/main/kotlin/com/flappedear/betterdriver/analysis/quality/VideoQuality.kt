package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoEventText
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import kotlin.math.abs
import kotlin.math.roundToLong

// The video part of the session validation report (FBBD-183), against FBBD-21: the start offset
// each camera's video is placed with and how it was found, its drift against the session clock,
// and how much of the session it covers.

private const val MaxDriftMicros = 100_000L
private const val MinCoveragePercent = 99.0

/** The drift is the change of the lowest media offset from a file's first minute to its last. */
private const val DriftWindowMicros = 60_000_000L

/** The drift limit is per this much video: 30 min. */
private const val DriftPerMicros = 1_800_000_000L

/** Video stops that are no fault: the driver stopped, or a file reached its size limit and the next began. */
private val PlainStops = setOf("user", "file_full")

/**
 * The video section, one set of values per camera. [from] and [to] are the first and last GNSS
 * sample: the part of the session video should cover. [frames] are the files whose frames were counted;
 * [handover] is the phone's file handover check from the log ([fileHandovers]).
 */
@Suppress("LongParameterList") // Each is a part of the session the video is checked against.
internal fun videoSection(
    events: List<RecorderEvent>,
    clips: List<QualityClip>,
    from: Long,
    to: Long,
    frames: List<FileFrames> = emptyList(),
    handover: QualityMetric? = null,
): QualitySection {
    val endMicros = to
    val indexed = clips.map { VideoClip(it.camera, it.file, it.startMicros, it.endMicros) }
    val timeline = VideoTimeline.from(events, indexed)
    if (timeline.clips.isEmpty()) {
        val why = "no video recorded with this session"
        return QualitySection("Video", listOf(notMeasured("video", "Video", null, why)))
    }
    val byFile = clips.associateBy { it.camera to it.file }
    val metrics = timeline.cameras.flatMap { camera ->
        val own = timeline.clips(camera)
        listOf(
            offset(camera, own.first(), byFile[camera to own.first().file]),
            startSyncError(camera, own, events),
            drift(camera, own, events),
        ) + driftAt(camera, own, events, endMicros) + coverage(camera, own, from, to) +
            listOfNotNull(frameRate(camera, events, frames.filter { it.camera == camera })) +
            listOfNotNull(handover.takeIf { camera == CameraIds.PHONE })
    }
    val notes = events.ofKind(EventKinds.VIDEO_STOP).mapNotNull { e ->
        val f = VideoEventText.parse(e.text)
        val reason = f[VideoEventText.REASON] ?: return@mapNotNull null
        if (reason in PlainStops) null else QualityNote(e.tMicros, "${f[VideoEventText.CAMERA]} video stopped: $reason")
    }
    return QualitySection("Video", metrics, notes)
}

/** Where the camera's first file starts on the session clock, and how that was found. */
private fun offset(camera: String, first: VideoClip, indexed: QualityClip?): QualityMetric {
    val how = if (indexed != null) {
        "found by ${indexed.method.lowercase().replace('_', ' ')}, " +
            "stated uncertainty ±${ms(indexed.uncertaintyMicros)} ms (an estimate, not a measured error)"
    } else {
        "from the camera's start and sync reports: the earliest media start they imply"
    }
    return info("video.$camera.offset", "Start offset ($camera)", millis(first.startMicros), "ms", Basis.DERIVED, how)
}

private const val DriftCriterion = "<= 100 ms over 30 min (FBBD-21)"

/**
 * How far each file's media clock moved against the session clock: the lowest media offset its sync
 * reports imply in its first minute against that in its last (a late report only raises an offset,
 * so the lowest is the best), per 30 min for a longer file. The worst file counts.
 */
private fun drift(camera: String, own: List<VideoClip>, events: List<RecorderEvent>): QualityMetric {
    val label = "Video drift ($camera)"
    val files = own.map { it.file }.toSet()
    val offsets = events.ofKind(EventKinds.VIDEO_SYNC).mapNotNull { e ->
        val f = VideoEventText.parse(e.text)
        val media = f[VideoEventText.MEDIA_US]?.toLongOrNull()
        val file = f[VideoEventText.FILE]
        val ours = f[VideoEventText.CAMERA] == camera && file in files
        if (ours && media != null) Triple(file, e.tMicros, e.tMicros - media) else null
    }.groupBy({ it.first }, { it.second to it.third })
    val drifts = offsets.values.mapNotNull(::fileDrift)
    if (drifts.isEmpty()) {
        val why = "no file with sync reports over two minutes or more"
        return notMeasured("video.$camera.drift", label, DriftCriterion, why)
    }
    val worst = drifts.maxBy { abs(it) }
    val how = "change of the lowest media offset from the first to the last minute of each file, from the " +
        "camera's once-a-second sync reports, per 30 min of a longer file; worst of ${drifts.size} file(s)"
    val ok = abs(worst) <= MaxDriftMicros
    return judged("video.$camera.drift", label, millis(worst), "ms", ok, Basis.DERIVED, DriftCriterion, how)
}

/**
 * The drift of one file from its (session time, media offset) reports; null under two minutes. A file
 * longer than 30 min gives its drift per 30 min of the time between the two reports used; a shorter
 * one its drift as it is, so a few milliseconds' jitter over a short file is not scaled up past the limit.
 */
private fun fileDrift(reports: List<Pair<Long, Long>>): Long? {
    val first = reports.minOf { it.first }
    val last = reports.maxOf { it.first }
    if (last - first < 2 * DriftWindowMicros) return null
    val start = reports.filter { it.first <= first + DriftWindowMicros }.minBy { it.second }
    val end = reports.filter { it.first >= last - DriftWindowMicros }.minBy { it.second }
    val drift = end.second - start.second
    // Per 30 min of the time between the two reports the offsets came from, not the whole file.
    return if (last - first < DriftPerMicros) {
        drift
    } else {
        (drift * (DriftPerMicros.toDouble() / (end.first - start.first))).roundToLong()
    }
}

private const val CoverageCriterion = "100 % of the session (FBBD-21)"

/**
 * The share of [from] to [to] some file of the camera covers. A file with no known end runs to the
 * next one's start.
 */
private fun coverage(camera: String, own: List<VideoClip>, from: Long, to: Long): QualityMetric {
    val label = "Video coverage ($camera)"
    if (to <= from) return notMeasured("video.$camera.coverage", label, CoverageCriterion, "no GNSS samples to cover")
    // A file with no stop logged runs to the next one, or the last one to the session's end: the
    // camera stops with the recording, and an older build logged no stop for that last file.
    val spans = own
        .mapIndexed { i, c -> c.startMicros to (c.endMicros ?: own.getOrNull(i + 1)?.startMicros ?: to) }
        .map { (a, b) -> a.coerceIn(from, to) to b.coerceIn(from, to) }
        .filter { (a, b) -> b > a }
        .sortedBy { it.first }
    var covered = 0L
    var reach = from
    for ((a, b) in spans) {
        if (b > reach) {
            covered += b - maxOf(a, reach)
            reach = b
        }
    }
    val percent = covered * Percent / (to - from)
    val how = "share of the time from the first to the last GNSS sample with a $camera file; " +
        "stops other than the driver's are listed with their reason"
    val ok = percent >= MinCoveragePercent
    return judged("video.$camera.coverage", label, percent, "%", ok, Basis.MEASURED, CoverageCriterion, how)
}
