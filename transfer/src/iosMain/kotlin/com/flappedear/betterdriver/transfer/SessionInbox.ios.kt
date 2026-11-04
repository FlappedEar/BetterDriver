@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.iosTrackStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fsync
import platform.posix.fwrite

private const val ReadBytes = 1 shl 16
private const val OldPrefix = "old-"
private const val InPrefix = "in-"

/** A file operation of the import failed (the device, not the bundle). */
class ImportFileException(message: String) : RuntimeException(message)

/** A session unpacked from a bundle into staging and checked; see [SessionInbox.receive]. */
class ReceivedBundle internal constructor(val session: ReceivedSession, internal val staged: String) {
    internal val sessionDir get() = "$staged/session"
}

/**
 * Brings session bundles into the iOS session folder [sessionsRoot] (FBBD-331), as SessionInbox
 * does on Android: a bundle is unpacked into [stagingRoot] and checked; only a complete, undamaged
 * session is then moved into [sessionsRoot] with one rename, so a failed import never leaves half a
 * session in the list. [stagingRoot] must be on the same volume. The track a session was timed with
 * is kept in [tracksRoot] by the shared TrackStore.
 */
class SessionInbox(
    private val sessionsRoot: String,
    private val stagingRoot: String,
    private val tracksRoot: String,
) {
    private val fm get() = NSFileManager.defaultManager
    private val tracks = TrackFiles(tracksRoot)

    /**
     * Unpacks and checks the bundle at [path]. [onProgress] gets the bytes read so far and the
     * bundle's size. On any error the staged files are removed.
     */
    // Any failure removes the staged files and is passed on as it is.
    @Suppress("TooGenericExceptionCaught")
    @Throws(Exception::class)
    fun receive(path: String, onProgress: (read: Long, total: Long) -> Unit = { _, _ -> }): ReceivedBundle {
        val total = sizeOf(path)
        mkdirs(stagingRoot)
        val staged = "$stagingRoot/$InPrefix${NSUUID().UUIDString}"
        mkdirs(staged)
        val files = Staging("$staged/session", stagingRoot)
        try {
            val receiver = SessionBundleReceiver(files)
            val unpacker = BundleUnpacker(receiver)
            pump(path) { bytes, n ->
                unpacker.feed(bytes, 0, n)
                onProgress(unpacker.bytesRead, total)
            }
            unpacker.finish()
            return ReceivedBundle(receiver.result(), staged)
        } catch (e: Exception) {
            files.abandon()
            fm.removeItemAtPath(staged, null)
            throw e
        }
    }

    /** A session with the same id is already here, so [accept] needs a keep-or-replace choice. */
    fun exists(received: ReceivedBundle): Boolean = fm.fileExistsAtPath(target(received))

    /**
     * The bundle's track has the id of a saved track with other lines or corners, so [accept] needs
     * a keep-or-replace choice for it too, as Android's import asks.
     */
    fun trackDiffers(received: ReceivedBundle): Boolean {
        val track = received.session.track ?: return false
        return trackMatch(track, tracks.saved(track.id)) == TrackMatch.DIFFERENT
    }

    /**
     * Moves the received session into the list and keeps its track. With a session of the same id
     * here, [replace] false keeps the one here; true swaps them. A track of the same id is kept
     * unless [replaceTrack]. Returns whether the session was added. A session still recording is
     * never replaced.
     */
    @Throws(Exception::class)
    fun accept(received: ReceivedBundle, replace: Boolean, replaceTrack: Boolean = false): Boolean {
        val target = target(received)
        val here = fm.fileExistsAtPath(target)
        try {
            when {
                !here -> move(received.sessionDir, target)
                replace -> swap(received.sessionDir, target)
            }
            if (!here || replace) tracks.keep(received.session, replaceTrack)
        } finally {
            discard(received)
        }
        return !here || replace
    }

    /** Drops a received session that will not be added. */
    fun discard(received: ReceivedBundle) {
        fm.removeItemAtPath(received.staged, null)
    }

    /**
     * Cleans up after an app killed mid-import; not while importing. A session moved aside by a
     * replace goes back if its replacement never arrived. Returns whether one went back.
     */
    fun clearStaging(): Boolean {
        var restored = false
        val names = fm.contentsOfDirectoryAtPath(stagingRoot, null)?.filterIsInstance<String>().orEmpty()
        for (name in names) {
            val id = name.removePrefix(OldPrefix)
            val target = "$sessionsRoot/$id"
            if (name.startsWith(OldPrefix) && SessionEntryLimits.validSessionId(id) && !fm.fileExistsAtPath(target)) {
                restored = fm.moveItemAtPath("$stagingRoot/$name", target, null) || restored
            } else {
                fm.removeItemAtPath("$stagingRoot/$name", null)
            }
        }
        return restored
    }

    private fun target(received: ReceivedBundle) = "$sessionsRoot/${received.session.sessionId}"

    private fun swap(incoming: String, target: String) {
        // A lock does not stop a rename: the recorder would go on writing into a deleted folder.
        if (SessionStore(sessionsRoot).state(target) == SessionState.RECORDING) {
            throw ImportFileException("${target.substringAfterLast('/')} is still recording")
        }
        val old = "$stagingRoot/$OldPrefix${target.substringAfterLast('/')}"
        move(target, old)
        try {
            move(incoming, target)
        } catch (e: ImportFileException) {
            move(old, target)
            throw e
        }
        fm.removeItemAtPath(old, null)
    }

    private fun move(from: String, to: String) {
        mkdirs(to.substringBeforeLast('/'))
        if (!fm.moveItemAtPath(from, to, null)) throw ImportFileException("could not move $from to $to")
    }

    /** The staged session folder, written one file at a time. */
    private inner class Staging(private val dir: String, private val volume: String) : StagingFiles {
        private var out: FileWriter? = null

        override fun create(path: String) {
            val full = "$dir/$path"
            mkdirs(full.substringBeforeLast('/'))
            out = FileWriter(full)
        }

        override fun write(bytes: ByteArray, from: Int, to: Int) {
            checkNotNull(out).write(bytes, from, to)
        }

        override fun close() {
            val file = out
            out = null
            file?.close()
        }

        fun abandon() {
            val file = out
            out = null
            try {
                file?.close(sync = false)
            } catch (_: ImportFileException) {
                // Abandoned: its files are removed next.
            }
        }

        override fun freeBytes(): Long {
            val attributes = fm.attributesOfFileSystemForPath(volume, null)
            return (attributes?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: Long.MAX_VALUE
        }
    }
}

internal fun mkdirs(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}

internal fun sizeOf(path: String): Long =
    (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
        ?: throw ImportFileException("cannot read $path")

/** The saved tracks in [root], kept by the TrackStore Android uses (FBBD-359). */
private class TrackFiles(root: String) {
    private val store = iosTrackStore(root)

    @Suppress("TooGenericExceptionCaught") // A track that cannot be read is none, never a crash in Swift.
    fun saved(id: String): Track? = try {
        if (SessionEntryLimits.validSessionId(id)) store.get(id) else null
    } catch (_: Exception) {
        null
    }

    /**
     * Keeps the session's track if none of its id is saved, or over the saved one with [replace], as
     * Android's import stores it: the next revision, with the driver's corner corrections if it
     * carries them (FBBD-179).
     */
    fun keep(session: ReceivedSession, replace: Boolean) {
        val track = session.track ?: return
        if (!SessionEntryLimits.validSessionId(track.id)) return
        if (replace || store.get(track.id) == null) {
            store.save(track, adoptIdentity = carriesCorrectionsOf(track))
        }
    }
}

/**
 * Reads the file at [path] in pieces, handing each to [block] with its length. One buffer is read
 * into with stdio and handed out again for every piece, which [block] must not keep. No NSData is
 * made per piece: Kotlin lets those go only when its collector runs, which the small Kotlin objects
 * around them did not make it do, so a session's video filled the iPhone's memory until iOS killed
 * the app (FBBD-393).
 */
internal fun pump(path: String, block: (ByteArray, Int) -> Unit) {
    val input = fopen(path, "rb") ?: throw ImportFileException("cannot open $path")
    val buffer = ByteArray(ReadBytes)
    try {
        // A short read is the end of the file, or an error ferror tells apart.
        var n = ReadBytes
        while (n == ReadBytes) {
            n = buffer.usePinned { fread(it.addressOf(0), 1u, ReadBytes.toULong(), input) }.toInt()
            if (n > 0) block(buffer, n)
        }
        if (ferror(input) != 0) throw ImportFileException("cannot read $path")
    } finally {
        fclose(input)
    }
}

/** A new file at [path], written from Kotlin's bytes with stdio, as [pump] reads (FBBD-393). */
internal class FileWriter(private val path: String) {
    private var file: CPointer<FILE>? = fopen(path, "wb") ?: throw ImportFileException("cannot create $path")

    fun write(bytes: ByteArray, from: Int, to: Int) {
        if (to <= from) return
        val out = file ?: throw ImportFileException("$path is closed")
        val n = bytes.usePinned { fwrite(it.addressOf(from), 1u, (to - from).toULong(), out) }.toInt()
        if (n != to - from) throw ImportFileException("cannot write $path")
    }

    /** Closes the file, on the disk first with [sync]; a second close does nothing. */
    fun close(sync: Boolean = true) {
        val out = file ?: return
        file = null
        val flushed = !sync || (fflush(out) == 0 && fsync(fileno(out)) == 0)
        if (fclose(out) != 0 || !flushed) throw ImportFileException("cannot write $path")
    }
}
