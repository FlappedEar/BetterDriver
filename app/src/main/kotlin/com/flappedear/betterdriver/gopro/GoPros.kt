package com.flappedear.betterdriver.gopro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.UiChecks
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.VideoMode
import com.flappedear.betterdriver.setup.VideoSize
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.VideoSessionContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val NanosPerMicro = 1_000L
private const val PairTimeoutMs = 45_000L
private const val Tag = "GoPros"

/** Progress is shown in steps of this many bytes, so a multi-GB copy does not flood the UI. */
internal const val ProgressStep = 8L shl 20

/**
 * What the app is doing with a session's GoPro video after the session, shown on its card. One
 * thing at a time per session; [sourceId] is the camera it is for ([CameraIds.goPro]).
 */
sealed interface GoProWork {
    val sourceId: String

    /** Downloading from an Open GoPro camera: the [index]th file (1-based), [bytes] of [total] so far. */
    data class Downloading(override val sourceId: String, val index: Int, val bytes: Long, val total: Long) : GoProWork

    /** Copying a GoPro's files into the session and lining them up. */
    data class Adding(override val sourceId: String, val files: Int, val bytes: Long, val total: Long) : GoProWork
}

/** What pairing learned about an Open GoPro camera: its model, from Get Hardware Info. */
data class PairedGoPro(val model: GoProModel, val modelName: String)

/**
 * GoPro support in the UI process (FBBD-22).
 *
 * During a session it runs an [OpenGoProSource] for each Open GoPro camera turned on for that
 * session on home (UI-36), any number of them (FBBD-105): started when the recorder reports a
 * running session, stopped when that session ends. A camera that is listed but turned off is never
 * started (FBBD-83). It lives
 * here, not in the recorder process, and every failure stays inside the source, so a GoPro problem
 * never reaches the recording (FBBD-69).
 *
 * After a session it downloads each camera's files over that camera's Wi-Fi ([GoProDownloader]) and
 * adds files the user picks for a GoPro started by hand ([GoProImporter]). Neither runs while a
 * session records.
 * Work and its outcome are Compose state, read by the Sessions screen.
 */
