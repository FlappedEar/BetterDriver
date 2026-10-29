@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.recorded.measuredG
import com.flappedear.betterdriver.analysis.review.indexedClips
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.format.formatLapTime
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.TechHudLayout
import com.flappedear.betterdriver.timing.overlay.hudWidgets
import com.flappedear.betterdriver.timing.overlay.VideoCut
import com.flappedear.betterdriver.timing.overlay.thin
import com.flappedear.betterdriver.timing.overlay.videoCuts
import com.flappedear.betterdriver.timing.replay.SessionLaps
import com.flappedear.betterdriver.timing.replay.replayLaps
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * "Video with data" on the iPhone and iPad (FBBD-152, FBBD-333), as Android's VideoExports: a session's
 * video with the data overlay drawn on it, to watch on the phone and to save as an MP4. Works without
 * laps too (a session away from a saved track): then the overlay shows speed, G and the map. The
 * overlay is FlappedEar Overlays' Tech HUD (FBBD-347), as on Android: [HudGeometry] and [HudMoment].
 */
class DataVideo(private val sessionsRoot: String, private val tracksRoot: String) {
    private val store = SessionStore(sessionsRoot)

    /** Session [id] with its video and data; null when it has no complete video file. */
    @Throws(Exception::class)
    fun open(id: String): DataVideoSession? {
        val dir = "${store.dir(id)}/${VideoFiles.DIR}"
        val data = store.read(id)
        val index = NSString.stringWithContentsOfFile("$dir/$ClipIndexFile", NSUTF8StringEncoding, null)
            ?.let(::indexedClips).orEmpty()
        val present = VideoTimeline.from(data.events, index).clips.filter { Mp4Files.isComplete("$dir/${it.file}") }
        // The phone's video when there is any, else the first camera with files, as the VBO export picks.
        val camera = present.firstOrNull { it.camera == CameraIds.PHONE }?.camera ?: present.firstOrNull()?.camera
        val clips = present.filter { it.camera == camera }
        if (clips.isEmpty()) return null
        val sets = data.sampleSets.sortedBy { it.tMicros }
        // A damaged track only leaves the laps out, as on Android.
        val laps = runCatching {
            trackFor(data, TrackLibrary(tracksRoot, sessionsRoot).list())?.let { replayLaps(id, sets, it) }
        }.getOrNull()
        val overlay = OverlayData(sets, laps, measuredG(data), channels = data.channels.values)
        return DataVideoSession(id, overlay, laps, clips, dir)
    }

    private companion object {
        const val ClipIndexFile = "clips.json"
    }
}

/** One choice of the picker: the whole video ([part] 0) or a lap; [note] under its [name]. */
class DataVideoPart(val part: Int, val name: String, val note: String, val enabled: Boolean, val best: Boolean)

/** One piece of the video: [fromMicros] to [toMicros] of the file at [path], from [outputStartMicros] of the result. */
class DataVideoCut(
    val path: String,
    val fromMicros: Long,
    val toMicros: Long,
    val outputStartMicros: Long,
    val sessionStartMicros: Long,
)

