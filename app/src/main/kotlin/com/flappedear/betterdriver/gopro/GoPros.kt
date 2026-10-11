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
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.shell.SetupRepo
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

/** What the app is doing with a session's GoPro video after the session, shown on its card. */
sealed interface GoProWork {
    /** Downloading from the HERO11: the [index]th file (1-based), [bytes] of [total] so far. */
    data class Downloading(val index: Int, val bytes: Long, val total: Long) : GoProWork

    /** Copying HERO8 files into the session and lining them up. */
    data class Adding(val files: Int, val bytes: Long, val total: Long) : GoProWork
}

/**
 * GoPro support in the UI process (FBBD-22).
 *
 * During a session it runs a [Hero11Source] for the HERO11 turned on for that session on home
 * (UI-36): started when the recorder reports a running session, stopped when that session ends. A
 * HERO11 that is listed but turned off, or not listed at all, is never started (FBBD-83). It lives
 * here, not in the recorder process, and every failure stays inside the source, so a GoPro problem
 * never reaches the recording (FBBD-69).
 *
 * After a session it downloads the HERO11's files over the camera's Wi-Fi ([GoProDownloader]) and
 * adds HERO8 files the user picks ([GoProImporter]). Neither runs while a session records.
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
    private var source: Hero11Source? = null
    private var sourceLog: Job? = null

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
        // At most one HERO11 is listed (EquipmentModel.single), so this is the HERO11 or none.
        val hero11 = setup.forSession().active().appCameras.firstOrNull { it.model == EquipmentModel.GOPRO_HERO11 }
        val address = hero11?.address ?: return
        if (!bluetoothGranted()) return
        val hero = Hero11Source(CameraIds.GOPRO_HERO11, GoProBleLink(context, address), scope)
        source = hero
        // Logged for the real-camera test (FBBD-82); the track screen does not show the GoPro yet.
        sourceLog = scope.launch { hero.state.collect { android.util.Log.i(Tag, "HERO11: $it") } }
        val videoDir = File(File(sessionsDir(context), sessionId), VideoFiles.DIR)
        val session = VideoSessionContext(sessionId, startUtcMillis, videoDir) {
            (System.nanoTime() - startNanos) / NanosPerMicro
        }
        scope.launch { hero.start(session) }
    }

    private fun end() {
        liveSession = null
        val hero = source ?: return
        source = null
        val log = sourceLog
        sourceLog = null
        scope.launch {
            hero.stop()
            log?.cancel()
        }
    }

    /**
     * Pairs with the GoPro at [address] (it must be in pairing mode) and returns its model name,
     * or throws [IOException]. Android shows its pairing prompt if needed.
     */
    suspend fun pair(address: String): String {
        val link = GoProBleLink(context, address)
        val client = GoProClient(link, scope)
        return try {
            withTimeout(PairTimeoutMs) {
                link.connect()
                val info = OpenGoPro.HardwareInfo.parse(client.send(OpenGoPro.hardwareInfo()).payload)
                info?.modelName?.takeIf { it.isNotBlank() } ?: "GoPro"
            }
        } catch (_: TimeoutCancellationException) {
            throw GoProException("The GoPro did not answer")
        } finally {
            client.close()
            link.disconnect()
        }
    }

    /** Downloads the HERO11 files of session [id] and lines them up (UI-31). */
    fun download(id: String) {
        if (recording || id in work) return
        val address = setup.hero11Address() ?: return
        work[id] = GoProWork.Downloading(1, 0, 0)
        problems.remove(id)
        scope.launch {
            val files = mutableListOf<String>()
            val result = try {
                val dir = File(sessionsDir(context), id)
                val data = withContext(Dispatchers.IO) { SessionStore(sessionsDir(context)).read(id) }
                val downloader = GoProDownloader(GoProBleLink(context, address), scope, AndroidWifiJoiner(context))
                var shown = -1L
                downloader.download(dir, data) { p ->
                    val first = p.file !in files
                    if (first) files += p.file
                    if (first || p.bytes / ProgressStep != shown || p.bytes == p.total) {
                        shown = p.bytes / ProgressStep
                        val w = GoProWork.Downloading(files.indexOf(p.file) + 1, p.bytes, p.total)
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
     * Copies HERO8 files the user picked into session [id] and lines them up (UI-32). Files of one
     * recording (chapters GH01xxxx, GH02xxxx, ...) are imported together, in chapter order. A file
     * that cannot be placed in the session is reported and its copy deleted.
     */
    fun addHero8(id: String, uris: List<Uri>) {
        if (recording || id in work || uris.isEmpty()) return
        val hero8 = Hero8Import(context)
        val total = uris.sumOf(hero8::sizeOf)
        work[id] = GoProWork.Adding(uris.size, 0, total)
        problems.remove(id)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                var shown = -1L
                hero8.run(File(sessionsDir(context), id), uris) { copied ->
                    if (copied / ProgressStep != shown) {
                        shown = copied / ProgressStep
                        val w = GoProWork.Adding(uris.size, copied, total)
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
