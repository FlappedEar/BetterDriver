@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.optimal.OptimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.OptimalKind
import com.flappedear.betterdriver.analysis.optimal.OptimalVideo
import com.flappedear.betterdriver.analysis.optimal.optimalOptionText
import com.flappedear.betterdriver.analysis.optimal.optimalVideo
import com.flappedear.betterdriver.analysis.recorded.measuredG
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
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
 * The composite laps (FBBD-163, FBBD-178; iOS FBBD-370) are offered as on Android, chosen by the
 * shared [optimalVideo].
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
        val measured = measuredG(data)
        val channels = data.channels.values
        // One lap's video shows that lap only (FBBD-346): its overlay data is made when the lap is picked.
        val hotlap = { lap: Int -> OverlayData(sets, laps, measured, hotlap = lap, channels = channels) }
        // The composite laps need the lap analysis, named by the track's corners (FBBD-172) as Android's
        // export names them; made when first asked for. A session the analysis cannot handle only
        // leaves them out.
        val analysis = {
            runCatching {
                laps?.track?.let { analyzeSession(telemetrySessionOf(data, it), identity = it.identity) }
            }.getOrNull()
        }
        val origin = sets.firstOrNull()?.tMicros ?: 0L
        return DataVideoSession(
            id, OverlayData(sets, laps, measured, channels = channels), hotlap, laps, clips, dir, analysis, origin,
        )
    }

    private companion object {
        const val ClipIndexFile = "clips.json"
    }
}

/**
 * One choice of the picker: the whole video ([part] 0), a composite lap ([composite]: part
 * [DataVideoSession.RealisticOptimal] or [DataVideoSession.RawTheoretical]) or a lap; [note] under
 * its [name].
 */
class DataVideoPart(
    val part: Int,
    val name: String,
    val note: String,
    val enabled: Boolean,
    val best: Boolean,
    val composite: Boolean = false,
)

/** One piece of the video: [fromMicros] to [toMicros] of the file at [path], from [outputStartMicros] of the result. */
class DataVideoCut(
    val path: String,
    val fromMicros: Long,
    val toMicros: Long,
    val outputStartMicros: Long,
    val sessionStartMicros: Long,
)

