package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.IoProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a phone does for [GoProDownload]: join and leave the camera's Wi-Fi, talk to its HTTP API,
 * and keep the files of one session's video folder. Android does it with java.io and a
 * WifiNetworkSpecifier, the iPhone with Foundation, NEHotspotConfiguration and URLSession. Files are
 * named as they are, or will be, in the session's video folder. Failures throw [IoProblem].
 */
interface GoProDownloadHost {
    /** Joins the camera's access point [ssid]. */
    suspend fun join(ssid: String, password: String)

    /** Leaves the camera's network. */
    suspend fun leave()

    /** `GET /gopro/media/list`, parsed ([GoProMedia.parseList]). */
    suspend fun mediaList(): List<MediaFile>

    /** The size of the session's file [localName], or null when there is none. */
    fun size(localName: String): Long?

    /**
     * Downloads [file] to the session's file [localName], never leaving a cut-off file under that
     * name. [onProgress] gets the bytes so far and the total.
     */
    suspend fun download(file: MediaFile, localName: String, onProgress: (Long, Long) -> Unit)

    /**
     * Places the session's files [localNames], one recording in chapter order, and adds the placed
     * ones to its clip index.
     */
    suspend fun import(recording: GoProRecording, localNames: List<String>): Placement

    /**
     * True when the session's file [localName] reads as an MP4: it came whole, though it may lie
     * outside the session.
     */
    suspend fun readable(localName: String): Boolean

    fun delete(localName: String)

    /** The session's Open GoPro recordings (`gopro-recordings.json`). */
    fun recordings(): List<GoProRecording>

    /** Adds or replaces [recording] in `gopro-recordings.json`. */
    fun putRecording(recording: GoProRecording)
}

/**
 * Downloads the files one Open GoPro camera recorded with a session and lines them up (FBBD-22,
 * FBBD-343), the same on Android and the iPhone.
 *
 * Over BLE it turns on the camera's access point and reads its SSID and password
 * (https://gopro.github.io/OpenGoPro/tutorials/connect-wifi); it then joins that network, lists
 * the camera's media, downloads the files recorded between the start and stop acknowledgements
 * into the session's video folder, and imports them. Afterwards it leaves the network and turns the
 * access point off. The app must not call this while a session is recording: the Wi-Fi join and
 * large downloads have no place next to a live recording.
 */
class GoProDownload(
    private val link: GoProLink,
    private val scope: CoroutineScope,
    private val host: GoProDownloadHost,
) {
    /** File [index] (from 1) of this run, [bytes] of [total] so far. */
    data class Progress(val file: String, val bytes: Long, val total: Long, val index: Int)

    data class Result(val downloaded: List<String>, val problems: List<String>)

    /**
     * Downloads the recordings of camera [sourceId], or of every camera when null (sessions from
     * builds with one GoPro). Several cameras name their files alike (GX010001.MP4), so a file is
     * saved as `<sourceId>-<name>` unless it is from such an older session ([localName]).
     */
    suspend fun run(sourceId: String? = null, onProgress: (Progress) -> Unit = {}): Result {
        val pending = host.recordings().filter { !it.downloaded && (sourceId == null || it.sourceId == sourceId) }
        if (pending.isEmpty()) return Result(emptyList(), emptyList())
        val client = GoProClient(link, scope)
        val downloaded = mutableListOf<String>()
        val problems = mutableListOf<String>()
        try {
            withTimeoutOrNull(ConnectTimeoutMillis) { link.connect() } ?: throw GoProException("GoPro not found")
            client.send(OpenGoPro.wifiAp(true))
            val ssid = client.read(GoProUuids.WIFI_AP_SSID).decodeToString()
            val password = client.read(GoProUuids.WIFI_AP_PASSWORD).decodeToString()
            host.join(ssid, password)
            val media = host.mediaList()
            for (recording in pending) {
                val files = GoProMedia.filesFor(recording, media)
                if (files.isEmpty()) {
                    val started = utcText(recording.startAckUtcMillis)
                    problems += "No GoPro files found for the recording started at $started"
                    continue
                }
                val local = files.map { f ->
                    val name = localName(recording.sourceId, f.name)
                    if (host.size(name) != f.size) {
                        val index = downloaded.size + 1
                        host.download(f, name) { bytes, total -> onProgress(Progress(f.name, bytes, total, index)) }
                    }
                    downloaded += f.name
                    name
                }
                val result = host.import(recording, local)
                problems += result.problems
                // Done only when every file is in the session (audit BD-06): otherwise a later run
                // tries again, downloading anew a file that is not a readable MP4, whatever its length.
                val placed = result.clips.map { it.fileName }.toSet()
                val missing = local.filter { it !in placed }
                missing.filterNot { host.readable(it) }.forEach(host::delete)
                if (missing.isEmpty()) host.putRecording(recording.copy(downloaded = true))
            }
        } catch (e: IoProblem) {
            problems += "GoPro download stopped: ${e.message}"
        } finally {
            cleanUp(client)
        }
        return Result(downloaded, problems)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Best effort: the files are already safe.
    private suspend fun cleanUp(client: GoProClient) {
        try {
            host.leave()
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

/** [utcMillis] as ISO 8601 UTC to the second, "2026-10-28T12:00:05Z". */
@Suppress("MagicNumber") // The Gregorian calendar's own numbers.
internal fun utcText(utcMillis: Long): String {
    val seconds = utcMillis.floorDiv(MillisPerSecond)
    val days = seconds.floorDiv(SecondsPerDay)
    val ofDay = seconds - days * SecondsPerDay
    // Days to civil date (Howard Hinnant's days_from_civil, inverted).
    val z = days + DaysTo1970
    val era = z.floorDiv(DaysPerEra)
    val doe = z - era * DaysPerEra
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    fun two(v: Long) = v.toString().padStart(2, '0')
    return "$year-${two(month)}-${two(day)}T${two(ofDay / 3600)}:${two(ofDay / 60 % 60)}:${two(ofDay % 60)}Z"
}

private const val MillisPerSecond = 1000L
private const val SecondsPerDay = 86_400L
private const val DaysTo1970 = 719_468L
private const val DaysPerEra = 146_097L
