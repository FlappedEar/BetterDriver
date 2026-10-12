package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoEventText
import kotlin.math.abs

// How well each camera's video stays in sync with the session (FBBD-183), against FBBD-21 and
// FBBD-82: the start sync error of each file, and the drift 10, 20 and 30 min into the session.
//
// The phone camera reports once a second how much media its file holds and, from FBBD-21 on, when
// its newest frame was captured (sensor timestamp on the session clock): frame time − media time is
// the session time of the file's media time 0, as the camera's own frames say. An imported camera
// (GoPro) has stretches of its file aligned on their own against the session's GNSS speed
// ([EventKinds.VIDEO_PROBE]): each stretch's offset is the same thing, as its GPS says.

private const val WindowMicros = 60_000_000L
private const val MicrosPerMinute = 60_000_000L
private const val MaxStartErrorMicros = 33_000L

/** Session minutes at which the drift is checked, with its limit (FBBD-21). */
private val Checkpoints = listOf(10 to 50_000L, 20 to 100_000L, 30 to 100_000L)

/**
 * Where one file's media time 0 is on the session clock, as measured at session time [tMicros]. The
 * phone's reports can only be late (a frame is encoded after it is captured), so their lowest is the
 * best; GPS stretches are not, so their middle one is.
 */
internal class OffsetSample(val file: String, val tMicros: Long, val offsetMicros: Long)

/**
 * [samples] of one camera. [independent] when they come from the frames or GPS themselves, not from
 * the reports the video's placement was worked out from: only then do they show a start error.
 */
private class OffsetTrack(
    val samples: List<OffsetSample>,
    val lowest: Boolean,
    val independent: Boolean,
    val how: String,
)

/**
 * The offsets measured for [camera]'s files: from frame times (phone), else from the times of its
 * sync reports, or from GPS stretches (imported); null when there are none.
 */
private fun track(camera: String, events: List<RecorderEvent>): OffsetTrack? {
    val reports = events.ofKind(EventKinds.VIDEO_SYNC).mapNotNull { e ->
        val f = VideoEventText.parse(e.text)
        val media = f[VideoEventText.MEDIA_US]?.toLongOrNull()
        val file = f[VideoEventText.FILE]
        if (f[VideoEventText.CAMERA] == camera && media != null && file != null) {
            Triple(OffsetSample(file, e.tMicros, e.tMicros - media), f[VideoEventText.FRAME_US]?.toLongOrNull(), media)
        } else {
            null
        }
    }
    val frames = reports.mapNotNull { (s, frame, media) -> frame?.let { OffsetSample(s.file, s.tMicros, it - media) } }
    if (frames.isNotEmpty()) {
        return OffsetTrack(frames, lowest = true, independent = true, "the camera's frame times (sensor timestamps) less its media time")
    }
    if (reports.isNotEmpty()) {
        return OffsetTrack(reports.map { it.first }, lowest = true, independent = false, "the camera's sync reports (report time less media time)")
    }
    val probes = events.ofKind(EventKinds.VIDEO_PROBE).mapNotNull { e ->
        val f = fields(e.text)
        val offset = f["offset_us"]?.toLongOrNull()
        val file = f["file"]
        if (f["camera"] == camera && offset != null && file != null) OffsetSample(file, e.tMicros, offset) else null
    }
    return probes.takeIf { it.isNotEmpty() }?.let {
        OffsetTrack(it, lowest = false, independent = true, "stretches of the file aligned on their own against the session's GNSS speed")
    }
}

private fun OffsetTrack.at(samples: List<OffsetSample>): Long? {
    if (samples.isEmpty()) return null
    val sorted = samples.map { it.offsetMicros }.sorted()
    return if (lowest) sorted.first() else sorted[sorted.size / 2]
}

private const val StartCriterion = "start sync error <= 1 frame (33 ms at 30 fps), never over 50 ms (FBBD-21, FBBD-82)"

/**
 * The start sync error of each file: the offset the video is placed with ([own], from the camera's
 * start and sync reports or its alignment) against the one its first minute of frames or GPS says.
 * The worst file counts.
 */
