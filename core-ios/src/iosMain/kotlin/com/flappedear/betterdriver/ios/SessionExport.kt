@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.recorded.vboAccelerations
import com.flappedear.betterdriver.analysis.review.indexedClips
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.recorder.export.CsvWriter
import com.flappedear.betterdriver.recorder.export.ExportLap
import com.flappedear.betterdriver.recorder.export.GpxWriter
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExport
import com.flappedear.betterdriver.recorder.vbo.VboVideo
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.timing.replay.replayLaps
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.fwrite

/** An export that could not be written; the message says why. */
class ExportException(message: String) : Exception(message)

/**
 * The iPhone's session exports (FBBD-333), the files Android's session card writes: the VBO, and VBO
 * with video for Circuit Tools (FBBD-25), a folder BetterDriver-<id> holding BetterDriver-<id>.vbo and
 * its video files BetterDriver-<id>_0001.MP4 and on. The same exporter writes the VBO on both (VboExport),
 * with the G columns FlappedEar Overlays reads (FBBD-216). CSV and GPX are written as Android writes
 * them, with the laps at the session's saved track in [tracksRoot]. Files are made under [outRoot], from where the
 * app hands them to Files, AirDrop or another app.
 */
class SessionExport(
    private val sessionsRoot: String,
    private val tracksRoot: String,
    private val outRoot: String,
) {
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

    /** Writes session [id] as CSV, with lap columns at a saved track, as Android does; returns its path. */
    @Throws(Exception::class)
    fun csv(id: String): String {
        val path = "${freshDir(id)}/${baseName(id)}.csv"
        val data = store.read(id)
        val time = IsoTimes()
        TextFile(path).use { CsvWriter.write(data, it, laps(data), time::local) }
        return path
    }

    /** Writes session [id] as GPX 1.1, one segment per lap at a saved track, as Android does; returns its path. */
    @Throws(Exception::class)
    fun gpx(id: String): String {
        val path = "${freshDir(id)}/${baseName(id)}.gpx"
        val data = store.read(id)
        val time = IsoTimes()
        TextFile(path).use { GpxWriter.write(data, it, laps(data), time::utc) }
        return path
    }

    /**
     * The laps of [data] at its saved track, as Android's export finds them; none away from a saved
     * track, and a damaged track only costs the lap columns, not the export.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun laps(data: SessionData): List<ExportLap> = try {
        val track = trackFor(data, TrackLibrary(tracksRoot, sessionsRoot).list())
        track?.let { replayLaps(data.id, data.sampleSets.sortedBy { s -> s.tMicros }, it).exportLaps() }.orEmpty()
    } catch (_: Exception) {
        emptyList()
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

/**
 * ISO-8601 times with milliseconds, as Android's exports write them: [local] in this device's time
 * zone with its offset (2026-10-13T09:11:14.495+02:00), [utc] in UTC (2026-10-13T07:11:14.495Z).
 * The milliseconds are written from the integer time, so they are never a millisecond off.
 */
private class IsoTimes {
    private val localSeconds = formatter("yyyy-MM-dd'T'HH:mm:ss", NSTimeZone.localTimeZone)
    private val localOffset = formatter("xxx", NSTimeZone.localTimeZone)
    private val utcSeconds = formatter("yyyy-MM-dd'T'HH:mm:ss", checkNotNull(NSTimeZone.timeZoneForSecondsFromGMT(0)))

    fun local(utcMillis: Long): String {
        val date = date(utcMillis)
        return "${localSeconds.stringFromDate(date)}.${millis(utcMillis)}${localOffset.stringFromDate(date)}"
    }

    fun utc(utcMillis: Long): String = "${utcSeconds.stringFromDate(date(utcMillis))}.${millis(utcMillis)}Z"

    private fun date(utcMillis: Long) =
        NSDate.dateWithTimeIntervalSince1970(utcMillis.floorDiv(MillisPerSecond).toDouble())

    private fun millis(utcMillis: Long) = utcMillis.mod(MillisPerSecond).toString().padStart(MillisDigits, '0')

    private fun formatter(format: String, zone: NSTimeZone) = NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        timeZone = zone
        dateFormat = format
    }

    private companion object {
        const val MillisPerSecond = 1_000L
        const val MillisDigits = 3
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
