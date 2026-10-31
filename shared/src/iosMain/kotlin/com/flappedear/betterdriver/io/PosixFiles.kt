@file:OptIn(ExperimentalForeignApi::class)

package com.flappedear.betterdriver.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.posix.EINTR
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.flock
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.open
import platform.posix.read
import platform.posix.stat
import platform.posix.strerror
import platform.posix.write

private const val FileMode = 0x1A4 // 0644
private const val MillisPerSecond = 1_000L
private const val NanosPerMilli = 1_000_000L

/** A file system call failed; the message names the call, the path and errno's text. */
class FileError(message: String) : IOException(message)

private fun fail(what: String, path: String): Nothing =
    throw FileError("$what $path: ${strerror(errno)?.toKString() ?: "errno $errno"}")

/**
 * The file operations of the iOS session store (FBBD-332) and the shared stores (FBBD-359), on POSIX calls so that
 * writes, fsync and renames behave as the Android store's: data handed to write() survives an
 * app crash, fsync makes it survive a power loss, and rename replaces a file atomically.
 */
// One small wrapper per POSIX call the store uses; grouping them keeps the error handling in one place.
@Suppress("TooManyFunctions")
object PosixFiles {
    fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun name(path: String) = path.trimEnd('/').substringAfterLast('/')

    fun parent(path: String) = path.trimEnd('/').substringBeforeLast('/', ".")

    private fun mode(path: String): Int? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) null else st.st_mode.toInt() and S_IFMT
    }

    fun isFile(path: String) = mode(path) == S_IFREG

    fun isDirectory(path: String) = mode(path) == S_IFDIR

    fun mkdirs(path: String): Boolean =
        isDirectory(path) || NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)

    /** Names in [dir], or empty when it cannot be listed. */
    fun list(dir: String): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)?.map { it as String } ?: emptyList()

    fun deleteRecursively(path: String): Boolean = NSFileManager.defaultManager.removeItemAtPath(path, null)

    fun move(from: String, to: String): Boolean = platform.posix.rename(from, to) == 0

    fun size(path: String): Long = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) 0L else st.st_size
    }

    /** When the file at [path] was last changed, in UTC milliseconds; 0 when there is none. */
    fun lastModifiedMillis(path: String): Long = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) {
            0L
        } else {
            st.st_mtimespec.tv_sec * MillisPerSecond + st.st_mtimespec.tv_nsec / NanosPerMilli
        }
    }

    fun readBytes(path: String): ByteArray {
        val fd = open(path, O_RDONLY)
        if (fd < 0) fail("open", path)
        try {
            val size = memScoped {
                val st = alloc<stat>()
                if (fstat(fd, st.ptr) != 0) fail("fstat", path)
                st.st_size.toInt()
            }
            val out = ByteArray(size)
            var done = 0
            out.usePinned { pin ->
                while (done < size) {
                    val n = read(fd, pin.addressOf(done), (size - done).convert()).toInt()
                    when {
                        n > 0 -> done += n
                        n == 0 -> break
                        errno != EINTR -> fail("read", path)
                    }
                }
            }
            return if (done == size) out else out.copyOf(done)
        } finally {
            close(fd)
        }
    }

    /** Opens [path] for appending, created if missing; returns the descriptor. */
    fun openAppend(path: String): Int {
        val fd = open(path, O_WRONLY or O_CREAT or O_APPEND, FileMode)
        if (fd < 0) fail("open", path)
        return fd
    }

    /** Writes all of [bytes] to [fd]. */
    fun writeAll(fd: Int, bytes: ByteArray, path: String) {
        if (bytes.isEmpty()) return
        var done = 0
        bytes.usePinned { pin ->
            while (done < bytes.size) {
                val n = write(fd, pin.addressOf(done), (bytes.size - done).convert()).toInt()
                when {
                    n > 0 -> done += n
                    errno != EINTR -> fail("write", path)
                }
            }
        }
    }

    fun sync(fd: Int, path: String) {
        if (fsync(fd) != 0) fail("fsync", path)
    }

    /** Best effort: makes a create or rename in [dir] durable. */
    fun syncDir(dir: String) {
        val fd = open(dir, O_RDONLY)
        if (fd < 0) return
        fsync(fd)
        close(fd)
    }

    /** Writes [bytes] to [path] through a synced temp file and a rename, so readers never see half a file. */
    fun writeAtomically(path: String, bytes: ByteArray) {
        val tmp = "$path.tmp"
        val fd = open(tmp, O_WRONLY or O_CREAT or O_TRUNC, FileMode)
        if (fd < 0) fail("open", tmp)
        try {
            writeAll(fd, bytes, tmp)
            sync(fd, tmp)
        } finally {
            close(fd)
        }
        if (platform.posix.rename(tmp, path) != 0) fail("rename to $path", tmp)
        syncDir(parent(path))
    }

    /** Bytes free for this app on the volume holding [path]; Long.MAX_VALUE when not known. */
    fun usableBytes(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null) ?: return Long.MAX_VALUE
        return (attrs[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: Long.MAX_VALUE
    }

    /** Takes an exclusive lock on [path] for this process, or returns null when another holds it. */
    fun tryLock(path: String): Int? {
        val fd = open(path, O_WRONLY or O_CREAT, FileMode)
        if (fd < 0) fail("open", path)
        if (flock(fd, LOCK_EX or LOCK_NB) != 0) {
            close(fd)
            return null
        }
        return fd
    }

    fun unlock(fd: Int) {
        flock(fd, LOCK_UN)
        close(fd)
    }

    /**
     * True if a lock on [path] is held, by another process or by another open of it in this one
     * (flock locks belong to the open file, so a recording in this app counts).
     */
    fun isLocked(path: String): Boolean {
        val fd = if (isFile(path)) open(path, O_WRONLY) else -1
        if (fd < 0) return false
        val held = flock(fd, LOCK_EX or LOCK_NB) != 0
        if (!held) flock(fd, LOCK_UN)
        close(fd)
        return held
    }
}