internal fun startSyncError(camera: String, own: List<VideoClip>, events: List<RecorderEvent>): QualityMetric {
    val id = "video.$camera.sync_error"
    val label = "Start sync error ($camera)"
    val track = track(camera, events)?.takeIf { it.independent } ?: return notMeasured(
        id, label, StartCriterion,
        "needs the phone camera's frame times (logged from this version on) or an imported file aligned in stretches",
    )
    val errors = own.mapNotNull { clip ->
        val samples = track.samples.filter { it.file == clip.file }
        val first = samples.minOfOrNull { it.tMicros } ?: return@mapNotNull null
        track.at(samples.filter { it.tMicros <= first + WindowMicros })?.let { clip.startMicros - it }
    }
    val worst = errors.maxByOrNull { abs(it) }
        ?: return notMeasured(id, label, StartCriterion, "no file with frame times or aligned stretches")
    return judged(
        id, label, millis(worst), "ms", abs(worst) <= MaxStartErrorMicros, Basis.DERIVED, StartCriterion,
        "offset the video is placed with, less the one ${track.how} give over its first minute; " +
            "worst of ${errors.size} file(s)",
    )
}

/**
 * The drift [minutes] into the session: within the file that covers that time, the offset measured
 * around it (one minute either side) less the one measured over the file's first minute.
 */
internal fun driftAt(
    camera: String,
    own: List<VideoClip>,
    events: List<RecorderEvent>,
    endMicros: Long,
): List<QualityMetric> {
    val track = track(camera, events)
    return Checkpoints.map { (minutes, limit) ->
        val id = "video.$camera.drift_$minutes"
        val label = "Drift at $minutes min ($camera)"
        val criterion = "<= ${limit / MicrosPerMilli} ms at $minutes min (FBBD-21, FBBD-82)"
        val at = minutes * MicrosPerMinute
        val clip = own.lastOrNull { it.startMicros <= at && (it.endMicros ?: endMicros) >= at }
        val samples = track?.samples?.filter { it.file == clip?.file }.orEmpty()
        val first = samples.minOfOrNull { it.tMicros }
        val base = first?.let { f -> track?.at(samples.filter { it.tMicros <= f + WindowMicros }) }
        val now = track?.at(samples.filter { abs(it.tMicros - at) <= WindowMicros })
        when {
            endMicros < at -> notMeasured(id, label, criterion, "the session is shorter than $minutes min")
            track == null -> notMeasured(id, label, criterion, "no frame times or aligned stretches for this camera")
            clip == null -> notMeasured(id, label, criterion, "no $camera video at $minutes min")
            base == null || now == null -> notMeasured(id, label, criterion, "no measurement near $minutes min")
            else -> {
                val drift = now - base
                judged(
                    id, label, millis(drift), "ms", abs(drift) <= limit, Basis.DERIVED, criterion,
                    "${clip.file}: offset ${track.how} give around $minutes min, less over the file's first minute",
                )
            }
        }
    }
}

/**
 * The phone's clock against the GNSS receiver's at 10, 20 and 30 min: how the smallest (session time −
 * receiver time) changed since the session's first minute. Both video and data are placed on the
 * phone's clock, so this is information: it is what a camera aligned by GPS time would drift by.
 */
internal fun clockDrift(lags: List<Pair<Long, Long>>, endMicros: Long): List<QualityMetric> {
    if (lags.isEmpty()) return emptyList()
    val first = lags.minOf { it.first }
    val base = lags.filter { it.first <= first + WindowMicros }.minOf { it.second }
    return Checkpoints.mapNotNull { (minutes, _) ->
        val at = minutes * MicrosPerMinute
        val near = lags.filter { abs(it.first - at) <= WindowMicros }.minOfOrNull { it.second }
        if (endMicros < at || near == null) {
            null
        } else {
            info(
                "gnss.clock_$minutes", "Phone clock vs GNSS at $minutes min", millis(near - base), "ms", Basis.MEASURED,
                "smallest (arrival on the phone − receiver fix time) around $minutes min, less over the first minute: " +
                    "the phone clock's drift plus any change of the Bluetooth delay",
            )
        }
    }
}
