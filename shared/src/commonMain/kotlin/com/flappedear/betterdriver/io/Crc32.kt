package com.flappedear.betterdriver.io

private const val Polynomial = 0xEDB88320.toInt()
private const val ByteMask = 0xFF
private const val BitsPerByte = 8
private const val TableSize = 256

private val Table = IntArray(TableSize) { n ->
    var c = n
    repeat(BitsPerByte) { c = if (c and 1 != 0) Polynomial xor (c ushr 1) else c ushr 1 }
    c
}

/**
 * CRC-32 as java.util.zip.CRC32 and zip compute it (IEEE 802.3, reflected), for common code:
 * session chunks (FBBD-70) are checked with it on Android and iOS alike.
 */
class Crc32 {
    private var crc = -1

    /** Adds the low 8 bits of [b], as CRC32.update(int). */
    fun update(b: Int) {
        crc = Table[(crc xor b) and ByteMask] xor (crc ushr BitsPerByte)
    }

    fun update(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        var c = crc
        for (i in from until to) c = Table[(c xor bytes[i].toInt()) and ByteMask] xor (c ushr BitsPerByte)
        crc = c
    }

    /** The CRC so far, as the low 32 bits of CRC32.getValue(). */
    val value: Int get() = crc.inv()
}
