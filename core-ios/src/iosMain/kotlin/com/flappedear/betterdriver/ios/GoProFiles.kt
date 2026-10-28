@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.video.IoProblem
import com.flappedear.betterdriver.video.mp4.Mp4Source
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.open
import platform.posix.pread
import platform.posix.read
import platform.posix.stat
import platform.posix.strerror
import platform.posix.write

private const val FileMode = 0x1A4 // 0644
private const val CopyBuffer = 1 shl 20

/** The tag of GoPro import lines in the app's log. */
internal const val GoProTag = "GoProImport"

private fun problem(what: String, path: String): Nothing =
    throw IoProblem("$what ${path.substringAfterLast('/')}: ${strerror(errno)?.toKString() ?: "errno $errno"}", null)

/** A file on the iPhone as an [Mp4Source], read with POSIX pread: no Objective-C exceptions. */
internal class PosixMp4Source(private val path: String) : Mp4Source {
    private val fd = open(path, O_RDONLY).also { if (it < 0) problem("cannot open", path) }

    override val length: Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0) {
            close(fd)
            problem("cannot read the size of", path)
        }
        st.st_size
    }

    override fun read(offset: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = out.usePinned { pread(fd, it.addressOf(done), (size - done).convert(), offset + done).toInt() }
            when {
                n > 0 -> done += n
                n == 0 -> throw IoProblem("${path.substringAfterLast('/')} ends at ${offset + done}", null)
                errno != EINTR -> problem("cannot read", path)
            }
        }
        return out
    }

    override fun close() {
        close(fd)
    }
}

/**
 * Copies [from] to [to], synced to storage, reporting each chunk's bytes to [onBytes]. Throws
 * [IoProblem] when either side fails; [to] may then be left part-written.
 */
internal fun copyFile(from: String, to: String, onBytes: (Long) -> Unit) {
    val src = open(from, O_RDONLY)
    if (src < 0) problem("cannot open", from)
    try {
        val dst = open(to, O_WRONLY or O_CREAT or O_TRUNC, FileMode)
        if (dst < 0) problem("cannot create", to)
        try {
            copyAll(src, from, dst, to, onBytes)
        } finally {
            close(dst)
        }
    } finally {
        close(src)
    }
}

private fun copyAll(src: Int, from: String, dst: Int, to: String, onBytes: (Long) -> Unit) {
    val buffer = ByteArray(CopyBuffer)
    var n = readSome(src, buffer, from)
    while (n > 0) {
        writeAll(dst, buffer, n, to)
        onBytes(n.toLong())
        n = readSome(src, buffer, from)
    }
    if (fsync(dst) != 0) problem("cannot sync", to)
}

/** Up to [buffer]'s size of bytes from [fd]; 0 at the end of the file. */
private fun readSome(fd: Int, buffer: ByteArray, path: String): Int {
    var n: Int
    do {
        n = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()).toInt() }
    } while (n < 0 && errno == EINTR)
    if (n < 0) problem("cannot read", path)
    return n
}

/** Writes [bytes] to [path] through a synced temporary file renamed over it. */
internal fun writeBytesAtomically(path: String, bytes: ByteArray) {
    val tmp = "$path.tmp"
    val fd = open(tmp, O_WRONLY or O_CREAT or O_TRUNC, FileMode)
    if (fd < 0) problem("cannot create", tmp)
    try {
        writeAll(fd, bytes, bytes.size, tmp)
        if (fsync(fd) != 0) problem("cannot sync", tmp)
    } finally {
        close(fd)
    }
    if (platform.posix.rename(tmp, path) != 0) problem("cannot replace", path)
}

private fun writeAll(fd: Int, bytes: ByteArray, size: Int, path: String) {
    var done = 0
    while (done < size) {
        val n = bytes.usePinned { write(fd, it.addressOf(done), (size - done).convert()).toInt() }
        if (n < 0) {
            if (errno == EINTR) continue
            problem("cannot write", path)
        }
        done += n
    }
}
