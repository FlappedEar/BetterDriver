package com.flappedear.betterdriver.videoexport

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.core.ble.RaceBoxChannels
import com.flappedear.betterdriver.feature.review.SessionVideo
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.review.indexedClips
import com.flappedear.betterdriver.review.sessionVideo
import com.flappedear.betterdriver.sessionanalysis.trackFor
import com.flappedear.betterdriver.sharing.TransferState
import com.flappedear.betterdriver.timing.overlay.AccelChannels
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.VideoCut
import com.flappedear.betterdriver.timing.overlay.totalMicros
import com.flappedear.betterdriver.timing.overlay.videoCuts
import com.flappedear.betterdriver.timing.replay.SessionLaps
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.video.mp4.Mp4Reader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Video with data" (FBBD-152): a lap, or all of a session's video, rendered on the phone with the
 * data overlay ([DataOverlay]) and saved where the user picked, as RaceChrono and Catalyst do.
 *
 * One export at a time, in the UI process: the session is read and the cuts planned on a background
 * thread; Media3 Transformer then decodes, draws the overlay on every frame and encodes on a thread
 * of its own, while [VideoExportService] keeps the app running in the background. The result goes to
 * a file in the cache first, then is copied to the picked document.
 *
 * Never while a session records: the encoder and the CPU belong to the recording. [start] refuses
 * then, and [stopForRecording] stops an export when a recording starts.
 */
