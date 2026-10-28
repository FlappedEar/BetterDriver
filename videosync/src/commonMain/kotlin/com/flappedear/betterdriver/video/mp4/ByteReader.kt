@file:Suppress("MagicNumber") // Byte widths and shifts.

package com.flappedear.betterdriver.video.mp4

/** A read ran past the end of what a [ByteReader] holds. [parsing] turns it into "cut short". */
internal class CutShort(message: String) : RuntimeException(message)

/**
 * Big-endian reading of a byte array window, the part of java.nio.ByteBuffer the MP4 and GPMF
 * readers use, in common Kotlin. [position] and [limit] count from the start of the window, as a
 * ByteBuffer's do from the start of its slice.
 */
internal class ByteReader private constructor(
    private val data: ByteArray,
    private val base: Int,
    private val capacity: Int,
) {
    constructor(data: ByteArray) : this(data, 0, data.size)

    private var pos = 0
    private var lim = capacity

    fun position(): Int = pos

    fun position(p: Int): ByteReader {
        require(p in 0..lim) { "position $p outside 0..$lim" }
        pos = p
        return this
    }

    fun limit(): Int = lim

    fun limit(l: Int): ByteReader {
        require(l in 0..capacity) { "limit $l outside 0..$capacity" }
        lim = l
        if (pos > l) pos = l
        return this
    }

    fun remaining(): Int = lim - pos

    /** A reader of the same bytes with its own position. */
    fun duplicate(): ByteReader = ByteReader(data, base, capacity).also {
        it.lim = lim
        it.pos = pos
    }

    /** A reader of the bytes from the position to the limit. */
    fun slice(): ByteReader = ByteReader(data, base + pos, remaining())

    private fun take(n: Int): Int {
        if (n > remaining()) throw CutShort("needs $n bytes, has ${remaining()}")
        val at = base + pos
        pos += n
        return at
    }

    fun get(): Byte = data[take(1)]

    fun get(dst: ByteArray) {
        val at = take(dst.size)
        data.copyInto(dst, 0, at, at + dst.size)
    }

    val short: Short
        get() {
            val at = take(2)
            return ((data[at].toInt() and 0xFF shl 8) or (data[at + 1].toInt() and 0xFF)).toShort()
        }

    val int: Int
        get() {
            val at = take(4)
            var v = 0
            for (i in 0 until 4) v = v shl 8 or (data[at + i].toInt() and 0xFF)
            return v
        }

    val long: Long
        get() {
            val at = take(8)
            var v = 0L
            for (i in 0 until 8) v = v shl 8 or (data[at + i].toLong() and 0xFF)
            return v
        }

    val float: Float get() = Float.fromBits(int)

    val double: Double get() = Double.fromBits(long)
}

/** The bytes as US-ASCII text, as String(bytes, US_ASCII): a byte above 127 becomes U+FFFD. */
internal fun ByteArray.ascii(): String = buildString(size) {
    for (b in this@ascii) append(if (b >= 0) b.toInt().toChar() else '�')
}
