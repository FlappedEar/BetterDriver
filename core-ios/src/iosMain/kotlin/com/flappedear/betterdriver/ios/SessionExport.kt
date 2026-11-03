@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.recorded.vboAccelerations
import com.flappedear.betterdriver.analysis.review.indexedClips
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.recorder.export.CsvWriter
import com.flappedear.betterdriver.recorder.export.ExportFormat
import com.flappedear.betterdriver.recorder.export.ExportLap
import com.flappedear.betterdriver.recorder.export.ExportShare
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
import com.flappedear.betterdriver.video.IoProblem
import com.flappedear.betterdriver.video.mp4.Mp4Parser
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/** An export that could not be written; the message says why. */
class ExportException(message: String) : Exception(message)

/**
 * The iPhone's session exports (FBBD-333), the files Android's session card writes: the VBO, and VBO
 * with video for Circuit Tools (FBBD-25), a folder BetterDriver-<id> holding BetterDriver-<id>.vbo and
 * its video files BetterDriver-<id>_0001.MP4 and on. The same exporter writes the VBO on both (VboExport),
 * with the G columns FlappedEar Overlays reads (FBBD-216). CSV and GPX are written as Android writes
 * them, with the laps at the session's saved track in [tracksRoot]. Files are made under [outRoot], from where the
 * app hands them to the share sheet ([share], FBBD-443), as Share log.
 */
