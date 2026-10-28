package com.flappedear.betterdriver.gopro

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** `<session>/video/gopro-recordings.json`: Open GoPro recordings of a session, of any number of cameras. */
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
