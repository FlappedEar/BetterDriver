@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.gopro.CameraFile
import com.flappedear.betterdriver.gopro.GoProDownload
import com.flappedear.betterdriver.gopro.GoProDownloadHost
import com.flappedear.betterdriver.gopro.GoProMedia
import com.flappedear.betterdriver.gopro.GoProPlacer
import com.flappedear.betterdriver.gopro.GoProRecording
import com.flappedear.betterdriver.gopro.GoProRecordingsJson
import com.flappedear.betterdriver.gopro.MediaFile
import com.flappedear.betterdriver.gopro.Placement
import com.flappedear.betterdriver.recorder.log.logInfo
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.video.IoProblem
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.mp4.Mp4Parser
import com.flappedear.betterdriver.video.sync.GpsAligner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber

/**
 * The camera's Wi-Fi and HTTP API on the iPhone (FBBD-343): the app's Swift code subclasses it and
 * does each step it is asked to ([beginJoin] with NEHotspotConfiguration, [beginGet] and
 * [beginDownload] with URLSession, [beginLeave]), then calls back with the outcome ([joined], [got],
 * [downloadProgress], [downloaded]). Calls come and go on the main thread.
 */
@Suppress("TooManyFunctions") // One call per step and one callback per outcome.
abstract class IosGoProWifi {
    private var joining: CompletableDeferred<Unit>? = null
    private var getting: CompletableDeferred<String>? = null
    private var fetching: CompletableDeferred<Unit>? = null
    private var progress: (Long, Long) -> Unit = { _, _ -> }

    /** Join the access point [ssid] with [password]; answer [joined]. */
    abstract fun beginJoin(ssid: String, password: String)

    /** Forget the access point [ssid]. Needs no answer. */
    abstract fun beginLeave(ssid: String)

    /** HTTP GET [url]; answer [got] with the body as text. */
    abstract fun beginGet(url: String)

    /** HTTP GET [url] into the file [toPath], replacing it; answer [downloadProgress] as it goes, then [downloaded]. */
    abstract fun beginDownload(url: String, toPath: String)

    /** Joined, or [error] why not. */
    fun joined(error: String?) {
        val done = joining ?: return
        joining = null
        if (error == null) done.complete(Unit) else done.completeExceptionally(IoProblem(error, null))
    }

    fun got(body: String?, error: String?) {
        val done = getting ?: return
        getting = null
        if (body != null && error == null) {
            done.complete(body)
        } else {
            done.completeExceptionally(IoProblem(error ?: "no answer", null))
        }
    }

    fun downloadProgress(bytes: Long, total: Long) = progress(bytes, total)

    fun downloaded(error: String?) {
        val done = fetching ?: return
        fetching = null
        if (error == null) done.complete(Unit) else done.completeExceptionally(IoProblem(error, null))
    }

    internal suspend fun join(ssid: String, password: String) =
        await(CompletableDeferred<Unit>().also { joining = it }) { beginJoin(ssid, password) }

    internal suspend fun get(url: String): String = await(CompletableDeferred<String>().also { getting = it }) {
        beginGet(url)
    }

    internal suspend fun download(url: String, toPath: String, onProgress: (Long, Long) -> Unit) {
        progress = onProgress
        try {
            await(CompletableDeferred<Unit>().also { fetching = it }) { beginDownload(url, toPath) }
        } finally {
            progress = { _, _ -> }
        }
    }

    private suspend fun <T> await(done: CompletableDeferred<T>, begin: () -> Unit): T {
        begin()
        return done.await()
    }
}

/** A file coming from the camera: file [index] of this download, [bytes] of [total] so far. */
data class GoProDownloadProgress(val sourceId: String, val index: Int, val bytes: Long, val total: Long)

/** An Open GoPro camera that still holds files it recorded with a session: the card offers Download. */
data class CameraOnCamera(
    val sourceId: String,
    val name: String,
    /** The iPhone's Bluetooth identifier for the camera; null when it is no longer in the equipment list. */
    val address: String?,
)

