@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.flappedear.betterdriver.transfer.foreign

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionWriter
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.track.Track
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.timeIntervalSince1970
import platform.posix.memcpy

/** A file that cannot be imported: not a known format, damaged, empty or too large. The message is for the user. */
actual class ImportFormatException actual constructor(message: String, cause: Throwable?) : Exception(message, cause)

private const val SecondsPerDay = 86_400L
private const val Gzip0 = 0x1f
private const val Gzip1 = 0x8b
private const val ByteMask = 0xFF
private const val BytesPerMegabyte = 1L shl 20

/**
 * Turns a VBO or RaceChrono CSV file into an ordinary session in the iOS session folder
 * [sessionsRoot] (FBBD-331), as SessionImport does on Android: the same common reader and
 * session writer, so the session is the one Android would make from the file. It is built in
 * [stagingRoot] (on the same volume) and moved into [sessionsRoot] with one rename only when
 * complete, so a failed import leaves nothing behind; its id is new and never one already there.
 *
 * Not on iOS yet: GPX (Android reads it with SAX) and gzip-compressed files; both are refused
 * with a message saying so. Runs on the caller's thread: call it off the main thread.
 */
class ForeignInbox(private val sessionsRoot: String, private val stagingRoot: String) {
    private val fm get() = NSFileManager.defaultManager

    /**
     * Imports the file at [path], shown as [fileName]. [tracks] and [directory] are used for track
     * detection, as on Android; [onProgress] gets the bytes read so far and the file's size.
     * Throws [ImportFormatException] for a file that cannot be imported.
     */
    @Throws(Exception::class)
    fun importFile(
        path: String,
        fileName: String,
        tracks: List<Track>,
        directory: CircuitList,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
    ): ImportedSession = try {
        importNow(path, fileName, tracks, directory, onProgress)
    } catch (e: IllegalArgumentException) {
        throw ImportFormatException("the file has values BetterDriver cannot store: ${e.message}", e)
    }

    private fun importNow(
        path: String,
        fileName: String,
        tracks: List<Track>,
        directory: CircuitList,
        onProgress: (read: Long, total: Long) -> Unit,
    ): ImportedSession {
        val total = sizeOf(path)
        val format = formatOf(FileBytes(path, total) { _, _ -> }.use { it.head(ForeignFormat.PeekBytes) })
        fm.createDirectoryAtPath(stagingRoot, true, null, null)
        val root = "$stagingRoot/import-${NSUUID().UUIDString}"
        val files = IosSessionFiles(SessionStore(root))
        val writer = ForeignSessionWriter(files, ImportOrigin(format.label, fileName), tracks, directory)
        try {
            FileBytes(path, total, onProgress).use { input ->
                val chars = Utf8Chars(input::read)
                readText(format, LineReader(chars::read), writer, todayEpochDay())
            }
            val made = writer.finish(format)
            add("$root/${made.id}", made.id)
            return made
        } finally {
            writer.abort()
            fm.removeItemAtPath(root, null)
        }
    }

    /** Moves the finished session [staged] into the list with one rename; its id is never one already there. */
    private fun add(staged: String, id: String) {
        fm.createDirectoryAtPath(sessionsRoot, true, null, null)
        val target = "$sessionsRoot/$id"
        if (fm.fileExistsAtPath(target)) throw ImportFormatException("a session $id is already here")
        if (platform.posix.rename(staged, target) != 0) throw ImportFormatException("could not add the session")
    }

    /** The staged session's files, through the iOS store's own [SessionWriter]. */
    private class IosSessionFiles(private val staged: SessionStore) : ForeignSessionFiles {
        private var writer: SessionWriter? = null

        override fun create(info: SessionInfo): String = SessionWriter(staged, info).also { writer = it }.id

        override fun open(channels: List<ChannelDef>) = checkNotNull(writer).open(channels)

        override fun write(record: Record) = checkNotNull(writer).write(record)

        override fun writeMeta(id: String, info: SessionInfo) = checkNotNull(writer).writeMeta(info)

        override fun close() = checkNotNull(writer).close()

        override fun writeFinished(id: String, reason: String, endMicros: Long) =
            checkNotNull(writer).writeFinished(reason, endMicros)

        override fun abort() {
            writer?.release()
        }
    }
}

/**
 * The format of a file from its first bytes [head]; refuses an empty file, a gzip-compressed one
 * and GPX (not on iOS yet) before anything is staged.
 */
@Suppress("ThrowsCount") // One message for each file that cannot be imported here.
private fun formatOf(head: ByteArray): ForeignFormat {
    val gzip = head.size >= 2 && head[0].toInt() and ByteMask == Gzip0 && head[1].toInt() and ByteMask == Gzip1
    val format = when {
        head.isEmpty() -> throw ImportFormatException("the file is empty")
        gzip -> throw ImportFormatException("compressed files cannot be imported on this device yet: unpack it first")
        else -> ForeignFormat.detect(head)
    }
    return when (format) {
        null -> throw ImportFormatException(
            "this is not a file BetterDriver can import (a BetterDriver session, VBO or RaceChrono CSV)",
        )
        ForeignFormat.GPX -> throw ImportFormatException("GPX files cannot be imported on this device yet")
        else -> format
    }
}

/** Today's date in UTC as days since 1970-01-01, for a VBO file without a date line (as on Android). */
private fun todayEpochDay(): Long = NSDate().timeIntervalSince1970.toLong().floorDiv(SecondsPerDay)

private fun sizeOf(path: String): Long =
    (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
        ?: throw ImportFormatException("cannot read the file")

/**
 * The file at [path] read in pieces: [onProgress] gets the bytes read so far and [total]; reading
 * fails past [ImportLimits.MaxBytes], as on Android.
 */
private class FileBytes(path: String, private val total: Long, private val onProgress: (Long, Long) -> Unit) {
    private val handle = NSFileHandle.fileHandleForReadingAtPath(path)
        ?: throw ImportFormatException("cannot open the file")
    private var count = 0L

    /** Fills [buf] from [offset] with up to [length] (at least 1) bytes; returns how many, or -1 at the end. */
    fun read(buf: ByteArray, offset: Int, length: Int): Int {
        // Its own autorelease pool, as pump (FBBD-393): a large file's pieces must not pile up.
        val n = autoreleasepool {
            val data = handle.readDataOfLength(length.toULong())
            if (data.length > 0u) buf.usePinned { memcpy(it.addressOf(offset), data.bytes, data.length) }
            data.length.toInt()
        }
        if (n == 0) return -1
        count += n
        if (count > ImportLimits.MaxBytes) {
            throw ImportFormatException("the file is too large (over ${ImportLimits.MaxBytes / BytesPerMegabyte} MB)")
        }
        onProgress(count, total)
        return n
    }

    /** Up to the first [size] bytes. */
    fun head(size: Int): ByteArray {
        val buf = ByteArray(size)
        var n = 0
        while (n < size) {
            val r = read(buf, n, size - n)
            if (r <= 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    fun <T> use(block: (FileBytes) -> T): T = try {
        block(this)
    } finally {
        handle.closeFile()
    }
}
