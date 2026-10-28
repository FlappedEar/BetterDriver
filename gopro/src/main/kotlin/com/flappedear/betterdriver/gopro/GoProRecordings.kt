package com.flappedear.betterdriver.gopro

import java.io.File
import java.io.IOException

/**
 * `<session>/video/gopro-recordings.json` ([GoProRecordingsJson]): Open GoPro recordings of a
 * session, of any number of cameras.
 */
class GoProRecordings(private val videoDir: File) {
    private val file = File(videoDir, FileName)

    fun all(): List<GoProRecording> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        try {
            GoProRecordingsJson.decode(file.readText())
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** Adds or replaces the recording with the same source and start. */
    fun put(recording: GoProRecording) = synchronized(lock) {
        val all = GoProRecordingsJson.put(all(), recording)
        videoDir.mkdirs()
        val tmp = File(videoDir, "$FileName.tmp")
        tmp.outputStream().use { out ->
            out.write(GoProRecordingsJson.encode(all).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("could not replace $file")
    }

    companion object {
        const val FileName = GoProRecordingsJson.FileName
        private val lock = Any()
    }
}
