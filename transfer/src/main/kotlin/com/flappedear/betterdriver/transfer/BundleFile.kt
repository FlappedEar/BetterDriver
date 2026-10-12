package com.flappedear.betterdriver.transfer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UTFDataFormatException
import java.util.zip.CRC32

/**
 * The container of a session bundle: a list of files, streamed in one pass, each with its CRC32.
 *
 * ```
 * "BDBUNDLE" (8 bytes ASCII), version (int)
 * per file:  1 (byte), path (modified UTF-8), size (long), the bytes, CRC32 (int)
 * end:       0 (byte), number of files (int)
 * ```
 *
 * Sizes are known before a file is written, so neither side needs to seek, files of any size work
 * (no 4 GB limit) and video is stored as is. A file cut short or changed in transit is refused.
 * A backup (FBBD-156) uses the same container with its own magic, "BDBACKUP", so it is never taken
 * for a session.
 */
internal object BundleFile {
    val Magic = "BDBUNDLE".toByteArray(Charsets.US_ASCII)
    const val Version = 1
    const val FileTag = 1
    const val EndTag = 0
    const val MaxPathChars = 512
    const val CopyBuffer = 1 shl 16

    /** Relative paths of letters, digits, '.', '-' and '_', split by '/', with no empty, '.' or '..' part. */
    fun validPath(path: String) = path.isNotEmpty() && path.length <= MaxPathChars &&
        path.split('/').all { part ->
            part.isNotEmpty() && part != "." && part != ".." && part.all { it.isLetterOrDigit() || it in ".-_" }
        }

    fun checkPath(path: String) {
        if (!validPath(path)) throw BundleFormatException("bad path in bundle: $path")
    }
}

/** A file that is not a bundle, is damaged or cut short, or was made by a newer app. */
class BundleFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Writes a bundle to [out]. Call [file] for each file, then [finish]; [close] closes [out]. */
internal class BundleWriter(out: OutputStream, magic: ByteArray = BundleFile.Magic) : Closeable {
    private val data = DataOutputStream(BufferedOutputStream(out, BundleFile.CopyBuffer))
    private var files = 0

    init {
        data.write(magic)
        data.writeInt(BundleFile.Version)
    }

    /** Copies exactly [size] bytes of [source]; a source that ends early is an error. */
    fun file(path: String, size: Long, source: InputStream, onBytes: (Int) -> Unit = {}) {
        BundleFile.checkPath(path)
        require(size >= 0) { "negative size" }
        data.writeByte(BundleFile.FileTag)
        data.writeUTF(path)
        data.writeLong(size)
        val crc = CRC32()
        val buf = ByteArray(BundleFile.CopyBuffer)
        var left = size
        while (left > 0) {
            val n = source.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("$path ended $left bytes early; was it changed while sending?")
            data.write(buf, 0, n)
            crc.update(buf, 0, n)
            left -= n
            onBytes(n)
        }
        data.writeInt(crc.value.toInt())
        files++
    }

    fun finish() {
        data.writeByte(BundleFile.EndTag)
        data.writeInt(files)
        data.flush()
    }

    override fun close() = data.close()
}

/** Reads a bundle from [input], one file at a time. */
internal class BundleReader(input: InputStream, private val magic: ByteArray = BundleFile.Magic) {
    private val data = DataInputStream(BufferedInputStream(input, BundleFile.CopyBuffer))

    /**
     * Calls [onFile] with each file's path, size and contents. [onFile] may read any part of the
     * contents; the rest is skipped. The CRC is checked after each file and the file count at the
     * end, so a bad bundle throws [BundleFormatException], possibly after some files were handed out.
     */
    @Suppress("ThrowsCount") // One reason per way a bundle can be bad.
    fun read(onFile: (path: String, size: Long, contents: InputStream) -> Unit) = guarded {
        val head = ByteArray(magic.size)
        data.readFully(head)
        if (!head.contentEquals(magic)) throw BundleFormatException("not a BetterDriver ${kindOf(magic)}")
        val version = data.readInt()
        if (version > BundleFile.Version) throw BundleFormatException("made by a newer app (version $version)")
        if (version < 1) throw BundleFormatException("bad version $version")
        var files = 0
        var tag = data.readByte().toInt()
        while (tag == BundleFile.FileTag) {
            readFile(onFile)
            files++
            tag = data.readByte().toInt()
        }
        if (tag != BundleFile.EndTag) throw BundleFormatException("bad record $tag")
        val expected = data.readInt()
        if (expected != files) throw BundleFormatException("bundle lists $expected files, holds $files")
    }

    private fun readFile(onFile: (String, Long, InputStream) -> Unit) {
        val path = data.readUTF()
        BundleFile.checkPath(path)
        val size = data.readLong()
        if (size < 0) throw BundleFormatException("bad size for $path")
        val contents = Bounded(data, size)
        onFile(path, size, contents)
        contents.skipRest()
        if (data.readInt() != contents.crc.value.toInt()) throw BundleFormatException("$path is damaged (CRC)")
    }

    private fun kindOf(magic: ByteArray) = if (magic.contentEquals(BundleFile.Magic)) "bundle" else "backup"

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: EOFException) {
            throw BundleFormatException("bundle is cut short", e)
        } catch (e: UTFDataFormatException) {
            throw BundleFormatException("bad path in bundle", e)
        }
    }

    /** The next [size] bytes of [source], with their CRC32. Never closes [source]. */
    private class Bounded(private val source: InputStream, size: Long) : InputStream() {
        val crc = CRC32()
        private var left = size

        override fun read(): Int {
            if (left == 0L) return -1
            val b = source.read()
            if (b < 0) throw EOFException()
            crc.update(b)
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left == 0L) return -1
            val n = if (len == 0) 0 else source.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException()
            crc.update(b, off, n)
            left -= n
            return n
        }

        override fun available(): Int = minOf(source.available().toLong(), left).toInt()

        fun skipRest() {
            val buf = ByteArray(BundleFile.CopyBuffer)
            while (read(buf, 0, buf.size) >= 0) Unit
        }

        override fun close() = Unit
    }
}
