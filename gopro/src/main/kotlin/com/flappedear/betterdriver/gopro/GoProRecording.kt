package com.flappedear.betterdriver.gopro

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * What the app knows about a HERO11 recording made with a session, kept until its files are
 * downloaded: when the camera acknowledged start and stop on the session clock and on the phone's
 * wall clock, and the UTC offset the camera's clock was set to.
 */
@Serializable
data class GoProRecording(
    val sourceId: String,
    val serial: String = "",
    val startAckSessionUs: Long,
    val startAckUtcMillis: Long,
    val stopAckSessionUs: Long? = null,
    val stopAckUtcMillis: Long? = null,
    /** The UTC offset the app set on the camera's clock, or null if setting the clock failed. */
    val cameraUtcOffsetMinutes: Int? = null,
    /** The video mode the app set ("1080p · 60"), or null if the camera kept its own (FBBD-88). */
    val videoMode: String? = null,
    val downloaded: Boolean = false,
)

/** `<session>/video/gopro-recordings.json`: HERO11 recordings of a session. */
class GoProRecordings(private val videoDir: File) {
    private val file = File(videoDir, FileName)

    fun all(): List<GoProRecording> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        try {
            json.decodeFromString<List<GoProRecording>>(file.readText())
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** Adds or replaces the recording with the same source and start. */
    fun put(recording: GoProRecording) = synchronized(lock) {
        val others = all().filterNot {
            it.sourceId == recording.sourceId && it.startAckSessionUs == recording.startAckSessionUs
        }
        videoDir.mkdirs()
        val tmp = File(videoDir, "$FileName.tmp")
        tmp.outputStream().use { out ->
            out.write(json.encodeToString(others + recording).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("could not replace $file")
    }

    companion object {
        const val FileName = "gopro-recordings.json"
        private val lock = Any()
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}
