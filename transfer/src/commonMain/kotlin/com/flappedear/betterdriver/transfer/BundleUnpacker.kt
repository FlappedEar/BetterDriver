package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.ByteFormatException
import com.flappedear.betterdriver.io.ByteSource
import com.flappedear.betterdriver.io.Crc32

/** A bundle that is damaged, cut short, not a bundle, or made by a newer app (common code). */
class BundleDamagedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** What [BundleUnpacker] hands out, one file at a time. */
interface BundleEntrySink {
    /** A file of [size] bytes at [path] starts; [path] is checked with [BundleFormat.validPath]. */
    fun begin(path: String, size: Long)

    /** The next bytes of the current file, [bytes] from [from] to [to]; only valid during the call. */
    fun bytes(bytes: ByteArray, from: Int, to: Int)

    /** The current file ended and its CRC matched. */
    fun end()
}

/**
 * Reads the bundle container (see BundleFile) pushed to it in pieces of any size, so a bundle of
 * any length, video included, is read in one pass with little memory (FBBD-331). [feed] each piece
 * in order, then [finish]. Every file's CRC and the file count are checked, and a path may come
 * only once, in at most [BundleFormat.MaxFiles] files (audit 30 Oct BD-06); anything wrong throws
 * [BundleDamagedException], possibly after some files were handed out.
 */
class BundleUnpacker(private val sink: BundleEntrySink, private val magic: ByteArray = BundleFormat.SessionMagic) {
    private enum class Step { MAGIC, VERSION, TAG, PATH_LENGTH, PATH, SIZE, CONTENT, CRC, COUNT, DONE }

    private var step = Step.MAGIC
    private var need = magic.size
    private val held = ByteArray(MaxHeld)
    private var heldSize = 0
    private var left = 0L
    private var crc = Crc32()
    private var files = 0
    private var path = ""
    private val seen = HashSet<String>()

    /** Total bytes fed so far. */
    var bytesRead = 0L
        private set

    /** Reads [bytes] from [from] to [to]. */
    fun feed(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        var i = from
        while (i < to) {
            if (step == Step.DONE) throw BundleDamagedException("data after the end of the bundle")
            if (step == Step.CONTENT) {
                val n = minOf((to - i).toLong(), left).toInt()
                crc.update(bytes, i, i + n)
                sink.bytes(bytes, i, i + n)
                left -= n
                i += n
                bytesRead += n
                if (left == 0L) next(Step.CRC, Int.SIZE_BYTES)
            } else {
                val n = minOf(to - i, need - heldSize)
                bytes.copyInto(held, heldSize, i, i + n)
                heldSize += n
                i += n
                bytesRead += n
                if (heldSize == need) field()
            }
        }
    }

    /** The bundle ended: throws when it was cut short. */
    fun finish() {
        if (step != Step.DONE) throw BundleDamagedException("bundle is cut short")
    }

    private fun next(s: Step, bytes: Int) {
        step = s
        need = bytes
        heldSize = 0
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount") // One branch per field of the format.
    private fun field() {
        val src = ByteSource(held, 0, heldSize)
        when (step) {
            Step.MAGIC -> {
                if (!held.copyOf(heldSize).contentEquals(magic)) {
                    throw BundleDamagedException("not a BetterDriver ${kind()}")
                }
                next(Step.VERSION, Int.SIZE_BYTES)
            }
            Step.VERSION -> {
                val v = src.readInt()
                if (v > BundleFormat.Version) throw BundleDamagedException("made by a newer app (version $v)")
                if (v < 1) throw BundleDamagedException("bad version $v")
                next(Step.TAG, 1)
            }
            Step.TAG -> when (val tag = src.readUnsignedByte()) {
                BundleFormat.FileTag -> next(Step.PATH_LENGTH, 2)
                BundleFormat.EndTag -> next(Step.COUNT, Int.SIZE_BYTES)
                else -> throw BundleDamagedException("bad record $tag")
            }
            Step.PATH_LENGTH -> {
                val len = src.readUnsignedShort()
                if (len > MaxPathBytes) throw BundleDamagedException("bad path in bundle")
                // The length stays held: the path is read with it, as DataInputStream.readUTF reads it.
                step = Step.PATH
                need = 2 + len
                if (heldSize == need) field()
            }
            Step.PATH -> {
                path = try {
                    src.readUtf()
                } catch (e: ByteFormatException) {
                    throw BundleDamagedException("bad path in bundle: ${e.message}", e)
                }
                if (!BundleFormat.validPath(path)) throw BundleDamagedException("bad path in bundle: $path")
                if (!seen.add(path)) throw BundleDamagedException("$path is in the bundle twice")
                if (seen.size > BundleFormat.MaxFiles) {
                    throw BundleDamagedException("more than ${BundleFormat.MaxFiles} files")
                }
                next(Step.SIZE, Long.SIZE_BYTES)
            }
            Step.SIZE -> {
                left = src.readLong()
                if (left < 0) throw BundleDamagedException("bad size for $path")
                crc = Crc32()
                sink.begin(path, left)
                if (left == 0L) next(Step.CRC, Int.SIZE_BYTES) else next(Step.CONTENT, 0)
            }
            Step.CRC -> {
                if (src.readInt() != crc.value) throw BundleDamagedException("$path is damaged (CRC)")
                files++
                sink.end()
                next(Step.TAG, 1)
            }
            Step.COUNT -> {
                val expected = src.readInt()
                if (expected != files) throw BundleDamagedException("bundle lists $expected files, holds $files")
                next(Step.DONE, 0)
            }
            Step.CONTENT, Step.DONE -> error("not a field")
        }
    }

    private fun kind() = if (magic.contentEquals(BundleFormat.SessionMagic)) "bundle" else "backup"

    private companion object {
        /** Modified UTF-8 of [BundleFormat.MaxPathChars] characters is at most 3 bytes each. */
        const val MaxPathBytes = BundleFormat.MaxPathChars * 3
        const val MaxHeld = 2 + MaxPathBytes
    }
}