class SessionExport(
    private val sessionsRoot: String,
    private val tracksRoot: String,
    private val outRoot: String,
) {
    private val store = SessionStore(sessionsRoot)

    /** Session [id] has a whole video file to export with. */
    fun hasVideo(id: String): Boolean = try {
        wholeVideoClips(store, id).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /**
     * Writes session [id] as [format] and returns what the share sheet gets (FBBD-443): the one file,
     * or for VBO + video the VBO and each video file next to it, as the shared [ExportShare] lists them.
     */
    @Throws(Exception::class)
    fun share(id: String, format: ExportFormat): ExportShare = when (format) {
        ExportFormat.Vbo -> ExportShare.of(id, format, vbo(id))
        ExportFormat.Csv -> ExportShare.of(id, format, csv(id))
        ExportFormat.Gpx -> ExportShare.of(id, format, gpx(id))
        ExportFormat.VboVideo -> {
            val folder = vboWithVideo(id)
            val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(folder, null)
                ?.filterIsInstance<String>().orEmpty()
            val videos = names.filter { it.startsWith(ExportShare.videoPrefix(id)) }.sorted().map { "$folder/$it" }
            ExportShare.of(id, format, "$folder/${ExportShare.fileName(id, format)}", videos)
        }
    }

    /** Removes session [id]'s waiting export, so its video links do not keep a deleted session's video. */
    fun forget(id: String) {
        NSFileManager.defaultManager.removeItemAtPath("$outRoot/$id", null)
    }

    /** Writes the plain VBO of session [id]; returns its path. */
    @Throws(Exception::class)
    fun vbo(id: String): String {
        val dir = freshDir(id)
        val path = "$dir/${ExportShare.fileName(id, ExportFormat.Vbo)}"
        val data = store.read(id)
        TextFile(path).use { VboExport.export(data, it, derived = vboAccelerations(data)) }
        return path
    }

    /** Writes session [id] as CSV, with lap columns at a saved track, as Android does; returns its path. */
    @Throws(Exception::class)
    fun csv(id: String): String {
        val path = "${freshDir(id)}/${ExportShare.fileName(id, ExportFormat.Csv)}"
        val data = store.read(id)
        val time = IsoTimes()
        TextFile(path).use { CsvWriter.write(data, it, laps(data), time::local) }
        return path
    }

    /** Writes session [id] as GPX 1.1, one segment per lap at a saved track, as Android does; returns its path. */
    @Throws(Exception::class)
    fun gpx(id: String): String {
        val path = "${freshDir(id)}/${ExportShare.fileName(id, ExportFormat.Gpx)}"
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
        val folder = "${freshDir(id)}/${ExportShare.baseName(id)}"
        mkdirs(folder)
        val data = store.read(id)
        val clips = wholeVideoClips(store, id)
        val video = if (clips.isEmpty()) null else VboVideo(ExportShare.videoPrefix(id), clips)
        TextFile("$folder/${ExportShare.fileName(id, ExportFormat.VboVideo)}").use {
            VboExport.export(data, it, video, vboAccelerations(data))
        }
        val videoDir = "${store.dir(id)}/${VideoFiles.DIR}"
        video?.orderedClips?.forEachIndexed { i, clip ->
            val to = "$folder/${video.fileName(i)}"
            val from = "$videoDir/${clip.file}"
            // A hard link: the same file under the export's name, no second copy of the video on the phone.
            val files = NSFileManager.defaultManager
            if (!files.linkItemAtPath(from, to, null) && !files.copyItemAtPath(from, to, null)) {
                throw ExportException("cannot copy ${clip.file}")
            }
        }
        return folder
    }

    /**
     * An empty folder for this export of [id]: an earlier one of [id] is removed, never another
     * session's, which may still be written or shared. Deleting a session removes its export ([forget]).
     */
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

}

/** Bytes of the files of [share], for its log line (FBBD-443). */
fun exportedBytes(share: ExportShare): Long = share.files.sumOf { path ->
    try {
        PosixMp4Source(path).use { it.length }
    } catch (_: IoProblem) {
        0L
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

    /** Writes the rest; fclose writes C's own buffer, so its failure (a full disk) fails the export too. */
    override fun close() {
        val flushed = runCatching { flush() }
        val closed = fclose(file) == 0
        flushed.getOrThrow()
        if (!closed) throw ExportException("cannot write $path")
    }

    private companion object {
        const val FlushChars = 64 * 1024
    }
}

/**
 * The clips of the camera session [id]'s video uses, from its events and its clip index (clips.json,
 * which wins, as on Android); a file cut off before it was finished (no moov) is left out. Empty: the
 * session has no video to play or export (FBBD-426).
 */
internal fun wholeVideoClips(store: SessionStore, id: String): List<VideoClip> {
    val videoDir = "${store.dir(id)}/${VideoFiles.DIR}"
    val index = NSString.stringWithContentsOfFile("$videoDir/$SessionClipIndexFile", NSUTF8StringEncoding, null)
        ?.let(::indexedClips).orEmpty()
    val timeline = VideoTimeline.from(store.read(id, samples = false).events, index)
    val present = timeline.clips.filter { Mp4Files.isComplete("$videoDir/${it.file}") }
    val camera = present.firstOrNull { it.camera == CameraIds.PHONE }?.camera ?: present.firstOrNull()?.camera
    return present.filter { it.camera == camera }
}

/**
 * Whether each session has a whole video file ([wholeVideoClips]), remembered until the files in its
 * video folder change (their names and sizes): the session list asks on every reload, and finding a
 * session's clips reads its recording for the video events. A session with no file in its video
 * folder is answered without reading anything. Thread-safe.
 */
internal class VideoPresence(private val store: SessionStore) {
    private val lock = NSLock()
    private val known = HashMap<String, Pair<String, Boolean>>()

    fun has(id: String): Boolean {
        val key = filesKey(id) ?: return false
        lock.lock()
        val cached = known[id]?.takeIf { it.first == key }?.second
        lock.unlock()
        return cached ?: read(id).also { has ->
            lock.lock()
            known[id] = key to has
            lock.unlock()
        }
    }

    /** The session's video files with their sizes, "name:size|…"; null when it has none. */
    private fun filesKey(id: String): String? {
        val dir = "${store.dir(id)}/${VideoFiles.DIR}"
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)
            ?.filterIsInstance<String>().orEmpty().filter { it != SessionClipIndexFile }.sorted()
        return names.takeIf { it.isNotEmpty() }?.joinToString("|") { "$it:${sizeOf("$dir/$it")}" }
    }

    private fun read(id: String): Boolean = try {
        wholeVideoClips(store, id).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    private fun sizeOf(path: String): Long = try {
        PosixMp4Source(path).use { it.length }
    } catch (_: IoProblem) {
        -1L
    }
}

/** MP4 files as the recorder leaves them. */
internal object Mp4Files {
    /**
     * The file at [path] has its index (a top-level moov box that fits in the file), so it plays: a
     * file whose camera was cut off before it finished has none. The shared [Mp4Parser.isComplete],
     * as Mp4Reader.isComplete on Android (audit BD-07: a moov header alone is not enough).
     */
    fun isComplete(path: String): Boolean = try {
        PosixMp4Source(path).use { Mp4Parser.isComplete(it) }
    } catch (_: IoProblem) {
        false
    }
}

/** A session's clip index in its video folder (Android's VideoIndex). */
private const val SessionClipIndexFile = "clips.json"
