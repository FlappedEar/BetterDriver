package com.flappedear.betterdriver.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BigEndianTest {
    @Test
    fun writesBigEndianAndModifiedUtf8() {
        val sink = ByteSink(1)
        sink.writeInt(0x42445231)
        sink.writeUtf("a\u0000ą€")
        val expected = byteArrayOf(0x42, 0x44, 0x52, 0x31, 0, 8, 0x61, 0xC0.toByte(), 0x80.toByte()) +
            byteArrayOf(0xC4.toByte(), 0x85.toByte(), 0xE2.toByte(), 0x82.toByte(), 0xAC.toByte())
        assertContentEquals(expected, sink.toByteArray())
        val back = ByteSource(sink.toByteArray())
        assertEquals(0x42445231, back.readInt())
        assertEquals("a\u0000ą€", back.readUtf())
    }

    @Test
    fun shortReadIsAFormatError() {
        assertFailsWith<ByteFormatException> { ByteSource(byteArrayOf(1, 2, 3)).readInt() }
        assertFailsWith<ByteFormatException> { ByteSource(byteArrayOf(0, 2, 0x61)).readUtf() }
    }

    @Test
    fun crcOfCheckString() {
        // The standard CRC-32 check value.
        assertEquals(0xCBF43926.toInt(), Crc32().apply { update("123456789".encodeToByteArray()) }.value)
    }
}
