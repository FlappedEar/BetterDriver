@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.recorded.vboAccelerations
import com.flappedear.betterdriver.analysis.review.indexedClips
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExport
import com.flappedear.betterdriver.recorder.vbo.VboVideo
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.fwrite
import kotlinx.cinterop.CPointer

/** An export that could not be written; the message says why. */
class ExportException(message: String) : Exception(message)

/**
 * The iPhone's session exports (FBBD-333), the files Android's session card writes: the VBO, and VBO
 * with video for Circuit Tools (FBBD-25), a folder BetterDriver-<id> holding BetterDriver-<id>.vbo and
 * its video files BetterDriver-<id>_0001.MP4 and on. The same exporter writes the VBO on both (VboExport),
 * with the G columns FlappedEar Overlays reads (FBBD-216). Files are made under [outRoot], from where the
 * app hands them to Files, AirDrop or another app.
 */
class SessionExport(sessionsRoot: String, private val outRoot: String) {
    private val store = SessionStore(sessionsRoot)

    /** Session [id] has a whole video file to export with. */
    fun hasVideo(id: String): Boolean = try {
        clips(id).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** Writes the plain VBO of session [id]; returns its path. */
    @Throws(Exception::class)
    fun vbo(id: String): String {
        val dir = freshDir(id)
        val path = "$dir/${baseName(id)}.vbo"
        val data = store.read(id)
        TextFile(path).use { VboExport.export(data, it, derived = vboAccelerations(data)) }
        return path
    }

    /**
     * Writes session [id] as VBO with its video into a folder, as Android does; returns the folder's
     * path. Phone video is used when there is any, else the first camera with files. Without video
     * the folder holds the plain VBO.
     */
    @Throws(Exception::class)
    fun vboWithVideo(id: String): String {
        val folder = "${freshDir(id)}/${baseName(id)}"
        mkdirs(folder)
        val data = store.read(id)
        val clips = clips(id)
        val video = if (clips.isEmpty()) null else VboVideo("${baseName(id)}_", clips)
        TextFile("$folder/${baseName(id)}.vbo").use { VboExport.export(data, it, video, vboAccelerations(data)) }
        val videoDir = "${store.dir(id)}/${VideoFiles.DIR}"
        video?.orderedClips?.forEachIndexed { i, clip ->
            val to = "$folder/${video.fileName(i)}"
            if (!NSFileManager.defaultManager.copyItemAtPath("$videoDir/${clip.file}", to, null)) {
                throw ExportException("cannot copy ${clip.file}")
            }
        }
        return folder
    }

    /**
     * The clips of the camera the export uses, from the session's events and its clip index
     * (clips.json, which wins, as on Android); a file cut off before it was finished (no moov) is left out.
     */
    private fun clips(id: String): List<VideoClip> {
        val videoDir = "${store.dir(id)}/${VideoFiles.DIR}"
        val index = NSString.stringWithContentsOfFile("$videoDir/$ClipIndexFile", NSUTF8StringEncoding, null)
            ?.let(::indexedClips).orEmpty()
        val timeline = VideoTimeline.from(store.read(id, samples = false).events, index)
        val present = timeline.clips.filter { Mp4Files.isComplete("$videoDir/${it.file}") }
        val camera = present.firstOrNull { it.camera == CameraIds.PHONE }?.camera ?: present.firstOrNull()?.camera
        return present.filter { it.camera == camera }
    }

    /** An empty folder for this export of [id]: an earlier one is removed. */
    private fun freshDir(id: String): String {
        val dir = "$outRoot/$id"
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
        mkdirs(dir)
        return dir
    }

    private fun mkdirs(dir: String) {
        if (!NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)) {
            throw ExportException("cannot create $dir")
        }
    }

    private fun baseName(id: String) = "BetterDriver-$id"

    private companion object {
        /** The session's clip index in its video folder (Android's VideoIndex). */
        const val ClipIndexFile = "clips.json"
    }
}

/** Text written to a file as UTF-8 in 64 KB pieces, so a long session's VBO is never one string. */
private class TextFile(private val path: String) : Appendable, AutoCloseable {
    private val file: CPointer<FILE> = fopen(path, "wb") ?: throw ExportException("cannot create $path")
    private val buffer = StringBuilder()

    override fun append(value: Char): Appendable = apply {
        buffer.append(value)
        flushIfFull()
    }

    override fun append(value: CharSequence?): Appendable = apply {
        buffer.append(value)
        flushIfFull()
    }

    override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable = apply {
        buffer.append(value, startIndex, endIndex)
        flushIfFull()
    }

    private fun flushIfFull() {
        if (buffer.length >= FlushChars) flush()
    }

    private fun flush() {
        val bytes = buffer.toString().encodeToByteArray()
        buffer.clear()
        if (bytes.isEmpty()) return
        val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), file) }
        if (written.toLong() != bytes.size.toLong()) throw ExportException("cannot write $path")
    }

    override fun close() {
        try {
            flush()
        } finally {
            fclose(file)
        }
    }

    private companion object {
        const val FlushChars = 64 * 1024
    }
}

/** MP4 files as the recorder leaves them. */
@Suppress("MagicNumber") // The MP4 box header: 4-byte size, 4-byte type, 8-byte size when the first is 1.
internal object Mp4Files {
    private const val HeaderBytes = 16
    private const val ShortHeaderBytes = 8
    private const val Moov = "moov"

    /**
     * The file at [path] has its index (a top-level moov box), so it plays: a file whose camera was
     * cut off before it finished has none, as Mp4Reader.isComplete checks on Android.
     */
    fun isComplete(path: String): Boolean {
        val file = fopen(path, "rb") ?: return false
        try {
            var offset: Long? = 0L
            var found = false
            while (offset != null && !found) {
                val at: Long = offset
                val box = boxAt(file, at)
                found = box?.first == Moov
                offset = box?.second?.let { at + it }
            }
            return found
        } finally {
            fclose(file)
        }
    }

    /**
     * Type and size of the box at [offset]; null past the end or when unreadable. The size is null
     * when the box runs to the end of the file, so no box follows it.
     */
    private fun boxAt(file: CPointer<FILE>, offset: Long): Pair<String, Long?>? {
        val header = ByteArray(HeaderBytes)
        val read = if (fseeko(file, offset, SEEK_SET) == 0) {
            header.usePinned { fread(it.addressOf(0), 1u, HeaderBytes.convert(), file) }.toInt()
        } else {
            0
        }
        if (read < ShortHeaderBytes) return null
        val size = when (val size32 = uint(header, 0)) {
            0L -> null
            1L -> if (read < HeaderBytes) null else (uint(header, 8) shl 32) or uint(header, 12)
            else -> size32
        }
        return header.decodeToString(4, 8) to size?.takeIf { it >= ShortHeaderBytes }
    }

    private fun uint(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)
}