@OptIn(UnstableApi::class)
class VideoExports private constructor(private val context: Context) {
    // Made on the io thread when first used: making it reads the disk (FBBD-41).
    private val store by lazy { SessionStore(sessionsDir(context)) }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "video-export-io") }
    private val main = Handler(Looper.getMainLooper())

    /** Progress or result of the last video export of each session, by id; shown on its card. */
    val states = mutableStateMapOf<String, TransferState>()

    /** The session being exported, if any. Main thread. */
    var running by mutableStateOf<String?>(null)
        private set

    /** Main thread. */
    private var job: Job? = null

    /**
     * What the picker offers for session [id], read off the main thread; null when it has no video
     * or cannot be read. Whatever stops the reading, the picker gets an answer: an exception left on
     * the io thread would end the app.
     */
    @Suppress("TooGenericExceptionCaught")
    fun options(id: String, onReady: (VideoExportOptions?) -> Unit) = io.execute {
        val options = try {
            prepare(id)?.options()
        } catch (e: Exception) {
            DebugLog.w(Tag, "reading $id for video export failed", e)
            null
        }
        main.post { onReady(options) }
    }

    /**
     * Renders [part] of session [id] into [uri]. Refused while a session records ([recording], or a
     * session the store still holds open) or while another export runs.
     */
    fun start(id: String, part: VideoPart, uri: Uri, recording: Boolean) {
        if (running != null) return
        if (recording) {
            states[id] = endState(VideoExportEnd.Recording)
            discard(uri)
            return
        }
        DebugLog.i(Tag, "video export of $id, part $part")
        keepAccess(uri)
        running = id
        states[id] = renderingState(null)
        val j = Job(id, part, uri)
        job = j
        VideoExportService.start(context)
        io.execute(j::plan)
    }

    /** Stops the export of [id], if it runs; its file is removed. */
    fun cancel(id: String) {
        job?.takeIf { it.id == id }?.stop(VideoExportEnd.Cancelled)
    }

    /** A recording is starting: the export stops at once (FBBD-69: nothing competes with a recording). */
    fun stopForRecording() {
        job?.stop(VideoExportEnd.Recording)
    }

    /** Android is ending [VideoExportService] (its daily time for media processing is used up): the export stops. */
    fun stopForTimeout() {
        job?.stop(VideoExportEnd.Failed)
    }

    /** The session's data, laps and video, as the export needs them; null when it has no video. */
    private fun prepare(id: String): Prepared? {
        val data = store.read(id)
        val dir = store.dir(id)
        val video = sessionVideo(dir, VideoTimeline.from(data.events, indexedClips(dir))) ?: return null
        val sets = data.sampleSets.sortedBy { it.tMicros }
        val laps = trackFor(data, TrackStore(tracksDir(context)).all())?.let { replayLaps(id, sets, it) }
        return Prepared(data, sets, laps, video)
    }

    private class Prepared(
        val data: SessionData,
        val sets: List<SampleSet>,
        val laps: SessionLaps?,
        val video: SessionVideo,
    ) {
        private val durations = HashMap<String, Long?>()

        /** The optimal lap and its video (FBBD-163), analysed when first asked for. */
        val optimal: OptimalVideo by lazy { analyseOptimal() }

        // A session the analysis cannot handle only leaves the optimal lap out.
        @Suppress("TooGenericExceptionCaught")
        private fun analyseOptimal(): OptimalVideo = try {
            // Named by the track's corners (FBBD-172), as the lap analysis names them.
            val analysis = laps?.track?.let { analyzeSession(telemetrySessionOf(data, it), identity = it.identity) }
            optimalVideo(analysis, video, sets.firstOrNull()?.tMicros ?: 0L, ::durationOf)
        } catch (e: Exception) {
            DebugLog.w(Tag, "analysing ${data.id} for its optimal lap failed", e)
            OptimalVideo(null, null)
        }

        /**
         * The file's own length, read from its index; exact, unlike the logged end. A malformed index
         * (the parser throws unchecked exceptions on bad nested boxes) only leaves that clip out.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun durationOf(clip: VideoClip): Long? = durations.getOrPut(clip.file) {
            try {
                Mp4Reader.read(video.file(clip)).durationUs.takeIf { it > 0 }
            } catch (e: IOException) {
                DebugLog.w(Tag, "unreadable video ${clip.file}", e)
                null
            } catch (e: RuntimeException) {
                DebugLog.w(Tag, "malformed video ${clip.file}", e)
                null
            }
        }

        fun cuts(part: VideoPart): List<VideoCut> =
            if (part == OptimalLap) optimal.plan?.cuts?.map { it.cut }.orEmpty() else recordedCuts(part)

        private fun recordedCuts(part: VideoPart): List<VideoCut> {
            val range = if (part == WholeVideo) {
                Long.MIN_VALUE to Long.MAX_VALUE
            } else {
                laps?.laps?.firstOrNull { it.lap.number == part }?.lap?.let { it.startMicros to it.endMicros }
            } ?: return emptyList()
            return videoCuts(video.clips, ::durationOf, range.first, range.second)
        }

        fun options(): VideoExportOptions {
            val best = laps?.best?.lap?.number
            val lapOptions = laps?.laps.orEmpty().map { t ->
                val n = t.lap.number
                LapOption(n, t.lap.kind, t.lap.timeMillis, best = n == best, onVideo = cuts(n).isNotEmpty())
            }
            val pointToPoint = laps?.track?.pointToPoint == true
            return VideoExportOptions(cuts(WholeVideo).totalMicros(), lapOptions, pointToPoint, optimal.option)
        }
    }

    /** One export, from planning to the saved file. */
    private inner class Job(val id: String, val part: VideoPart, val uri: Uri) {
        private val done = AtomicBoolean()
        private val thread = HandlerThread("video-export").apply { start() }
        private val handler = Handler(thread.looper)
        private val temp = File(File(context.cacheDir, TempDir), "$id.mp4")

        /** Render thread only. */
        private var transformer: Transformer? = null

        /** io thread: reads the session and plans the cuts, then hands over to the render thread. */
        @Suppress("TooGenericExceptionCaught") // Whatever stops the plan, the export ends with a reason.
        fun plan() {
            try {
                val prepared = prepare(id)
                val cuts = prepared?.cuts(part).orEmpty()
                when {
                    store.list().any { it.state == SessionState.RECORDING } -> finish(VideoExportEnd.Recording)
                    prepared == null || cuts.isEmpty() -> finish(VideoExportEnd.NoVideo)
                    else -> {
                        val composition = composition(prepared, cuts)
                        handler.post { render(composition) }
                    }
                }
            } catch (e: Exception) {
                DebugLog.w(Tag, "planning the video export of $id failed", e)
                finish(VideoExportEnd.Failed)
            }
        }

        private fun composition(prepared: Prepared, cuts: List<VideoCut>): Composition {
            val data = OverlayData(prepared.sets, prepared.laps, accelOf(prepared.data))
            val painter = OverlayPainter(OverlayFonts.load(context), data.outline, data.pointToPoint)
            // The optimal lap's cuts are its plan's, in the same order (Prepared.cuts).
            val optimal = if (part == OptimalLap) prepared.optimal.plan else null
            val items = cuts.mapIndexed { i, cut ->
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(cut.fromMicros)
                    .setEndPositionUs(cut.toMicros)
                    .build()
                val media = MediaItem.Builder()
                    .setUri(Uri.fromFile(prepared.video.file(cut.clip)))
                    .setClippingConfiguration(clipping)
                    .build()
                val drawn = optimal?.let { OptimalOverlay(data, it, it.cuts[i], painter) }
                    ?: DataOverlay(data, cut, painter)
                val overlay = OverlayEffect(listOf(drawn))
                EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), listOf(overlay))).build()
            }
            // Silence where a clip has no sound, so clips with and without it can follow each other.
            val sequence = EditedMediaItemSequence.Builder(items).experimentalSetForceAudioTrack(true).build()
            DebugLog.i(Tag, "video export of $id: ${cuts.size} cuts, ${cuts.totalMicros() / MicrosPerSecond} s")
            return Composition.Builder(sequence).build()
        }

        /** Render thread. */
        private fun render(composition: Composition) {
            if (done.get()) return
            temp.parentFile?.mkdirs()
            temp.delete()
            val t = Transformer.Builder(context)
                .setLooper(thread.looper)
                .addListener(listener)
                .build()
            transformer = t
            t.start(composition, temp.absolutePath)
            handler.postDelayed(poll, PollMs)
        }

        private val poll = object : Runnable {
            private val holder = ProgressHolder()

            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    show(holder.progress / PercentFloat * RenderShare)
                }
                handler.postDelayed(this, PollMs)
            }
        }

        private val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                transformer = null
                handler.removeCallbacks(poll)
                show(RenderShare)
                io.execute(::save)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                transformer = null
                DebugLog.w(Tag, "rendering the video of $id failed", exportException)
                finish(VideoExportEnd.Failed)
            }
        }

        /**
         * io thread: copies the rendered file into the picked document. Access to it can be gone by
         * now (a provider that does not let it be kept, see [keepAccess]): then the export failed.
         */
        private fun save() {
            val end = try {
                val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
                out.use { o -> temp.inputStream().use { copyUntilStopped(it, o) } }
                VideoExportEnd.Saved
            } catch (e: IOException) {
                DebugLog.w(Tag, "saving the video of $id failed", e)
                VideoExportEnd.Failed
            } catch (e: SecurityException) {
                DebugLog.w(Tag, "no access to the picked file for the video of $id", e)
                VideoExportEnd.Failed
            }
            finish(end)
        }

        /** Copies [input] to [output]; stops early when the export is stopped (its document is then removed). */
        private fun copyUntilStopped(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(CopyBuffer)
            var n = input.read(buffer)
            while (n >= 0 && !done.get()) {
                output.write(buffer, 0, n)
                n = input.read(buffer)
            }
        }

        /** Any thread: stops rendering on its own thread, then ends the export with [end]. */
        fun stop(end: VideoExportEnd) {
            handler.post {
                transformer?.cancel()
                transformer = null
                finish(end)
            }
        }

        private fun show(fraction: Float) = main.post {
            if (!done.get()) states[id] = renderingState(fraction)
        }

        /** Any thread, once: cleans up and shows how it ended. Only a saved export keeps its document. */
        private fun finish(end: VideoExportEnd) {
            if (!done.compareAndSet(false, true)) return
            DebugLog.i(Tag, "video export of $id: $end")
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            // In this order on the one io thread, after a copy that may still be running.
            io.execute { temp.delete() }
            if (end != VideoExportEnd.Saved) discard(uri)
            io.execute { releaseAccess(uri) }
            main.post {
                states[id] = endState(end)
                if (job === this) {
                    job = null
                    running = null
                    VideoExportService.stop(context)
                }
            }
        }
    }

    /**
     * Keeps write access to the picked document for the whole export. The picker grants it only for
     * as long as the activity lives, and a render takes minutes: once the user leaves with Back or
     * swipes the app away, the copy into the document (and the clean-up of a failed one) would be
     * refused. Best effort: a provider that does not offer lasting access leaves the plain grant.
     */
    private fun keepAccess(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            DebugLog.w(Tag, "the picked file cannot be kept open for the export", e)
        }
    }

    /** Gives back the lasting access [keepAccess] took, so the app's few lasting grants are not used up. */
    private fun releaseAccess(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            DebugLog.w(Tag, "no lasting access to give back", e)
        }
    }

    /** Removes a document that will not get a video. Best effort: the export has already ended. */
    @Suppress("TooGenericExceptionCaught")
    private fun discard(uri: Uri) = io.execute {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            DebugLog.w(Tag, "could not remove the unfinished video", e)
        }
    }

    companion object {
        private const val Tag = "VideoExports"
        private const val TempDir = "video-export"
        private const val PollMs = 500L
        private const val CopyBuffer = 1 shl 20
        private const val MicrosPerSecond = 1_000_000L
        private const val PercentFloat = 100f

        /** Rendering is most of the work; copying into the picked file is the rest. */
        private const val RenderShare = 0.95f

        @Volatile
        private var instance: VideoExports? = null

        fun get(context: Context): VideoExports = instance ?: synchronized(this) {
            instance ?: VideoExports(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * The RaceBox accelerometer when the session recorded it: X forward, Y to the right (RaceBox
 * protocol); levelled by the app's calibration (FBBD-89). Other receivers' axes are not known, so
 * their G comes from the GNSS path.
 */
private fun accelOf(data: SessionData): AccelChannels? {
    val x = RaceBoxChannels.ACCEL_X
    val y = RaceBoxChannels.ACCEL_Y
    val recorded = data.channels[x.id]?.name == x.name && data.channels[y.id]?.name == y.name
    return if (recorded) AccelChannels(forward = x.id, right = y.id) else null
}
