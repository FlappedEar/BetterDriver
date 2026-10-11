@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.vbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VboxSportFormatTest {
    private fun VboxSportFrameReader.all(bytes: ByteArray) = buildList { feed(bytes) { add(it) } }

    @Test
    fun `crc is CRC-16 with polynomial 0x1021 and initial value 0`() {
        // The standard check value of this CRC (CRC-16/XMODEM) over "123456789".
        val check = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x31C3, VboxSportFormat.crc16(check, 0, check.size))
    }

    @Test
    fun `bluetooth default message is 55 bytes as in the document's format string`() {
        // "$VBSPT$nnnniiii,stttaaaaoooovvhheeezzxxyybbmmmmffffddcc"
        assertEquals(55, bluetoothDefaultMessage().size)
        val f = VboxSportFormat
        assertEquals(37, f.dataLength(f.BLUETOOTH_STANDARD, f.BLUETOOTH_EXTENDED))
        // USB default: satellites to vertical velocity, no extended channels.
        assertEquals(1 + 3 + 4 + 4 + 2 + 2 + 3 + 2, VboxSportFormat.dataLength(0xFF, 0))
    }

    @Test
    fun `offsets follow bit order and skip absent channels`() {
        val std = VboxSportFormat.SATELLITES or VboxSportFormat.SPEED
        assertEquals(0, VboxSportFormat.standardOffset(std, VboxSportFormat.SATELLITES))
        assertEquals(1, VboxSportFormat.standardOffset(std, VboxSportFormat.SPEED))
        assertNull(VboxSportFormat.standardOffset(std, VboxSportFormat.TIME))
        assertEquals(3, VboxSportFormat.extendedOffset(std, 0x40, VboxSportFormat.EXT_HDOP))
        assertEquals(35, VboxSportFormat.extendedOffset(0x3FF, 0x71, VboxSportFormat.EXT_HDOP))
    }

    @Test
    fun `undocumented extended bits have no length`() {
        assertNull(VboxSportFormat.dataLength(0x3FF, 0x80))
    }

    @Test
    fun `reads both header layouts`() {
        for (comma in listOf(false, true)) {
            val m = VboxSportFrameReader().all(bluetoothDefaultMessage(comma)).single()
            assertEquals(VboxSportFormat.BLUETOOTH_STANDARD, m.standard)
            assertEquals(VboxSportFormat.BLUETOOTH_EXTENDED, m.extended)
            assertArrayEquals(bluetoothDefaultData(), m.data)
        }
    }

    @Test
    fun `reassembles messages split anywhere and skips noise between them`() {
        val stream = byteArrayOf(0x00, 0x24, 0x56) + bluetoothDefaultMessage() + "junk".toByteArray() +
            bluetoothDefaultMessage(commaAfterHeader = true)
        for (chunk in 1..stream.size) {
            val reader = VboxSportFrameReader()
            val got = mutableListOf<VboxSportMessage>()
            stream.toList().chunked(chunk).forEach { part -> reader.feed(part.toByteArray()) { got += it } }
            assertEquals("chunk $chunk", 2, got.size)
            assertEquals(0L, reader.checksumErrors)
        }
    }

    @Test
    fun `a corrupted message is dropped and the next one read`() {
        val bad = bluetoothDefaultMessage().also { it[20] = (it[20] + 1).toByte() }
        val reader = VboxSportFrameReader()
        val got = reader.all(bad + bluetoothDefaultMessage())
        assertEquals(1, got.size)
        assertTrue(reader.checksumErrors >= 1)
    }

    @Test
    fun `a message with undocumented channels is counted and skipped`() {
        val reader = VboxSportFrameReader()
        val got = reader.all(vboxMessage(0x01, 0x80, byteArrayOf(5)) + bluetoothDefaultMessage())
        assertEquals(1, got.size)
        assertTrue(reader.unsupportedMessages >= 1)
    }

    @Test
    fun `little-endian bytes fail the CRC instead of giving wrong values`() {
        val m = bluetoothDefaultMessage()
        val swapped = m.copyOf().also { it[m.size - 2] = m[m.size - 1]; it[m.size - 1] = m[m.size - 2] }
        val reader = VboxSportFrameReader()
        assertTrue(reader.all(swapped).isEmpty())
        assertEquals(1L, reader.checksumErrors)
    }
}
