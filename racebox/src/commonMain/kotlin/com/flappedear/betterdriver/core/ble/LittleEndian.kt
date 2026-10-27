@file:Suppress("MagicNumber") // Byte shifts.

package com.flappedear.betterdriver.core.ble

/**
 * Reads little-endian values at absolute positions, as ByteBuffer with LITTLE_ENDIAN order did
 * before this code became common Kotlin.
 */
internal class LittleEndian(private val bytes: ByteArray) {
    fun get(i: Int): Byte = bytes[i]

    fun getShort(i: Int): Short = (u8(i) or (u8(i + 1) shl 8)).toShort()

    fun getInt(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)

    private fun u8(i: Int) = bytes[i].toInt() and 0xFF
}
