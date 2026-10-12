@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** FBBD-53: the generic mode 01 channels and the supported-PID bitmasks (SAE J1979). */
class StandardPidsTest {
    private fun value(channel: PidChannel, response: String): Double =
        assertNotNull(channel.decode(assertNotNull(hexBytes(response))), "${channel.id} $response")

    @Test
    fun `every standard channel parses and asks mode 01 with the default header`() {
        StandardPids.all.forEach {
            assertNotNull(it.compiled, it.id)
            assertEquals(0x01, it.mode)
            assertNull(it.header)
            assertEquals("01" + hex(it.pid, 2), it.command)
        }
        assertEquals(StandardPids.all.size, StandardPids.all.map { it.id }.toSet().size)
    }

    @Test
    fun `J1979 scalings`() {
        with(StandardPids) {
            assertEquals(1726.0, value(Rpm, "41 0C 1A F8")) // 0x1AF8 = 6904, ÷4
            assertEquals(100.0, value(Speed, "41 0D 64"))
            assertEquals(100.0, value(Throttle, "41 11 FF"))
            assertEquals(128 * 100 / 255.0, value(Throttle, "41 11 80"), 1e-9) // 50.2 %
            assertEquals(20.0, value(PedalD, "41 49 33")) // 51 × 100 ÷ 255
            assertEquals(25 * 100 / 255.0, value(PedalE, "41 4A 19"), 1e-9)
            assertEquals(64 * 100 / 255.0, value(Accelerator, "41 5A 40"), 1e-9)
            assertEquals(83.0, value(Coolant, "41 05 7B")) // 123 − 40
            assertEquals(25.0, value(IntakeAirTemp, "41 0F 41")) // 65 − 40
            assertEquals(5.0, value(Maf, "41 10 01 F4")) // 500 ÷ 100
            assertEquals(101.0, value(ManifoldPressure, "41 0B 65"))
            assertEquals(100.0, value(EngineLoad, "41 04 FF"))
            assertEquals(8.0, value(TimingAdvance, "41 0E 90")) // 144 ÷ 2 − 64
            assertEquals(-64.0, value(TimingAdvance, "41 0E 00"))
            assertEquals(128 * 100 / 255.0, value(FuelLevel, "41 2F 80"), 1e-9)
            assertEquals(14.0, value(ModuleVoltage, "41 42 36 B0")) // 14000 ÷ 1000
            assertEquals(85.0, value(OilTemp, "41 5C 7D"))
            assertEquals(20.0, value(AmbientTemp, "41 46 3C"))
            assertEquals(1.0, value(Lambda, "41 24 80 00 66 66")) // 32768 × 2 ÷ 65536; C, D are the voltage
            assertEquals(31457 * 2 / 65536.0, value(CommandedLambda, "41 44 7A E1"), 1e-12) // 0.96
        }
    }

    @Test
    fun `wrong answers give no value`() {
        assertNull(StandardPids.Rpm.decode(hexBytes("41 0C 1A")!!)) // B missing
        assertNull(StandardPids.Rpm.decode(hexBytes("41 0D 1A F8")!!)) // another PID
        assertNull(StandardPids.Rpm.decode(hexBytes("7F 01 12")!!)) // negative response
        assertNull(StandardPids.Rpm.decode(ByteArray(0)))
    }

    @Test
    fun `two-byte PIDs for mode 22`() {
        val vinDigit = PidChannel("x", "x", 0x7E0, 0x22, 0xF190, "A", "", PollClass.SLOW, pidLength = 2)
        assertEquals("22F190", vinDigit.command)
        assertEquals(0x57.toDouble(), vinDigit.decode(hexBytes("62 F1 90 57 30")!!))
        assertNull(vinDigit.decode(hexBytes("62 F1 91 57")!!))
    }

    @Test
    fun `bitmask 01 00 decodes to the PIDs it lists`() {
        // The J1979 example BE 1F A8 13: 1011 1110 | 0001 1111 | 1010 1000 | 0001 0011
        val pids = assertNotNull(SupportedPids.fromMessage(hexBytes("41 00 BE 1F A8 13")!!))
        assertEquals(
            setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            pids,
        )
        assertEquals(0x20, SupportedPids.nextBitmask(0x00, pids)) // 0x20 is set: ask 01 20 next
    }

    @Test
    fun `later ranges are offset by their base`() {
        // 01 40: 0x80 sets PID 0x41, 0x01 in the last byte sets 0x60 (next range supported)
        assertEquals(setOf(0x41, 0x60), SupportedPids.fromMessage(hexBytes("41 40 80 00 00 01")!!))
        // 01 C0: last range; nothing after it even when its last bit is set
        assertEquals(setOf(0xC1, 0xE0), SupportedPids.decode(0xC0, hexBytes("80 00 00 01")!!))
        assertNull(SupportedPids.nextBitmask(0xC0, setOf(0xE0)))
        assertNull(SupportedPids.nextBitmask(0x20, setOf(0x21)))
    }

    @Test
    fun `several ECUs answer and the car supports the union`() {
        val response = ElmParser.parse("7E8 06 41 00 BE 3F A8 13 00\r7E9 06 41 00 98 18 80 11 00\r\r>", headers = true)
        val messages = (response as ElmResponse.Messages).messages
        val pids = SupportedPids.fromMessages(messages)
        // 7E8: BE 3F A8 13 → 01 03-07 0B-10 11 13 15 1C 1F 20; 7E9: 98 18 80 11 → 01 04 05 0C 0D 11 1C 20
        assertEquals(
            setOf(
                0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D,
                0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20,
            ),
            pids,
        )
        assertNull(SupportedPids.fromMessage(hexBytes("41 00 BE 1F")!!)) // short
        assertNull(SupportedPids.fromMessage(hexBytes("41 0C 1A F8 00 00")!!)) // not a bitmask PID
    }
}
