package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.ByteSink
import com.flappedear.betterdriver.io.Crc32

/**
 * Writes the bundle container (see BundleFile) in one pass, the bytes BundleWriter writes on
 * Android, so a session shared from iOS opens on Android and the other way round (FBBD-331). Each
 * file is [begin] with its size, its bytes given to [bytes] in pieces of any size, then [end];
 * [finish] closes the bundle. Everything written goes to [out].
 */
class BundlePacker(
    private val out: (bytes: ByteArray, from: Int, to: Int) -> Unit,
    magic: ByteArray = BundleFormat.SessionMagic,
) {
    private var crc = Crc32()
    private var left = -1L
    private var path = ""
    private var files = 0

    init {
        emit(ByteSink().apply {
            write(magic)
            writeInt(BundleFormat.Version)
        })
    }

    /** A file of [size] bytes at [path] (checked with [BundleFormat.validPath]) starts. */
    fun begin(path: String, size: Long) {
        check(left < 0) { "${this.path} is not finished" }
        require(BundleFormat.validPath(path)) { "bad path in bundle: $path" }
        require(size >= 0) { "negative size" }
        emit(ByteSink().apply {
            writeByte(BundleFormat.FileTag)
            writeUtf(path)
            writeLong(size)
        })
        this.path = path
        left = size
        crc = Crc32()
    }

    /** The next bytes of the current file, [bytes] from [from] to [to]; more than its size is an error. */
    fun bytes(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        check(to - from <= left) { "$path is longer than its size" }
        crc.update(bytes, from, to)
        out(bytes, from, to)
        left -= to - from
    }

    /** The current file ends; one that ended early (changed while sending) is an error. */
    fun end() {
        check(left == 0L) { "$path ended $left bytes early; was it changed while sending?" }
        emit(ByteSink().apply { writeInt(crc.value) })
        left = -1L
        files++
    }

    /** A whole small file at [path]. */
    fun file(path: String, contents: ByteArray) {
        begin(path, contents.size.toLong())
        bytes(contents)
        end()
    }

    /** Ends the bundle with its file count. */
    fun finish() {
        check(left < 0) { "$path is not finished" }
        emit(ByteSink().apply {
            writeByte(BundleFormat.EndTag)
            writeInt(files)
        })
    }

    private fun emit(sink: ByteSink) {
        val bytes = sink.toByteArray()
        out(bytes, 0, bytes.size)
    }
}
