package com.flappedear.betterdriver.gopro

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenGoProTest {
    private fun hex(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `shutter and AP commands match the Open GoPro tutorial bytes`() {
        // https://gopro.github.io/OpenGoPro/tutorials/send-ble-commands and .../connect-wifi
        assertContentEquals(hex(0x03, 0x01, 0x01, 0x01), OpenGoPro.packetize(OpenGoPro.shutter(true).message).single())
        assertContentEquals(hex(0x03, 0x01, 0x01, 0x00), OpenGoPro.packetize(OpenGoPro.shutter(false).message).single())
        assertContentEquals(hex(0x03, 0x17, 0x01, 0x01), OpenGoPro.packetize(OpenGoPro.wifiAp(true).message).single())
        assertEquals(GoProUuids.COMMAND, OpenGoPro.shutter(true).characteristic)
        assertEquals("b5f90072-aa8d-11e3-9046-0002a5d5c51b", GoProUuids.COMMAND)
    }

    @Test
    fun `set local date time matches the documented example`() {
        // 2023-01-31 03:04:05, UTC-02:00, DST on → 07:E7:01:1F:03:04:05:FF:88:01
        val r = OpenGoPro.setLocalDateTime(LocalDateTime.of(2023, 1, 31, 3, 4, 5), -120, true)
        assertContentEquals(hex(0x0F, 10, 0x07, 0xE7, 0x01, 0x1F, 0x03, 0x04, 0x05, 0xFF, 0x88, 0x01), r.message)
    }

    @Test
    fun `keep alive is setting 0x5B with 0x42 on the settings characteristic`() {
        val r = OpenGoPro.keepAlive()
        assertEquals(GoProUuids.SETTINGS, r.characteristic)
        assertContentEquals(hex(0x5B, 0x01, 0x42), r.message)
    }

    @Test
    fun `long messages use an extended header and continuation packets that reassemble`() {
        val message = ByteArray(50) { it.toByte() }
        val packets = OpenGoPro.packetize(message)
        assertEquals(3, packets.size)
        assertContentEquals(hex(0x20, 50), packets[0].copyOfRange(0, 2))
        assertEquals(0x80, packets[1][0].toInt() and 0xFF)
        assertEquals(0x81, packets[2][0].toInt() and 0xFF)
        packets.forEach { assert(it.size <= 20) }
        val a = OpenGoPro.Assembler()
        assertNull(a.accept(packets[0]))
        assertNull(a.accept(packets[1]))
        assertContentEquals(message, a.accept(packets[2]))
    }

    @Test
    fun `assembler reads general and 16-bit headers and ignores stray continuations`() {
        val a = OpenGoPro.Assembler()
        assertNull(a.accept(hex(0x80, 1, 2)))
        assertContentEquals(hex(0x01, 0x00), a.accept(hex(0x02, 0x01, 0x00)))
        assertContentEquals(hex(1, 2, 3), a.accept(hex(0x40, 0x00, 0x03, 1, 2, 3)))
    }

    @Test
    fun `status query payload parses into id to value`() {
        val r = assertNotNull(OpenGoPro.Response.parse(hex(0x13, 0x00, 8, 1, 1, 10, 1, 0)))
        assertEquals(0x13, r.id)
        val values = OpenGoPro.parseStatuses(r.payload)
        assertEquals(true, OpenGoPro.flag(values[8]))
        assertEquals(false, OpenGoPro.flag(values[10]))
    }

    @Test
    fun `hardware info parses length-prefixed fields`() {
        fun field(s: String) = byteArrayOf(s.length.toByte()) + s.toByteArray()
        val payload = hex(4, 0, 0, 0, 58) + field("HERO11 Black") + hex(4, 0, 0, 0, 5) + field("H22.01.01.10.00") +
            field("C3471325208909") + field("GP25208909") + field("2674f7f65f78")
        val info = assertNotNull(OpenGoPro.HardwareInfo.parse(payload))
        assertEquals(58, info.modelNumber)
        assertEquals("HERO11 Black", info.modelName)
        assertEquals("H22.01.01.10.00", info.firmware)
        assertEquals("GP25208909", info.apSsid)
        assertNull(OpenGoPro.HardwareInfo.parse(payload.copyOf(10)))
    }
}