/** A session's video and data, read once; see [DataVideo]. */
@Suppress("LongParameterList") // Made by DataVideo.open only: what it read of the session.
class DataVideoSession internal constructor(
    val sessionId: String,
    private val data: OverlayData,
    private val hotlapData: (Int) -> OverlayData,
    private val laps: SessionLaps?,
    private val clips: List<VideoClip>,
    private val dir: String,
    private val analysis: () -> SessionAnalysis? = { null },
    private val originMicros: Long = 0L,
) {
    private val lock = NSLock()
    private val hotlaps = HashMap<Int, OverlayData>()

    /** The session's lap analysis, made once: the composite laps are cut from it. */
    private val analysed: SessionAnalysis? by lazy { analysis() }

    /** The composite laps on the video, for the file lengths they were planned with. */
    private var compositeCache: Pair<Map<String, Long>, OptimalVideo>? = null

    /** The composite laps (FBBD-163, FBBD-178) on this session's video, as Android's export finds them. */
    private fun composites(durations: Map<String, Long>): OptimalVideo = lock.withLock {
        compositeCache?.takeIf { it.first == durations }?.second
            ?: runCatching { optimalVideo(analysed, clips, originMicros) { durations[path(it)] } }
                .getOrDefault(OptimalVideo.None)
                .also { compositeCache = durations to it }
    }

    /** The plan the last composite lap was cut with, for its overlay. */
    private val cutPlans = HashMap<Int, OptimalClipPlan>()

    /** The laps are runs of a point-to-point track (FBBD-155). */
    val pointToPoint: Boolean = data.pointToPoint

    /** The track's shape for the overlay's map, at most a few hundred points. */
    val outline: List<Xy> = thin(data.outline)

    /** The full paths of the video files; the app reads their lengths, which [parts] and [cuts] take. */
    val paths: List<String> = clips.map { path(it) }

    /**
     * The picker's lines, as Android's: the whole session, then each lap with its time ("Lap 7 ·
     * 1:42.37" over "Best lap", "Out lap", "No video"…), with the realistic optimal and the raw
     * theoretical best after the whole session (save only, as on Android). [durations]: microseconds
     * by path. Analyses the session the first time: not on the main thread.
     */
    fun parts(durations: Map<String, Long>): List<DataVideoPart> {
        val whole = cuts(WholeVideo, durations, 0).sumOf { it.toMicros - it.fromMicros }
        val best = laps?.best?.lap?.number
        val word = if (pointToPoint) "Run" else "Lap"
        val lapParts = laps?.laps.orEmpty().map { t ->
            val n = t.lap.number
            val onVideo = cuts(n, durations, 0).isNotEmpty()
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
        val films = composites(durations)
        val compositeParts = listOf(films.realistic, films.raw).map { film ->
            val option = film.option
            val (name, note) = optimalOptionText(option)
            DataVideoPart(partOf(film.kind), name, note, option.enabled, best = false, composite = true)
        }
        return listOf(DataVideoPart(WholeVideo, "Whole session", wholeVideoText(whole), whole > 0, false)) +
            compositeParts + lapParts
    }

    /**
     * The pieces that show [part] (0: all of the video), back to back; a lap from [handleSeconds] before
     * its start to as long after its end, as far as there is video (FBBD-346, as FlappedEar Overlays).
     */
    fun cuts(part: Int, durations: Map<String, Long>, handleSeconds: Int): List<DataVideoCut> =
        if (kindOf(part) != null) compositeCuts(part, durations) else recordedCuts(part, durations, handleSeconds)

    /** A composite lap: its parts' pieces, back to back, as Android's export cuts it (no handle). */
    private fun compositeCuts(part: Int, durations: Map<String, Long>): List<DataVideoCut> {
        val plan = kindOf(part)?.let { composites(durations).of(it).plan } ?: return emptyList()
        lock.withLock { cutPlans[part] = plan }
        return plan.cuts.map {
            val c = it.cut
            DataVideoCut(path(c.clip), c.fromMicros, c.toMicros, c.outputStartMicros, c.sessionStartMicros)
        }
    }

    private fun recordedCuts(part: Int, durations: Map<String, Long>, handleSeconds: Int): List<DataVideoCut> {
        val handle = handleSeconds * MicrosPerSecondLong
        val range = if (part == WholeVideo) {
            Long.MIN_VALUE to Long.MAX_VALUE
        } else {
            laps?.laps?.firstOrNull { it.lap.number == part }?.lap
                ?.let { it.startMicros - handle to it.endMicros + handle }
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

    /**
     * What the Tech HUD shows of [part] at session time [sessionMicros]: a lap shows that lap only, 0:00
     * before its line and its final time after it (FBBD-346). Any thread: the player draws frames on its own.
     */
    fun moment(sessionMicros: Long, part: Int): HudMoment {
        lock.lock()
        try {
            // A composite lap shows the whole session's data; its lap tile is compositeMoment's.
            val source = if (part <= WholeVideo) data else hotlaps.getOrPut(part) { hotlapData(part) }
            val radar = (RadarDots downTo 1).mapNotNull { i ->
                source.gAt(sessionMicros - RadarMicros * i / RadarDots)
                    ?.let { HudRadarDot(it, 1 - i.toFloat() / (RadarDots + 1)) }
            }
            val trail = runs(source.trail(sessionMicros))
            return HudMoment(source.at(sessionMicros), trail, radar, bestLapMicros, pointToPoint)
        } finally {
            lock.unlock()
        }
    }

    /**
     * What the Tech HUD shows at session time [sessionMicros] of piece [cut] of composite lap [part]
     * (as [cuts] gave it): the session's data, with the lap tile showing the lap the piece comes from
     * over the theoretical lap's running time and its gain on the fastest lap, as Android's export.
     * Another part, or a composite lap not yet cut, is [moment].
     */
    fun compositeMoment(sessionMicros: Long, part: Int, cut: Int): HudMoment {
        val plan = lock.withLock { cutPlans[part] }
        val piece = plan?.cuts?.getOrNull(cut) ?: return moment(sessionMicros, part)
        val base = moment(sessionMicros, WholeVideo)
        return base.asComposite(plan.overlayAt(piece, sessionMicros, runs = pointToPoint))
    }

    /** The name the saved file is offered with, as Android's: BetterDriver-<id>-video.mp4, -lap7.mp4, -run2.mp4. */
    fun fileName(part: Int): String {
        val name = when {
            part == WholeVideo -> "video"
            part == RealisticOptimal -> "realistic-optimal"
            part == RawTheoretical -> "raw-theoretical"
            pointToPoint -> "run$part"
            else -> "lap$part"
        }
        return "BetterDriver-$sessionId-$name.mp4"
    }

    private fun path(clip: VideoClip) = "$dir/${clip.file}"

    companion object {
        /** The part that is all of the session's video; lap numbers start at 1. */
        const val WholeVideo = 0

        /** The parts that are the composite laps, filmed (FBBD-163, FBBD-178), as Android numbers them. */
        const val RealisticOptimal = -1
        const val RawTheoretical = -2

        /** The composite lap [part] films; null for the whole video or a lap. */
        fun kindOf(part: Int): OptimalKind? = when (part) {
            RealisticOptimal -> OptimalKind.RealisticOptimal
            RawTheoretical -> OptimalKind.RawTheoreticalBest
            else -> null
        }

        /** [part] is a composite lap: saved only, no handle (as on Android). */
        fun isComposite(part: Int): Boolean = kindOf(part) != null

        internal fun partOf(kind: OptimalKind): Int =
            if (kind == OptimalKind.RealisticOptimal) RealisticOptimal else RawTheoretical

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

private inline fun <T> NSLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
