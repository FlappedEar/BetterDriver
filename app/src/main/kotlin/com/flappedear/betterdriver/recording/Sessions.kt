package com.flappedear.betterdriver.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.analysis.recorded.vboAccelerations
import com.flappedear.betterdriver.coach.CoachReferences
import com.flappedear.betterdriver.coach.CoachedSessions
import com.flappedear.betterdriver.coach.coachDir
import com.flappedear.betterdriver.coach.sessionFocusStore
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.export.CsvExporter
import com.flappedear.betterdriver.recorder.export.ExportFormat
import com.flappedear.betterdriver.recorder.export.ExportShare
import com.flappedear.betterdriver.recorder.export.ExportLap
import com.flappedear.betterdriver.recorder.export.GpxExporter
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.DeleteResult
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import com.flappedear.betterdriver.recorder.vbo.VboVideo
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.gopro.GoProRecordings
import com.flappedear.betterdriver.review.recorderClip
import com.flappedear.betterdriver.shell.GoProClips
import com.flappedear.betterdriver.shell.SessionCamera
import com.flappedear.betterdriver.shell.SessionGoPro
import com.flappedear.betterdriver.sessionanalysis.trackFor
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.ClipStatus
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.mp4.Mp4Reader
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Export progress of one session, shown on its card. */
enum class ExportState { Running, Done, Failed }

/** A delete in progress or one that did not happen, shown on the session's card (FBBD-90). */
enum class DeleteState { Running, Recording, Failed }

/**
 * Saved sessions, for the UI process. Created once per process; creating it recovers interrupted
 * sessions (FBBD-71). Disk work runs on one background thread; results are Compose state.
 * Recovery and export only read chunk files, so they can run while the recorder process records.
 */