class GoPros private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val setup = SetupRepo.get(context)

    /** By session id. */
    val work = mutableStateMapOf<String, GoProWork>()

    /** Problems from the last download or import, by session id. */
    val problems = mutableStateMapOf<String, List<String>>()

    /** The running session, or null. Compose state, so the Sessions screen follows [recording]. */
    private var liveSession by mutableStateOf<String?>(null)
    private var sources: List<OpenGoProSource> = emptyList()
    private var sourceLogs: List<Job> = emptyList()

    /** True while a session records; no download or import starts then. */
    val recording: Boolean get() = liveSession != null

    /**
     * Called with every recorder state: [sessionId] is the running session, or null when none runs.
     * [startNanos] is the session start on System.nanoTime, which both processes share.
     */
    fun onRecorderState(sessionId: String?, startUtcMillis: Long, startNanos: Long?) {
        val current = liveSession
        if (sessionId == current) return
        if (current != null) end()
        if (sessionId != null && startNanos != null) begin(sessionId, startUtcMillis, startNanos)
    }

    private fun begin(sessionId: String, startUtcMillis: Long, startNanos: Long) {
        liveSession = sessionId
        val cameras = setup.forSession().active().appCameras.filter { it.model == EquipmentModel.GOPRO }
        if (cameras.isEmpty() || !bluetoothGranted()) return
        val videoDir = File(File(sessionsDir(context), sessionId), VideoFiles.DIR)
        val session = VideoSessionContext(sessionId, startUtcMillis, videoDir) {
            (System.nanoTime() - startNanos) / NanosPerMicro
        }
        sources = cameras.mapNotNull { camera ->
            val address = camera.address ?: return@mapNotNull null
            val id = CameraIds.goPro(camera.id)
            OpenGoProSource(
                id,
                GoProBleLink(context, address),
                scope,
                video = camera.videoMode?.let(::goProVideo),
                io = Dispatchers.IO,
                // Into the drive's log under this session, also for the stop that comes after it ends (FBBD-82).
                acks = { ack ->
                    val result = if (ack.ok) "ok" else "failed"
                    val text = "camera=$id action=${ack.action} result=$result"
                    UiChecks.report(sessionId, ack.sessionUs, EventKinds.GOPRO, text)
                },
            )
        }
        // Logged for the real-camera test (FBBD-82); the track screen does not show the GoPros yet.
        sourceLogs = sources.map { source ->
            scope.launch { source.state.collect { DebugLog.i(Tag, "${source.id}: $it") } }
        }
        sources.forEach { source -> scope.launch { source.start(session) } }
    }

    private fun end() {
        liveSession = null
        val stopping = sources
        val logs = sourceLogs
        sources = emptyList()
        sourceLogs = emptyList()
        stopping.forEachIndexed { i, source ->
            scope.launch {
                source.stop()
                logs.getOrNull(i)?.cancel()
            }
        }
    }

    /**
     * Pairs with the GoPro at [address] (it must be in pairing mode) and returns which camera it
     * is, or throws [IOException]. Android shows its pairing prompt if needed. The model number
     * decides; a number this build does not know is still an Open GoPro camera.
     */
    suspend fun pair(address: String): PairedGoPro {
        val link = GoProBleLink(context, address)
        val client = GoProClient(link, scope)
        return try {
            withTimeout(PairTimeoutMs) {
                link.connect()
                val info = OpenGoPro.HardwareInfo.parse(client.send(OpenGoPro.hardwareInfo()).payload)
                val byNumber = GoProModel.ofModelId(info?.modelNumber)
                val model = byNumber.takeIf { it != GoProModel.OTHER_OPEN_GOPRO }
                    ?: GoProModel.ofName(info?.modelName)
                    ?: byNumber
                PairedGoPro(model, info?.modelName?.takeIf { it.isNotBlank() } ?: "GoPro")
            }
        } catch (_: TimeoutCancellationException) {
            throw GoProException("The GoPro did not answer")
        } finally {
            client.close()
            link.disconnect()
        }
    }

    /** Downloads the files camera [sourceId] recorded with session [id] and lines them up (UI-31). */
    fun download(id: String, sourceId: String) {
        if (recording || id in work) return
        val address = setup.goProFor(sourceId)?.address ?: return
        work[id] = GoProWork.Downloading(sourceId, 1, 0, 0)
        problems.remove(id)
        scope.launch {
            val files = mutableListOf<String>()
            val result = try {
                val dir = File(sessionsDir(context), id)
                val data = withContext(Dispatchers.IO) { SessionStore(sessionsDir(context)).read(id) }
                val downloader = GoProDownloader(GoProBleLink(context, address), scope, AndroidWifiJoiner(context))
                var shown = -1L
                downloader.download(dir, data, sourceId) { p ->
                    val first = p.file !in files
                    if (first) files += p.file
                    if (first || p.bytes / ProgressStep != shown || p.bytes == p.total) {
                        shown = p.bytes / ProgressStep
                        val w = GoProWork.Downloading(sourceId, files.indexOf(p.file) + 1, p.bytes, p.total)
                        scope.launch { work[id] = w }
                    }
                }.problems
            } catch (e: IOException) {
                listOf("GoPro download failed: ${e.message}")
            } catch (e: IllegalStateException) {
                listOf("GoPro download failed: ${e.message}")
            }
            finish(id, result)
        }
    }

    /**
     * Copies files the user picked for camera [sourceId] into session [id] and lines them up
     * (UI-32). Files of one recording (chapters GH01xxxx, GH02xxxx, ...) are imported together, in
     * chapter order. A file that cannot be placed in the session is reported and its copy deleted.
     */
    fun addFiles(id: String, sourceId: String, uris: List<Uri>) {
        if (recording || id in work || uris.isEmpty()) return
        val other = setup.setup.equipment.any {
            it.model == EquipmentModel.CAMERA_FILES && CameraIds.goPro(it.id) == sourceId
        }
        val kind = if (other) VideoSourceKind.CAMERA_FILES else VideoSourceKind.GOPRO_FILES
        val adding = GoProFileImport(context, sourceId, kind)
        val total = uris.sumOf(adding::sizeOf)
        work[id] = GoProWork.Adding(sourceId, uris.size, 0, total)
        problems.remove(id)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                var shown = -1L
                adding.run(File(sessionsDir(context), id), uris) { copied ->
                    if (copied / ProgressStep != shown) {
                        shown = copied / ProgressStep
                        val w = GoProWork.Adding(sourceId, uris.size, copied, total)
                        scope.launch { work[id] = w }
                    }
                }
            }
            finish(id, result)
        }
    }

    private fun finish(id: String, result: List<String>) {
        work.remove(id)
        if (result.isNotEmpty()) problems[id] = result
        Sessions.get(context).refresh()
    }

    private fun bluetoothGranted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    companion object {
        @Volatile
        private var instance: GoPros? = null

        fun get(context: Context): GoPros = instance ?: synchronized(this) {
            instance ?: GoPros(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Open GoPro setting values for a video mode (FBBD-88); null for a size or rate Open GoPro has no
 * value for here. Whether the camera takes it is up to the camera: the modes offered per camera
 * come from GoProModel, and a refused setting leaves the camera's own mode.
 */
internal fun goProVideo(mode: VideoMode): GoProVideo? {
    val resolution = when (mode.size) {
        VideoSize.UHD -> OpenGoPro.Setting.RES_4K
        VideoSize.QHD -> OpenGoPro.Setting.RES_2_7K
        VideoSize.FHD -> OpenGoPro.Setting.RES_1080
        VideoSize.HD -> OpenGoPro.Setting.RES_720
    }
    val fps = when (mode.fps) {
        HighFps -> OpenGoPro.Setting.FPS_60
        LowFps -> OpenGoPro.Setting.FPS_30
        else -> null
    }
    return if (fps != null) GoProVideo(resolution, fps, mode.label, mode.size.label) else null
}

private const val HighFps = 60
private const val LowFps = 30
