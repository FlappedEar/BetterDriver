package com.flappedear.betterdriver.core.ble

/** Low byte mask and bit widths of the little-endian GATT integer formats. */
private const val ByteMask = 0xFF
private const val Bits8 = 8
private const val Bits16 = 16
private const val Bits24 = 24
private const val Sint24Shift = 8

/**
 * Reads the little-endian integer formats of the GATT Specification Supplement (uint8, uint16,
 * uint24, sint24, sint32) from a characteristic value, front to back. A read past the end throws
 * [TruncatedValue], so a decoder can drop a short value in one place.
 */
internal class LeReader(private val bytes: ByteArray) {
    var offset = 0
        private set

    val remaining: Int get() = bytes.size - offset

    fun uint8(): Int {
        need(1)
        return bytes[offset++].toInt() and ByteMask
    }

    fun uint16(): Int = uint8() or (uint8() shl Bits8)

    fun uint24(): Int = uint16() or (uint8() shl Bits16)

    /** Sign-extended from 24 bits. */
    fun sint24(): Int = (uint24() shl Sint24Shift) shr Sint24Shift

    fun sint32(): Int = uint24() or (uint8() shl Bits24)

    /** Throws [TruncatedValue] unless [n] more bytes are left. */
    fun need(n: Int) {
        if (remaining < n) throw TruncatedValue()
    }
}

/** A characteristic value shorter than its flags say. */
internal class TruncatedValue : Exception("characteristic value truncated")
