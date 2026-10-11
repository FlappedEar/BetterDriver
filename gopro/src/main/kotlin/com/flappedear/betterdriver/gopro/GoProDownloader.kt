package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.video.VideoSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.time.Instant

/** Joins the camera's Wi-Fi network; on Android this is a WifiNetworkSpecifier request. */
interface WifiJoiner {
    /** Joins [ssid] and returns an HTTP client bound to that network. Throws [IOException] on failure. */
    suspend fun join(ssid: String, password: String): GoProHttp

    suspend fun leave()
}

/**
 * Downloads the files one Open GoPro camera recorded with a session and aligns them (FBBD-22).
 *
 * Over BLE it turns on the camera's access point and reads its SSID and password
 * (https://gopro.github.io/OpenGoPro/tutorials/connect-wifi); it then joins that network, lists
 * the camera's media, downloads the files recorded between the start and stop acknowledgements
 * into `<session>/video`, and imports them. Afterwards it leaves the network and turns the access
 * point off. The app must not call this while a session is recording: the Wi-Fi join and large
 * downloads have no place next to a live recording.
 */
class GoProDownloader(
    private val link: GoProLink,
    private val scope: CoroutineScope,
    private val wifi: WifiJoiner,
    private val importer: GoProImporter = GoProImporter(),
) {
    data class Progress(val file: String, val bytes: Long, val total: Long)

    data class Result(val downloaded: List<String>, val problems: List<String>)

    /**
     * Downloads the recordings of camera [sourceId], or of every camera when null (sessions from
     * builds with one GoPro). Several cameras name their files alike (GX010001.MP4), so a file is
     * saved as `<sourceId>-<name>` unless it is from such an older session.
     */
    suspend fun download(
        sessionDir: File,
        session: SessionData,
        sourceId: String? = null,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val videoDir = File(sessionDir, "video")
        val store = GoProRecordings(videoDir)
        val pending = store.all().filter { !it.downloaded && (sourceId == null || it.sourceId == sourceId) }
        if (pending.isEmpty()) return Result(emptyList(), emptyList())
        val client = GoProClient(link, scope)
        val downloaded = mutableListOf<String>()
        val problems = mutableListOf<String>()
        try {
            withTimeoutOrNull(ConnectTimeoutMillis) { link.connect() } ?: throw GoProException("GoPro not found")
            client.send(OpenGoPro.wifiAp(true))
            val ssid = client.read(GoProUuids.WIFI_AP_SSID).decodeToString()
            val password = client.read(GoProUuids.WIFI_AP_PASSWORD).decodeToString()
            val http = wifi.join(ssid, password)
            val media = withContext(Dispatchers.IO) { http.mediaList() }
            for (recording in pending) {
                val files = GoProMedia.filesFor(recording, media)
                if (files.isEmpty()) {
                    problems += "No GoPro files found for the recording started at " +
                        Instant.ofEpochMilli(recording.startAckUtcMillis)
                    continue
                }
                val local = files.map { f ->
                    val target = File(videoDir, localName(recording.sourceId, f.name))
                    if (!(target.isFile && target.length() == f.size)) {
                        withContext(Dispatchers.IO) {
                            http.download(f, target) { bytes, total -> onProgress(Progress(f.name, bytes, total)) }
                        }
                    }
                    downloaded += f.name
                    target
                }
                val result = withContext(Dispatchers.Default) {
                    val source = GoProImporter.Source(VideoSourceKind.GOPRO, recording.sourceId)
                    importer.import(sessionDir, session, local, source, recording)
                }
                problems += result.problems
                store.put(recording.copy(downloaded = true))
            }
        } catch (e: IOException) {
            problems += "GoPro download stopped: ${e.message}"
        } finally {
            cleanUp(client)
        }
        return Result(downloaded, problems)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Best effort: the files are already safe.
    private suspend fun cleanUp(client: GoProClient) {
        try {
            wifi.leave()
        } catch (_: Exception) {
        }
        try {
            client.send(OpenGoPro.wifiAp(false))
        } catch (_: Exception) {
        }
        try {
            link.disconnect()
        } catch (_: Exception) {
        }
        client.close()
    }

    companion object {
        private const val ConnectTimeoutMillis = 20_000L

        /** The single HERO11 of builds before FBBD-105; its files kept their own names. */
        const val LegacySourceId = "gopro_hero11"

        /** The name a camera's file is saved under in the session. */
        fun localName(sourceId: String, name: String): String =
            if (sourceId == LegacySourceId) name else "$sourceId-$name"
    }
}
