package com.flappedear.betterdriver.recording

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.recorder.store.DeleteResult
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import com.flappedear.betterdriver.recorder.vbo.VboVideo
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.gopro.GoProRecordings
import com.flappedear.betterdriver.shell.GoProClips
import com.flappedear.betterdriver.shell.SessionCamera
import com.flappedear.betterdriver.shell.SessionGoPro
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.ClipStatus
import com.flappedear.betterdriver.video.VideoIndex
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
    private val store = SessionStore(sessionsDir(context))
    private val io = Executors.newSingleThreadExecutor { Thread(it, "sessions-io") }
    private val main = Handler(Looper.getMainLooper())

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
                android.util.Log.w(Tag, "recovery failed", e)
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
            val result = try {
                store.delete(id)
            } catch (e: SecurityException) {
                android.util.Log.w(Tag, "delete failed", e)
                DeleteResult.FAILED
            }
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

    /** Writes the session as VBO to [uri], a document the user picked. */
    fun export(id: String, uri: Uri) {
        exports[id] = ExportState.Running
        io.execute {
            val result = try {
                val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
                out.use { VboExporter.export(store.read(id), it) }
                ExportState.Done
            } catch (e: IOException) {
                android.util.Log.w(Tag, "export failed", e)
                ExportState.Failed
            } catch (e: IllegalStateException) {
                android.util.Log.w(Tag, "export failed", e) // unreadable metadata
                ExportState.Failed
            }
            main.post { exports[id] = result }
        }
    }

    /**
     * Writes the session to [tree], a folder the user picked, as Circuit Tools expects it (FBBD-25):
     * `BetterDriver-<id>.vbo` with an [avi] section, and the video files next to it as
     * `BetterDriver-<id>_0001.MP4` and on. Phone video is used when there is any, else the first
     * camera that has files.
     */
    fun exportWithVideo(id: String, tree: Uri) {
        exports[id] = ExportState.Running
        io.execute {
            val result = try {
                val dir = store.dir(id)
                val data = store.read(id)
                val video = exportVideo(id, dir, VideoTimeline.from(data.events, indexedClips(dir)))
                val folder =
                    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                create(folder, VboMimeType, "BetterDriver-$id.vbo").use { VboExporter.export(data, it, video) }
                video?.orderedClips?.forEachIndexed { i, clip ->
                    create(folder, Mp4MimeType, video.fileName(i)).use { out ->
                        File(File(dir, VideoFiles.DIR), clip.file).inputStream().use { it.copyTo(out, CopyBuffer) }
                    }
                }
                ExportState.Done
            } catch (e: IOException) {
                android.util.Log.w(Tag, "export failed", e)
                ExportState.Failed
            } catch (e: IllegalStateException) {
                android.util.Log.w(Tag, "export failed", e) // unreadable metadata
                ExportState.Failed
            } catch (e: IllegalArgumentException) {
                android.util.Log.w(Tag, "export failed", e) // folder no longer usable
                ExportState.Failed
            }
            main.post { exports[id] = result }
        }
    }

    /** The session's clip index (clips.json) on the recorder's terms; clips still on a camera are left out. */
    private fun indexedClips(dir: File) = VideoIndex(dir).clips()
        .filter { it.status == ClipStatus.LOCAL }
        .map { c ->
            VideoClip(c.kind.name.lowercase(), c.fileName, c.sessionOffsetUs, c.durationUs?.let(c::sessionTimeUs))
        }

    private fun exportVideo(id: String, dir: File, timeline: VideoTimeline): VboVideo? {
        val videoDir = File(dir, VideoFiles.DIR)
        val present = timeline.clips.filter { File(videoDir, it.file).isFile }
        val camera = present.firstOrNull { it.camera == CameraIds.PHONE }?.camera ?: present.firstOrNull()?.camera
        val clips = present.filter { it.camera == camera }
        return if (clips.isEmpty()) null else VboVideo("BetterDriver-${id}_", clips)
    }

    private fun create(folder: Uri, mime: String, name: String) =
        DocumentsContract.createDocument(context.contentResolver, folder, mime, name)
            ?.let { context.contentResolver.openOutputStream(it) }
            ?: throw IOException("cannot create $name")

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
    }

    companion object {
        private const val Tag = "Sessions"
        private const val VboMimeType = "application/octet-stream"
        private const val Mp4MimeType = "video/mp4"
        private const val CopyBuffer = 1 shl 20

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
        android.util.Log.w("Sessions", "unreadable clip index in $dir", e)
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

private fun isVideoFile(f: File) = f.isFile && f.length() > 0 && f.extension.equals("mp4", ignoreCase = true)

/** The file name offered when exporting. */
fun vboFileName(session: SessionSummary) = "BetterDriver-${session.id}.vbo"