class Sessions private constructor(private val context: Context) {
    // Made on the io thread when first used: making them reads the disk (FBBD-41).
    private val store by lazy { SessionStore(sessionsDir(context)) }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "sessions-io") }
    private val main = Handler(Looper.getMainLooper())
    private val coached by lazy { CoachedSessions(store, File(coachDir(context), "cues.properties")) }
    private val quality by lazy { QualityReports(store, TrackStore(tracksDir(context))) }

    /** Newest first. */
    var list by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    /** By session id. */
    val exports = mutableStateMapOf<String, ExportState>()

    /** Ids of sessions with video files, which export as VBO plus video (FBBD-25). */
    var withVideo by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Bytes on disk of each session, video included, by id; shown when deleting (FBBD-90). */
    var sizes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** How many coach cues each session has, by id, for sessions with any (FBBD-99). */
    var cues by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** Sessions being deleted, and why a delete failed, by id (FBBD-90). */
    val deletes = mutableStateMapOf<String, DeleteState>()

    /** GoPro video of each session, by id (FBBD-22). */
    var goPro by mutableStateOf<Map<String, SessionGoPro>>(emptyMap())
        private set

    /**
     * A recovery pass is queued or running, so [list] may still miss an interrupted session. True
     * from creation until the first pass has published the list (FBBD-74).
     */
    var recovering by mutableStateOf(true)
        private set

    private val recoveries = AtomicInteger()

    init {
        recoverAndRefresh()
    }

    /**
     * Marks sessions that were not stopped cleanly and that no process is recording as recovered,
     * then reloads the list. A session being recorded is locked, so it is never touched.
     */
    @Suppress("TooGenericExceptionCaught") // A damaged session must not stop the others from listing.
    fun recoverAndRefresh() {
        recoveries.incrementAndGet()
        // Set at once on the main thread, so nothing can see the stale list as recovered.
        if (Looper.myLooper() == Looper.getMainLooper()) recovering = true else main.post { recovering = true }
        io.execute {
            try {
                store.recoverInterrupted()
            } catch (e: RuntimeException) {
                DebugLog.w(Tag, "recovery failed", e)
            }
            // A drive imported twice before FBBD-427 is listed once again; its extra imported copies go.
            try {
                store.removeCopies().forEach { DebugLog.i(Tag, "removed $it: a copy of a drive listed twice") }
            } catch (e: RuntimeException) {
                DebugLog.w(Tag, "removing copies failed", e)
            }
            try {
                publish()
            } finally {
                // Posted after publish's own post, so the list is current when this clears.
                if (recoveries.decrementAndGet() == 0) main.post { recovering = recoveries.get() > 0 }
            }
        }
    }

    fun refresh() = io.execute { publish() }

    /**
     * Deletes the session and everything in its folder, its video included (FBBD-90). The store
     * refuses a session that is recording; the UI also keeps Delete off while it exports.
     */
    fun delete(id: String) {
        if (exports[id] == ExportState.Running || deletes[id] == DeleteState.Running) return
        deletes[id] = DeleteState.Running
        io.execute {
            // A waiting export's video links would keep the deleted video on the phone.
            File(exportsDir(context), id).deleteRecursively()
            val result = try {
                store.delete(id).also { r ->
                    // Its next-session focus goes with it, so the coach never works from it (FBBD-20).
                    if (r == DeleteResult.DELETED || r == DeleteResult.NOT_FOUND) forgetFocus(context, id)
                }
            } catch (e: SecurityException) {
                DebugLog.w(Tag, "delete failed", e)
                DeleteResult.FAILED
            }
            DebugLog.i(Tag, "delete $id: $result")
            publish()
            main.post { deleted(id, result) }
        }
    }

    private fun deleted(id: String, result: DeleteResult) {
        when (result) {
            DeleteResult.DELETED, DeleteResult.NOT_FOUND -> {
                deletes.remove(id)
                exports.remove(id)
            }
            DeleteResult.RECORDING -> deletes[id] = DeleteState.Recording
            DeleteResult.FAILED -> deletes[id] = DeleteState.Failed
        }
    }

    /**
     * Writes the session as [format] into the app's cache, then hands it to [ready] for the share
     * sheet, as Share log does (FBBD-443): Quick Share, Drive, Gmail or another app in one step. CSV
     * and GPX get lap columns and one track segment per lap when the session was at a saved track
     * with a start/finish line (FBBD-150). VBO + video writes the VBO with an [avi] section and the
     * video files next to it as `BetterDriver-<id>_0001.MP4` and on, as Circuit Tools expects them
     * (FBBD-25); phone video is used when there is any, else the first camera that has files. The
     * video files are hard links to the session's own, so the export takes no second copy of them.
     * Exports run one at a time on the io thread.
     */
    fun export(id: String, format: ExportFormat) {
        exports[id] = ExportState.Running
        DebugLog.i(Tag, "export $id as $format")
        io.execute {
            val share = try {
                val data = store.read(id)
                // An earlier export of this session is replaced; another session's may still be shared.
                // Deleting a session removes its export, with the video links.
                val dir = File(exportsDir(context), id)
                dir.deleteRecursively()
                if (!dir.mkdirs()) throw IOException("cannot create $dir")
                val file = File(dir, ExportShare.fileName(id, format))
                val videos = mutableListOf<String>()
                file.outputStream().buffered().use { out ->
                    when (format) {
                        ExportFormat.Vbo ->
                            logExport(id, data) { VboExporter.export(data, out, derived = vboAccelerations(data)) }
                        ExportFormat.VboVideo -> {
                            val sessionDir = store.dir(id)
                            val timeline = VideoTimeline.from(data.events, indexedClips(sessionDir))
                            val video = exportVideo(id, sessionDir, timeline)
                            logExport(id, data) { VboExporter.export(data, out, video, vboAccelerations(data)) }
                            video?.orderedClips?.forEachIndexed { i, clip ->
                                val to = File(dir, video.fileName(i))
                                linkOrCopy(File(File(sessionDir, VideoFiles.DIR), clip.file), to)
                                videos += to.path
                            }
                        }
                        ExportFormat.Csv -> CsvExporter.export(data, out, exportLaps(data, tracksDir(context)))
                        ExportFormat.Gpx -> GpxExporter.export(data, out, exportLaps(data, tracksDir(context)))
                    }
                }
                ExportShare.of(id, format, file.path, videos).also {
                    DebugLog.i(Tag, it.logLine(it.files.sumOf { f -> File(f).length() }))
                }
            } catch (e: IOException) {
                DebugLog.w(Tag, "export failed", e)
                null
            } catch (e: IllegalStateException) {
                DebugLog.w(Tag, "export failed", e) // unreadable metadata
                null
            } catch (_: OutOfMemoryError) {
                // Left on this thread it ends the app (FBBD-232). No stack trace: formatting one needs memory.
                DebugLog.w(Tag, "export failed: out of memory")
                null
            }
            main.post {
                exports[id] = if (share != null) ExportState.Done else ExportState.Failed
                if (share != null) ready = share
            }
        }
    }

    /** A written export waiting for the share sheet; [shared] clears it once the sheet is open. */
    var ready by mutableStateOf<ExportShare?>(null)
        private set

    fun shared(share: ExportShare) {
        if (ready == share) ready = null
    }

    /** The session's clip index (clips.json) on the recorder's terms; clips still on a camera are left out. */
    private fun indexedClips(dir: File) = VideoIndex(dir).clips()
        .filter { it.status == ClipStatus.LOCAL }
        .map(::recorderClip)

    private fun exportVideo(id: String, dir: File, timeline: VideoTimeline): VboVideo? {
        val videoDir = File(dir, VideoFiles.DIR)
        // A file cut off by a crash has no index (moov) and would not play: it is left out.
        val present = timeline.clips.filter { File(videoDir, it.file).let { f -> f.isFile && Mp4Reader.isComplete(f) } }
        val camera = present.firstOrNull { it.camera == CameraIds.PHONE }?.camera ?: present.firstOrNull()?.camera
        val clips = present.filter { it.camera == camera }
        return if (clips.isEmpty()) null else VboVideo(ExportShare.videoPrefix(id), clips)
    }

    private fun publish() {
        val sessions = store.list()
        val video = sessions.filter { s ->
            File(s.dir, VideoFiles.DIR).listFiles()?.any(::isVideoFile) == true
        }.map { it.id }.toSet()
        val goPros = sessions.associate { it.id to goProOf(it.dir) }
        val bytes = sessions.associate { it.id to SessionFiles.sizeBytes(it.dir) }
        main.post {
            list = sessions
            sizes = bytes
            withVideo = video
            goPro = goPros
        }
        // After the list is shown: the first time, this reads each session once.
        val cueCounts = coached.counts(sessions)
        main.post { cues = cueCounts }
        // Sessions added, recovered or deleted: the coach references follow (FBBD-168).
        CoachReferences.refresh(context)
        // New and recovered sessions get their validation report (FBBD-183) now, not when first opened.
        sessions.take(ReportedAhead).forEach { quality.load(it.id) }
    }

    companion object {
        private const val Tag = "Sessions"

        /** The newest sessions whose validation report is made in the background; older ones on opening. */
        private const val ReportedAhead = 3

        @Volatile
        private var instance: Sessions? = null

        fun get(context: Context): Sessions = instance ?: synchronized(this) {
            instance ?: Sessions(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * What a session holds from GoPros, per camera: files an Open GoPro camera still holds, and
 * lined-up clips. Any number of cameras; older sessions name their one HERO11 or HERO8.
 */
private fun goProOf(dir: File): SessionGoPro {
    val clips = try {
        VideoIndex(dir).clips().filter { it.status == ClipStatus.LOCAL && it.kind.isGoPro }
    } catch (e: IOException) {
        DebugLog.w("Sessions", "unreadable clip index in $dir", e)
        emptyList()
    }
    val onCamera = GoProRecordings(File(dir, VideoFiles.DIR)).all().filter { !it.downloaded }.map { it.sourceId }
    val ids = (clips.map { it.sourceId } + onCamera).distinct()
    return SessionGoPro(
        ids.map { id ->
            val own = clips.filter { it.sourceId == id }
            val lined = own.takeIf { it.isNotEmpty() }?.let {
                val worst = it.maxBy { c -> c.alignment.uncertaintyUs }.alignment
                val byGps = worst.method == AlignmentMethod.GPS_MATCH || worst.method == AlignmentMethod.GPS_UTC
                GoProClips(it.size, alignmentText(worst), byGps)
            }
            SessionCamera(id, onCamera = id in onCamera, clips = lined)
        },
    )
}

/** How a clip was placed, for its card: "by GPS ±0.05 s". */
internal fun alignmentText(a: Alignment): String {
    val how = when (a.method) {
        AlignmentMethod.GPS_MATCH, AlignmentMethod.GPS_UTC -> "by GPS"
        AlignmentMethod.COMMAND_ACK -> "by start time"
        AlignmentMethod.CAMERA_CLOCK -> "by camera clock"
        AlignmentMethod.CAPTURE_CLOCK -> "by phone clock"
        AlignmentMethod.MANUAL -> "by hand"
    }
    val seconds = a.uncertaintyUs / MicrosPerSecond
    val format = if (seconds < 1) "%.2f s" else "%.0f s"
    return "$how ±" + String.format(Locale.ROOT, format, seconds)
}

private const val MicrosPerSecond = 1_000_000.0
private const val NanosPerMilli = 1_000_000L

/**
 * Runs a VBO export of session [id] and puts what it wrote and how long it took in the drive's log
 * (FBBD-14), as an event of that session at its end.
 */
private fun logExport(id: String, data: SessionData, export: () -> VboExporter.Written) {
    val started = System.nanoTime()
    val written = export()
    val ms = (System.nanoTime() - started) / NanosPerMilli
    val end = data.sampleSets.maxOfOrNull { it.tMicros } ?: 0L
    val text = "rows=${written.rows} samples=${written.fixes} repeated=${written.repeated} ms=$ms"
    CheckLog.event(id, end, EventKinds.VBO_EXPORT, text)
}

private fun isVideoFile(f: File) =
    f.isFile && f.length() > 0 && f.extension.equals("mp4", ignoreCase = true) && Mp4Reader.isComplete(f)

/**
 * The session's laps, replayed against its track's start/finish line as the lap analysis does,
 * plus the lap still being driven when it stopped (FBBD-150; not on a point-to-point track, where
 * that is the drive back, FBBD-155). Empty when it was not at such a track; a damaged track file
 * only costs the lap columns, not the export.
 */
@Suppress("TooGenericExceptionCaught")
private fun exportLaps(data: SessionData, tracksDir: File): List<ExportLap> = try {
    val track = trackFor(data, TrackStore(tracksDir).all())
    val sets = data.sampleSets.sortedBy { it.tMicros }
    track?.let { replayLaps(data.id, sets, it).exportLaps() }.orEmpty()
} catch (e: Exception) {
    DebugLog.w("Sessions", "laps for export failed", e)
    emptyList()
}

/** Where exports wait for the share sheet (FBBD-443); the transfer provider shares this folder. */
internal fun exportsDir(context: Context) = File(context.cacheDir, "exports-out")

/**
 * [to] as a hard link to [from], the same video under the export's name with no second copy on the
 * phone; a copy where the file system refuses links.
 */
internal fun linkOrCopy(from: File, to: File) {
    try {
        Os.link(from.path, to.path)
    } catch (e: ErrnoException) {
        DebugLog.i("Sessions", "no hard link for ${to.name} (${e.message}), copying")
        from.inputStream().use { input -> to.outputStream().use { input.copyTo(it, CopyBufferBytes) } }
    }
}

private const val CopyBufferBytes = 1 shl 20

/** The file name of an export. */
fun exportFileName(session: SessionSummary, format: ExportFormat) = ExportShare.fileName(session.id, format)

/** Deletes session [id]'s next-session focus (FBBD-20) with the session, so the coach never works from it. */
private fun forgetFocus(context: Context, id: String) {
    try {
        if (!sessionFocusStore(context).deleteSession(id)) DebugLog.w("Sessions", "session focus of $id not deleted")
    } catch (e: IllegalArgumentException) {
        DebugLog.w("Sessions", "session focus of $id not deleted", e)
    }
}