/** A session's video and data, read once; see [DataVideo]. */
class DataVideoSession internal constructor(
    val sessionId: String,
    private val data: OverlayData,
    private val laps: SessionLaps?,
    private val clips: List<VideoClip>,
    private val dir: String,
) {
    private val lock = NSLock()

    /** The laps are runs of a point-to-point track (FBBD-155). */
    val pointToPoint: Boolean = data.pointToPoint

    /** The track's shape for the overlay's map, at most a few hundred points. */
    val outline: List<Xy> = thin(data.outline)

    /** The full paths of the video files; the app reads their lengths, which [parts] and [cuts] take. */
    val paths: List<String> = clips.map { path(it) }

    /**
     * The picker's lines, as Android's: the whole session, then each lap with its time ("Lap 7 ·
     * 1:42.37" over "Best lap", "Out lap", "No video"…). [durations]: microseconds by path.
     */
    fun parts(durations: Map<String, Long>): List<DataVideoPart> {
        val whole = cuts(WholeVideo, durations).sumOf { it.toMicros - it.fromMicros }
        val best = laps?.best?.lap?.number
        val word = if (pointToPoint) "Run" else "Lap"
        val lapParts = laps?.laps.orEmpty().map { t ->
            val n = t.lap.number
            val onVideo = cuts(n, durations).isNotEmpty()
            val note = listOfNotNull(
                when {
                    n == best -> "Best ${word.lowercase()}"
                    t.lap.kind == LapKind.Out -> "Out lap"
                    t.lap.kind == LapKind.In -> "In lap"
                    else -> null
                },
                if (onVideo) null else "No video",
            ).joinToString(" · ")
            DataVideoPart(n, "$word $n · ${formatLapTime(t.lap.timeMillis)}", note, onVideo, n == best)
        }
        return listOf(DataVideoPart(WholeVideo, "Whole session", wholeVideoText(whole), whole > 0, false)) + lapParts
    }

    /** The pieces that show [part] (0: all of the video), back to back. */
    fun cuts(part: Int, durations: Map<String, Long>): List<DataVideoCut> {
        val range = if (part == WholeVideo) {
            Long.MIN_VALUE to Long.MAX_VALUE
        } else {
            laps?.laps?.firstOrNull { it.lap.number == part }?.lap?.let { it.startMicros to it.endMicros }
        } ?: return emptyList()
        val cuts: List<VideoCut> = videoCuts(clips, { durations[path(it)] }, range.first, range.second)
        return cuts.map {
            DataVideoCut(path(it.clip), it.fromMicros, it.toMicros, it.outputStartMicros, it.sessionStartMicros)
        }
    }

    /** The start/finish line's ends, in the frame of [outline]; null without a saved track's line. */
    val startLineFrom: Xy? = data.startLine?.first
    val startLineTo: Xy? = data.startLine?.second

    private val widgets = hudWidgets(data)
    private val bestLapMicros = data.bestLapMicros

    /** Where the Tech HUD's widgets go on a picture [videoWidth] by [videoHeight] pixels. */
    fun hud(videoWidth: Int, videoHeight: Int): HudGeometry =
        HudGeometry(TechHudLayout(videoWidth, videoHeight, widgets))

    /** What the Tech HUD shows at session time [sessionMicros]. Any thread: the player draws frames on its own. */
    fun moment(sessionMicros: Long): HudMoment {
        lock.lock()
        try {
            val radar = (RadarDots downTo 1).mapNotNull { i ->
                data.gAt(sessionMicros - RadarMicros * i / RadarDots)
                    ?.let { HudRadarDot(it, 1 - i.toFloat() / (RadarDots + 1)) }
            }
            val trail = runs(data.trail(sessionMicros))
            return HudMoment(data.at(sessionMicros), trail, radar, bestLapMicros, pointToPoint)
        } finally {
            lock.unlock()
        }
    }

    /** The name the saved file is offered with, as Android's: BetterDriver-<id>-video.mp4, -lap7.mp4, -run2.mp4. */
    fun fileName(part: Int): String {
        val name = when {
            part == WholeVideo -> "video"
            pointToPoint -> "run$part"
            else -> "lap$part"
        }
        return "BetterDriver-$sessionId-$name.mp4"
    }

    private fun path(clip: VideoClip) = "$dir/${clip.file}"

    companion object {
        /** The part that is all of the session's video; lap numbers start at 1. */
        const val WholeVideo = 0

        /** The radar's trail, as Overlays' TechRadar: 14 dots over the last second. */
        private const val RadarDots = 14
        private const val RadarMicros = 1_000_000L
    }
}

/** "18 min of video", "45 s of video", as Android's picker. */
internal fun wholeVideoText(micros: Long): String {
    val seconds = micros / MicrosPerSecondLong
    val minutes = (seconds + SecondsPerMinute / 2) / SecondsPerMinute
    return if (seconds >= SecondsPerMinute) "$minutes min of video" else "$seconds s of video"
}

private const val MicrosPerSecondLong = 1_000_000L
private const val SecondsPerMinute = 60L
