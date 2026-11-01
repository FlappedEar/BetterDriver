package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.mp4.Mp4Reader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Joins the camera's Wi-Fi network; on Android this is a WifiNetworkSpecifier request. */
interface WifiJoiner {
    /** Joins [ssid] and returns an HTTP client bound to that network. Throws [IOException] on failure. */
    suspend fun join(ssid: String, password: String): GoProHttp

    suspend fun leave()
}

/**
 * Downloads the files one Open GoPro camera recorded with a session and aligns them (FBBD-22): the
 * shared [GoProDownload] (also the iPhone's, FBBD-343) on java.io files in `<session>/video`, over
 * the network [WifiJoiner] joins.
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
     * builds with one GoPro); see [GoProDownload.run].
     */
    suspend fun download(
        sessionDir: File,
        session: SessionData,
        sourceId: String? = null,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val host = Host(sessionDir, session)
        val result = GoProDownload(link, scope, host).run(sourceId) {
            onProgress(Progress(it.file, it.bytes, it.total))
        }
        return Result(result.downloaded, result.problems)
    }

    @Suppress("TooManyFunctions") // One per GoProDownloadHost step.
    private inner class Host(private val sessionDir: File, private val session: SessionData) : GoProDownloadHost {
        private val videoDir = File(sessionDir, "video")
        private val store = GoProRecordings(videoDir)
        private var http: GoProHttp? = null

        private fun file(name: String) = File(videoDir, name)

        override suspend fun join(ssid: String, password: String) {
            http = wifi.join(ssid, password)
        }

        override suspend fun leave() = wifi.leave()

        override suspend fun mediaList(): List<MediaFile> = withContext(Dispatchers.IO) { client().mediaList() }

        override fun size(localName: String): Long? = file(localName).takeIf { it.isFile }?.length()

        override suspend fun download(file: MediaFile, localName: String, onProgress: (Long, Long) -> Unit) =
            withContext(Dispatchers.IO) { client().download(file, file(localName), onProgress) }

        override suspend fun import(recording: GoProRecording, localNames: List<String>): Placement =
            withContext(Dispatchers.Default) {
                val source = GoProImporter.Source(VideoSourceKind.GOPRO, recording.sourceId)
                val result = importer.import(sessionDir, session, localNames.map(::file), source, recording)
                Placement(result.clips, result.problems)
            }

        override suspend fun readable(localName: String): Boolean = withContext(Dispatchers.IO) {
            try {
                Mp4Reader.read(file(localName))
                true
            } catch (_: IOException) {
                false
            }
        }

        override fun delete(localName: String) {
            file(localName).delete()
        }

        override fun recordings(): List<GoProRecording> = store.all()

        override fun putRecording(recording: GoProRecording) = store.put(recording)

        private fun client() = http ?: throw IOException("not on the GoPro's Wi-Fi")
    }

    companion object {
        /** The single HERO11 of builds before FBBD-105; its files kept their own names. */
        const val LegacySourceId = GoProDownload.LegacySourceId

        /** The name a camera's file is saved under in the session. */
        fun localName(sourceId: String, name: String): String = GoProDownload.localName(sourceId, name)
    }
}
