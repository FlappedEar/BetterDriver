@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UbxFrameReaderTest {
    private fun read(vararg chunks: ByteArray, reader: UbxFrameReader = UbxFrameReader()): List<UbxFrame> {
        val frames = mutableListOf<UbxFrame>()
        chunks.forEach { reader.feed(it) { f -> frames += f } }
        return frames
    }

    @Test
    fun `checksum matches the u-blox Fletcher algorithm`() {
        // Empty 06 00 message: CK_A = 0x06, CK_B = 4 x 0x06 = 0x18 (worked by hand).
        val packet = ubxPacket(0x06, 0x00, ByteArray(0))
        assertEquals(listOf(0xB5, 0x62, 0x06, 0x00, 0x00, 0x00, 0x06, 0x18), packet.map { it.toInt() and 0xFF })
    }

    @Test
    fun `checksum matches RaceBox's documented example packet`() {
        val official = RaceBoxTestData.officialPacket
        assertTrue(RaceBoxTestData.packet().contentEquals(official))
        assertEquals(1, read(official).size)
    }

    @Test
    fun `reads a whole packet`() {
        val frames = read(RaceBoxTestData.packet())
        assertEquals(1, frames.size)
        assertEquals(RaceBoxClass, frames[0].messageClass)
        assertEquals(RaceBoxDataId, frames[0].messageId)
        assertEquals(RaceBoxDataLength, frames[0].payload.size)
    }

    @Test
    fun `reassembles a packet split over 20-byte notifications`() {
        val packet = RaceBoxTestData.packet()
        val chunks = packet.toList().chunked(20).map { it.toByteArray() }
        val frames = read(*chunks.toTypedArray())
        assertEquals(1, frames.size)
        assertTrue(frames[0].payload.contentEquals(RaceBoxTestData.payload()))
    }

    @Test
    fun `reads several packets in one notification`() {
        val two = RaceBoxTestData.packet() + RaceBoxTestData.packet()
        assertEquals(2, read(two).size)
    }

    @Test
    fun `split sync bytes are kept for the next notification`() {
        val packet = RaceBoxTestData.packet()
        assertEquals(1, read(packet.copyOfRange(0, 1), packet.copyOfRange(1, packet.size)).size)
    }

    @Test
    fun `rejects a bad checksum and recovers on the next packet`() {
        val bad = RaceBoxTestData.packet().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val reader = UbxFrameReader()
        val frames = read(bad, RaceBoxTestData.packet(), reader = reader)
        assertEquals(1, frames.size)
        assertEquals(1, reader.checksumErrors)
    }

    @Test
    fun `rejects a corrupted payload byte`() {
        val bad = RaceBoxTestData.packet().also { it[30] = (it[30] + 1).toByte() }
        val reader = UbxFrameReader()
        assertEquals(0, read(bad, reader = reader).size)
        assertEquals(1, reader.checksumErrors)
    }

    @Test
    fun `skips garbage before a packet and an impossible length`() {
        val garbage = byteArrayOf(0x00, 0x13, 0xB5.toByte(), 0x62, 0xFF.toByte(), 0x01, 0xFF.toByte(), 0xFF.toByte())
        val reader = UbxFrameReader()
        val frames = read(garbage + RaceBoxTestData.packet(), reader = reader)
        assertEquals(1, frames.size)
        assertTrue(reader.skippedBytes >= garbage.size)
    }

    @Test
    fun `reset drops a partial packet`() {
        val packet = RaceBoxTestData.packet()
        val reader = UbxFrameReader()
        val frames = mutableListOf<UbxFrame>()
        reader.feed(packet.copyOfRange(0, 40)) { frames += it }
        reader.reset()
        reader.feed(packet) { frames += it }
        assertEquals(1, frames.size)
        assertEquals(0, reader.checksumErrors)
    }
}
