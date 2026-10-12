package com.flappedear.betterdriver.video

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The clips of one session, kept in `<session>/video/clips.json`.
 *
 * It is separate from the recorder's files, which camera code never writes, so a camera bug
 * cannot damage session data (FBBD-69). Writes go to a synced temporary file that is renamed over
 * the old one, so a crash leaves the old or the new index, never half of one. An index that cannot
 * be read is moved aside rather than overwritten.
 *
 * Safe to use from several threads and several processes: the phone camera writes from its own
 * `:camera` process and GoPro import from the UI process. Changes hold a file lock on
 * `clips.json.lock` (and, within one process, a monitor), so no change is lost; reads need no lock
 * because the file is only ever replaced whole.
 */
class VideoIndex(private val sessionDir: File) {
    val videoDir = File(sessionDir, VideoDirName)
    private val file = File(videoDir, IndexFileName)

    fun clips(): List<VideoClip> = synchronized(lock) { read() }

    /** Adds [clip], replacing any clip with the same file name. */
    fun put(clip: VideoClip) = update { clips -> clips.filterNot { it.fileName == clip.fileName } + clip }

    fun putAll(newClips: List<VideoClip>) = update { clips ->
        val names = newClips.map { it.fileName }.toSet()
        clips.filterNot { it.fileName in names } + newClips
    }

    fun remove(fileName: String) = update { clips -> clips.filterNot { it.fileName == fileName } }

    /** The clip that has a frame at [sessionTimeUs], preferring the best alignment. */
    fun clipAt(sessionTimeUs: Long, kind: VideoSourceKind? = null): VideoClip? =
        clips()
            .filter { (kind == null || it.kind == kind) && it.status == ClipStatus.LOCAL && it.covers(sessionTimeUs) }
            .minByOrNull { it.alignment.method.ordinal }

    /**
     * Throws IOException when the session is gone: the phone camera can finish its last clip a few
     * seconds after the recording ends, and must not bring back the folder of a session deleted
     * meanwhile.
     */
    private fun update(change: (List<VideoClip>) -> List<VideoClip>) = synchronized(lock) {
        if (!sessionDir.isDirectory) throw IOException("${sessionDir.path} is gone")
        videoDir.mkdirs()
        RandomAccessFile(File(videoDir, IndexFileName + LockSuffix), "rw").channel.use { channel ->
            channel.lock().use {
                val updated = change(read()).sortedWith(compareBy({ it.kind }, { it.sessionOffsetUs }, { it.fileName }))
                writeAtomically(json.encodeToString(IndexFile.serializer(), IndexFile(FormatVersion, updated)))
            }
        }
    }

    private fun read(): List<VideoClip> {
        if (!file.isFile) return emptyList()
        return try {
            val index = json.decodeFromString(IndexFile.serializer(), file.readText())
            if (index.version > FormatVersion) throw SerializationException("newer index version ${index.version}")
            index.clips
        } catch (_: SerializationException) {
            setAside()
        } catch (_: IllegalArgumentException) {
            setAside()
        } catch (_: IOException) {
            setAside()
        }
    }

    private fun setAside(): List<VideoClip> {
        file.renameTo(File(videoDir, IndexFileName + UnreadableMarker + System.currentTimeMillis()))
        return emptyList()
    }

    private fun writeAtomically(text: String) {
        val tmp = File(videoDir, "$IndexFileName.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Serializable
    private data class IndexFile(val version: Int, val clips: List<VideoClip>)

    companion object {
        const val VideoDirName = "video"
        const val IndexFileName = "clips.json"
        private const val FormatVersion = 1
        private const val UnreadableMarker = ".unreadable-"
        private const val LockSuffix = ".lock"
        private val lock = Any()
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
