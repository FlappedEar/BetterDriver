@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.recorded.measuredG
import com.flappedear.betterdriver.analysis.review.indexedClips
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.format.formatDelta
import com.flappedear.betterdriver.format.formatLapTime
import com.flappedear.betterdriver.format.formatRunningTime
import com.flappedear.betterdriver.format.isDeltaGain
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.Box
import com.flappedear.betterdriver.timing.overlay.GForce
import com.flappedear.betterdriver.timing.overlay.MapFit
import com.flappedear.betterdriver.timing.overlay.OverlayLayout
import com.flappedear.betterdriver.timing.overlay.Px
import com.flappedear.betterdriver.timing.overlay.gDot
import com.flappedear.betterdriver.timing.overlay.OverlayFrame
import com.flappedear.betterdriver.timing.overlay.OverlayLap
import com.flappedear.betterdriver.timing.overlay.VideoCut
import com.flappedear.betterdriver.timing.overlay.thin
import com.flappedear.betterdriver.timing.overlay.videoCuts
import com.flappedear.betterdriver.timing.replay.SessionLaps
import com.flappedear.betterdriver.timing.replay.replayLaps
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Video with data" on the iPhone and iPad (FBBD-152, FBBD-333), as Android's VideoExports: a session's
 * video with the data overlay drawn on it, to watch on the phone and to save as an MP4. Works without
 * laps too (a session away from a saved track): then the overlay shows speed, G and the map.
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
        return DataVideoSession(id, OverlayData(sets, laps, measuredG(data)), laps, clips, dir)
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

/** How a value of the overlay is coloured, as Android's OverlayPainter colours it. */
enum class OverlayInk { Text, Dim, Gain, Loss, Best }

/**
 * What the overlay's band says at one moment, as Android's OverlayPainter writes it: speed, lap (or
 * run) and lap time, delta to the best lap; [g] and [position] for the G meter and the map. A slot
 * whose [deltaValue] is null stays empty (no laps).
 */
@Suppress("LongParameterList") // One value per slot of the band, as the painter draws them.
class OverlayText(
    val speed: String,
    val lapCaption: String,
    val lapCaptionInk: OverlayInk,
    val lapValue: String,
    val lapValueInk: OverlayInk,
    val deltaValue: String?,
    val deltaInk: OverlayInk,
    val g: GForce?,
    val position: Xy?,
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

    /** The overlay at session time [sessionMicros]. Any thread: the player draws frames on its own. */
    fun text(sessionMicros: Long): OverlayText {
        lock.lock()
        val f = try {
            data.at(sessionMicros)
        } finally {
            lock.unlock()
        }
        return overlayText(f, pointToPoint)
    }

    /** Where the band goes on a picture [videoWidth] by [videoHeight] pixels, with the map of this session. */
    fun band(videoWidth: Int, videoHeight: Int): OverlayBand =
        OverlayBand(OverlayLayout.of(videoWidth, videoHeight), outline)

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
    }
}

/**
 * The band along the bottom of the picture, in its own pixels ([width] by [height], y down), as
 * Android's OverlayPainter lays it out: the slots, text sizes and line width of [OverlayLayout], the
 * track map's line ([track]) and where the car and the G dot go.
 */
class OverlayBand internal constructor(layout: OverlayLayout, outline: List<Xy>) {
    val width = layout.width
    val height = layout.height
    val speed: Box = layout.speed
    val lap: Box = layout.lap
    val delta: Box = layout.delta
    val gMeter: Box = layout.gMeter
    val numberSize = layout.numberSize
    val labelSize = layout.labelSize
    val stroke = layout.stroke
    private val fit = MapFit(outline, layout.map, layout.stroke * MapInsetStrokes)
    val track: List<Px> = outline.map(fit::project)

    fun car(position: Xy): Px = fit.project(position)

    fun dot(g: GForce): Px = gDot(g, gMeter)

    private companion object {
        /** As Android's painter: the map stays this many line widths inside its box. */
        const val MapInsetStrokes = 4f
    }
}

/** Android's OverlayPainter texts and colours for frame [f]. */
internal fun overlayText(f: OverlayFrame, pointToPoint: Boolean): OverlayText {
    val lap = f.lap
    val seconds = lap?.deltaMicros?.let { it / MicrosPerSecond }
    return OverlayText(
        speed = f.speedKmh?.roundToInt()?.toString() ?: Dash,
        lapCaption = lapCaption(lap, pointToPoint),
        lapCaptionInk = if (lap?.isBest == true) OverlayInk.Best else OverlayInk.Dim,
        lapValue = lap?.let { formatRunningTime(it.lapMicros / MicrosPerMilli) } ?: Dash,
        lapValueInk = if (lap == null) OverlayInk.Dim else OverlayInk.Text,
        // Without laps (no saved track) the slot stays empty; on out and in laps it shows a dash.
        deltaValue = if (lap == null) null else seconds?.let(::formatDelta) ?: Dash,
        deltaInk = deltaInk(seconds),
        g = f.g,
        position = f.position,
    )
}

/** "LAP 7 · BEST", "LAP 1 · OUT", "RUN 2"; "LAP" alone without a lap. */
private fun lapCaption(lap: OverlayLap?, pointToPoint: Boolean): String {
    val word = if (pointToPoint) "RUN" else "LAP"
    val kind = when {
        lap == null -> return word
        lap.isBest -> " · BEST"
        lap.kind == LapKind.Out -> " · OUT"
        lap.kind == LapKind.In -> " · IN"
        else -> ""
    }
    return "$word ${lap.number}$kind"
}

/** The delta's colour: green ahead, red behind, white level, dim without one. */
private fun deltaInk(seconds: Double?): OverlayInk = when {
    seconds == null -> OverlayInk.Dim
    isDeltaGain(seconds) -> OverlayInk.Gain
    abs(seconds) < HalfHundredth -> OverlayInk.Text
    else -> OverlayInk.Loss
}

/** "18 min of video", "45 s of video", as Android's picker. */
internal fun wholeVideoText(micros: Long): String {
    val seconds = micros / MicrosPerSecondLong
    val minutes = (seconds + SecondsPerMinute / 2) / SecondsPerMinute
    return if (seconds >= SecondsPerMinute) "$minutes min of video" else "$seconds s of video"
}

private const val Dash = "—"
private const val MicrosPerSecond = 1_000_000.0
private const val MicrosPerSecondLong = 1_000_000L
private const val MicrosPerMilli = 1_000L
private const val SecondsPerMinute = 60L
private const val HalfHundredth = 0.005