/**
 * Downloads an Open GoPro camera's files of a session over its Wi-Fi after the drive (FBBD-343), as
 * Android does (UI-31): the shared [GoProDownload] over the camera's Bluetooth link ([IosGoProLink])
 * and Wi-Fi ([IosGoProWifi]), with the files kept in `<session>/video` and lined up by the camera's
 * GPS as files added by hand are. Runs on the main thread, reading and placing files off it.
 */
class GoProDownloads internal constructor(private val sessionsRoot: String, private val baseUrl: String) {
    constructor(sessionsRoot: String) : this(sessionsRoot, CameraUrl)

    /**
     * Downloads camera [sourceId]'s recordings of session [sessionId]; [done] gets the problems, one
     * line each, and is always called. Never call it while a session records.
     */
    @Suppress("LongParameterList") // Swift calls it with the two links and two callbacks.
    fun download(
        sessionId: String,
        sourceId: String,
        link: IosGoProLink,
        wifi: IosGoProWifi,
        onProgress: (GoProDownloadProgress) -> Unit,
        done: (List<String>) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            val problems = run(sessionId, sourceId, link, wifi, onProgress)
            done(problems)
            scope.cancel()
        }
    }

    /** [download] in the caller's coroutine: the problems, one line each. */
    internal suspend fun run(
        sessionId: String,
        sourceId: String,
        link: IosGoProLink,
        wifi: IosGoProWifi,
        onProgress: (GoProDownloadProgress) -> Unit,
    ): List<String> = coroutineScope {
        logInfo(GoProTag, "$sessionId camera=$sourceId download start")
        val problems = try {
            val host = Host(sessionsRoot, sessionId, wifi, baseUrl)
            val result = GoProDownload(link, this, host).run(sourceId) {
                onProgress(GoProDownloadProgress(sourceId, it.index, it.bytes, it.total))
            }
            val files = result.downloaded.size
            logInfo(GoProTag, "$sessionId camera=$sourceId download end files=$files problems=${result.problems.size}")
            result.problems
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // The download must always end.
            logInfo(GoProTag, "$sessionId camera=$sourceId download failed: $e")
            listOf("GoPro download failed: ${e.message ?: e::class.simpleName}")
        }
        problems.forEach { logInfo(GoProTag, "$sessionId camera=$sourceId problem: $it") }
        problems
    }

    /** The phone's side of [GoProDownload] for one session. */
    @Suppress("TooManyFunctions") // One per GoProDownloadHost step.
    private class Host(
        private val sessionsRoot: String,
        private val sessionId: String,
        private val wifi: IosGoProWifi,
        private val baseUrl: String,
    ) : GoProDownloadHost {
        private val videoDir = "$sessionsRoot/$sessionId/${VideoFiles.DIR}"
        private val recordingsPath = "$videoDir/${GoProRecordingsJson.FileName}"
        private var ssid: String? = null

        private fun path(name: String) = "$videoDir/$name"

        override suspend fun join(ssid: String, password: String) {
            this.ssid = ssid
            logInfo(GoProTag, "$sessionId joining the GoPro's Wi-Fi")
            wifi.join(ssid, password)
            // iOS says joined before the network carries traffic: wait for the camera to answer.
            var last: IoProblem? = null
            repeat(CameraTries) { attempt ->
                try {
                    wifi.get("$baseUrl/gopro/camera/keep_alive")
                    logInfo(GoProTag, "$sessionId GoPro answers on Wi-Fi after ${attempt + 1} tries")
                    return
                } catch (e: IoProblem) {
                    last = e
                    delay(CameraRetryMillis)
                }
            }
            throw IoProblem("joined the GoPro's Wi-Fi, but the camera does not answer: ${last?.message}", last)
        }

        override suspend fun leave() {
            ssid?.let(wifi::beginLeave)
        }

        override suspend fun mediaList(): List<MediaFile> {
            val text = wifi.get("$baseUrl/gopro/media/list")
            return try {
                GoProMedia.parseList(text)
            } catch (e: IllegalArgumentException) {
                throw IoProblem("the GoPro's media list does not read: ${e.message}", e)
            }
        }

        override fun size(localName: String): Long? {
            val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path(localName), null) ?: return null
            return (attributes[NSFileSize] as? NSNumber)?.longLongValue
        }

        override suspend fun download(file: MediaFile, localName: String, onProgress: (Long, Long) -> Unit) {
            NSFileManager.defaultManager.createDirectoryAtPath(videoDir, true, null, null)
            // Through a .part file renamed when complete, so a cut-off download never looks finished.
            val part = path("$localName$PartSuffix")
            NSFileManager.defaultManager.removeItemAtPath(part, null)
            logInfo(GoProTag, "$sessionId downloading ${file.path} bytes=${file.size}")
            wifi.download("$baseUrl/videos/DCIM/${file.path}", part) { bytes, total ->
                onProgress(bytes, if (total > 0) total else file.size)
            }
            val got = size("$localName$PartSuffix") ?: 0
            if (file.size > 0 && got != file.size) {
                NSFileManager.defaultManager.removeItemAtPath(part, null)
                throw IoProblem("${file.name}: got $got of ${file.size} bytes", null)
            }
            if (platform.posix.rename(part, path(localName)) != 0) throw IoProblem("could not rename $part", null)
        }

        override suspend fun import(recording: GoProRecording, localNames: List<String>): Placement =
            withContext(Dispatchers.Default) {
                val data = SessionStore(sessionsRoot).read(sessionId)
                val placer = GoProPlacer(GpsAligner(), ::localOffsetMillis, IosPlacementLog)
                val files = localNames.map { name -> CameraFile(name) { PosixMp4Source(path(name)) } }
                val placed = placer.place(data, files, VideoSourceKind.GOPRO, recording.sourceId, recording)
                if (placed.clips.isNotEmpty()) ClipIndex(videoDir).putAll(placed.clips)
                placed
            }

        override suspend fun readable(localName: String): Boolean = withContext(Dispatchers.Default) {
            try {
                val source = PosixMp4Source(path(localName))
                try {
                    Mp4Parser.read(source, localName)
                } finally {
                    source.close()
                }
                true
            } catch (@Suppress("SwallowedException") _: IoProblem) { // Not an MP4 is the answer.
                false
            }
        }

        override fun delete(localName: String) {
            NSFileManager.defaultManager.removeItemAtPath(path(localName), null)
        }

        override fun recordings(): List<GoProRecording> =
            readText(recordingsPath)?.let(GoProRecordingsJson::decode).orEmpty()

        override fun putRecording(recording: GoProRecording) {
            val all = GoProRecordingsJson.put(recordings(), recording)
            writeBytesAtomically(recordingsPath, GoProRecordingsJson.encode(all).encodeToByteArray())
        }
    }

    companion object {
        /** The camera's HTTP API on its own access point. */
        const val CameraUrl = "http://10.5.5.9:8080"
        private const val PartSuffix = ".part"
        private const val CameraTries = 10
        private const val CameraRetryMillis = 1_500L

        /**
         * The Open GoPro cameras that still hold files they recorded with session [sessionId], as
         * Android's "video on the camera" line: each recording note not yet downloaded. A camera no
         * longer in the equipment list keeps its line, without a Bluetooth identifier to download with.
         */
        fun onCamera(setup: Setup, sessionsRoot: String, sessionId: String): List<CameraOnCamera> {
            val path = "$sessionsRoot/$sessionId/${VideoFiles.DIR}/${GoProRecordingsJson.FileName}"
            val pending = readText(path)?.let(GoProRecordingsJson::decode).orEmpty()
                .filter { !it.downloaded }.map { it.sourceId }.distinct()
            val openGoPros = setup.equipment.filter { it.model == EquipmentModel.GOPRO && it.address != null }
            return pending.map { id ->
                // A session from before FBBD-105 names its one HERO11 by model.
                val camera = openGoPros.firstOrNull { CameraIds.goPro(it.id) == id }
                    ?: openGoPros.firstOrNull { id == CameraIds.GOPRO_HERO11 && it.goPro == GoProModel.HERO11_BLACK }
                CameraOnCamera(id, camera?.name ?: "GoPro", camera?.address)
            }
        }
    }
}
