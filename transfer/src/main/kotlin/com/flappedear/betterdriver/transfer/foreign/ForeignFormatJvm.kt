package com.flappedear.betterdriver.transfer.foreign

import java.io.IOException
import java.io.InputStream

/** A file that cannot be imported: not a known format, damaged, empty or too large. The message is for the user. */
actual class ImportFormatException actual constructor(message: String, cause: Throwable?) : IOException(message, cause)

/**
 * Counts what passes through: [onBytes] for progress, and the import fails beyond [limit] bytes
 * (a decompressed file can be far larger than the one picked).
 */
internal class CountingInput(
    private val input: InputStream,
    private val limit: Long,
    private val onBytes: (Int) -> Unit = {},
) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int {
        val b = input.read()
        if (b >= 0) counted(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) counted(n)
        return n
    }

    private fun counted(n: Int) {
        count += n
        if (count > limit) throw ImportFormatException("the file is too large (over ${limit / BytesPerMegabyte} MB)")
        onBytes(n)
    }

    override fun close() = input.close()

    private companion object {
        const val BytesPerMegabyte = 1L shl 20
    }
}
