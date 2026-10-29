@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val N = BleSerial.PROPERTY_NOTIFY
private const val I = BleSerial.PROPERTY_INDICATE
private const val W = BleSerial.PROPERTY_WRITE
private const val WNR = BleSerial.PROPERTY_WRITE_NO_RESPONSE
private const val READ = 0x02

private fun s(id: Int) = BleSerial.short(id)

private fun c(service: Int, ch: Int, props: Int) = GattCharacteristic(s(service), s(ch), props)

/** Services every adapter also has, which must never be taken for the serial line. */
private val standard = listOf(c(0x1800, 0x2A00, READ), c(0x180A, 0x2A29, READ), c(0x1801, 0x2A05, I))

/** FBBD-121: finding the serial pair of a Bluetooth LE ELM327, and writing to it. */
class BleSerialTest {
    @Test
    fun `the common FFF0 pair`() {
        val pair = BleSerial.choose(standard + listOf(c(0xFFF0, 0xFFF1, N), c(0xFFF0, 0xFFF2, W or WNR)))
        assertEquals(BleSerialPair(s(0xFFF0), s(0xFFF1), s(0xFFF2), withResponse = true), pair)
    }

    @Test
    fun `an HM-10 style single characteristic`() {
        val pair = BleSerial.choose(standard + c(0xFFE0, 0xFFE1, N or WNR or READ))
        assertEquals(BleSerialPair(s(0xFFE0), s(0xFFE1), s(0xFFE1), withResponse = false), pair)
    }

    @Test
    fun `the Vgate pair iOS apps use`() {
        val service = "e7810a71-73ae-499d-8c15-faa9aef0c3f2"
        val ch = "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"
        val pair = BleSerial.choose(standard + GattCharacteristic(service, ch, N or W or WNR or READ))
        assertEquals(BleSerialPair(service, ch, ch, withResponse = true), pair)
    }

    @Test
    fun `an unknown maker's pair is still found`() {
        val service = "49535343-fe7d-4ae5-8fa9-9fafd205e455"
        val rx = "49535343-1e4d-4bd9-ba61-23c647249616"
        val tx = "49535343-8841-43f4-a8d4-ecbe34729bb3"
        val all = standard + listOf(GattCharacteristic(service, rx, N), GattCharacteristic(service, tx, WNR))
        assertEquals(BleSerialPair(service, rx, tx, withResponse = false), BleSerial.choose(all))
    }

    @Test
    fun `a known pair with the wrong properties is skipped`() {
        // FFF1 cannot notify here, so FFF0 is not the line; the next service with a pair is.
        val all = listOf(c(0xFFF0, 0xFFF1, READ), c(0xFFF0, 0xFFF2, W), c(0x18F0, 0x2AF0, I), c(0x18F0, 0x2AF1, W))
        assertEquals(BleSerialPair(s(0x18F0), s(0x2AF0), s(0x2AF1), withResponse = true), BleSerial.choose(all))
    }

    @Test
    fun `a device with no serial line`() {
        assertNull(BleSerial.choose(standard + c(0x180D, 0x2A37, N)))
    }

    private class Port(var accept: Boolean = true) : BleSerialPort {
        val chunks = ArrayList<String>()
        var disconnects = 0
        var onWrite: (ByteArray) -> Unit = {}

        override fun write(chunk: ByteArray): Boolean {
            chunks += String(chunk)
            if (accept) onWrite(chunk)
            return accept
        }

        override fun disconnect() {
            disconnects++
        }
    }

    private class Sink : ObdBytes {
        val got = StringBuilder()
        var closed: String? = null

        override fun received(bytes: ByteArray) {
            got.append(String(bytes))
        }

        override fun closed(reason: String) {
            closed = reason
        }
    }

    @Test
    fun `writes go out in payload-sized pieces, one at a time`() {
        val port = Port()
        lateinit var conn: BleSerialConnection
        port.onWrite = { Thread { conn.writeDone(true) }.start() }
        conn = BleSerialConnection(port, Sink(), payload = 4)
        conn.write("ATDPN\r".toByteArray())
        assertEquals(listOf("ATDP", "N\r"), port.chunks)
    }

    @Test
    fun `a write that is never acknowledged fails`() {
        val conn = BleSerialConnection(Port(), Sink(), payload = 20, writeTimeoutMs = 20)
        assertFailsWith<IOException> { conn.write("ATZ\r".toByteArray()) }
    }

    @Test
    fun `a refused or failed write fails`() {
        val refused = BleSerialConnection(Port(accept = false), Sink(), 20)
        assertFailsWith<IOException> { refused.write("ATZ\r".toByteArray()) }
        val port = Port()
        lateinit var conn: BleSerialConnection
        port.onWrite = { Thread { conn.writeDone(false) }.start() }
        conn = BleSerialConnection(port, Sink(), 20)
        assertFailsWith<IOException> { conn.write("ATZ\r".toByteArray()) }
    }

    @Test
    fun `a lost link ends a waiting write, and close still releases the GATT connection once`() {
        val port = Port()
        val sink = Sink()
        lateinit var conn: BleSerialConnection
        port.onWrite = { Thread { conn.lost("connection state 0, status 8") }.start() }
        conn = BleSerialConnection(port, sink, 20)
        val e = assertFailsWith<IOException> { conn.write("ATZ\r".toByteArray()) }
        assertEquals("connection state 0, status 8", e.message)
        assertEquals("connection state 0, status 8", sink.closed)
        conn.close()
        conn.close()
        assertEquals(1, port.disconnects)
    }

    @Test
    fun `notifications pass through until the connection ends`() {
        val sink = Sink()
        val conn = BleSerialConnection(Port(), sink, 20)
        conn.notified("410C".toByteArray())
        conn.notified("1AF8\r\r>".toByteArray())
        conn.close()
        conn.notified("late".toByteArray())
        assertEquals("410C1AF8\r\r>", sink.got.toString())
        assertEquals("closed", sink.closed)
    }

    @Test
    fun `adapter names`() {
        listOf("vLinker MC+", "OBDII", "OBD2", "V-LINK", "Vgate iCar Pro", "OBDLink MX+", "KONNWEI", "ELM327 v1.5")
            .forEach { assertTrue(ObdAdapterNames.isCandidate(it), it) }
        listOf("RaceBox Micro 3241", "Polar H10 7A3B21", "Garmin GLO", "Pixel Buds", null, "")
            .forEach { assertFalse(ObdAdapterNames.isCandidate(it), it.toString()) }
    }

    @Test
    fun `an adapter with another name counts by the serial service it advertises`() {
        assertTrue(ObdAdapterNames.isCandidate("LELink-2", emptyList()))
        assertTrue(ObdAdapterNames.isCandidate("KW902", emptyList()))
        assertTrue(ObdAdapterNames.isCandidate("SCAN-77", listOf("FFF0")))
        assertTrue(ObdAdapterNames.isCandidate(null, listOf("0000fff0-0000-1000-8000-00805f9b34fb")))
        assertTrue(ObdAdapterNames.isCandidate("X", listOf("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2")))
        assertTrue(ObdAdapterNames.isCandidate("X", listOf("180F", "18F0")))
        // HM-10's FFE0 is on many other gadgets; the standard services are on every device.
        assertFalse(ObdAdapterNames.isCandidate("Fridge", listOf("FFE0")))
        assertFalse(ObdAdapterNames.isCandidate("Polar H10 7A3B21", listOf("180D", "180F")))
        assertFalse(ObdAdapterNames.isCandidate(null, emptyList()))
    }
}
