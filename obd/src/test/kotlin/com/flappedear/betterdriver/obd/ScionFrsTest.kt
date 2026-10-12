@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-16: the 2013 Scion FR-S profile, decoded from adapter output worked out by hand. */
class ScionFrsTest {
    private val profile = CarProfiles.ScionFrs2013
    private fun channel(id: String) = profile.channels.single { it.id == id }
    private val brake = channel("brake")
    private val accelerator = channel("accelerator")
    private val oil = channel("oil_temp")
    private val atf = channel("atf_temp")

    private fun messages(text: String, headers: Boolean = true) =
        assertIs<ElmResponse.Messages>(ElmParser.parse(text, headers)).messages

    @Test
    fun `requests go to the profile's headers`() {
        assertEquals(Request(0x7B0, 0x21, 0x41), brake.request)
        assertEquals("2141", brake.command)
        assertEquals(Request(null, 0x01, 0x5A), accelerator.request)
        assertEquals("015A", accelerator.command)
        assertEquals(Request(0x7E0, 0x21, 0x01), oil.request)
        assertEquals("2101", oil.command)
        assertEquals(Request(0x7E1, 0x21, 0x82), atf.request)
        assertEquals("2182", atf.command)
        profile.channels.forEach { assertNotNull(it.compiled, it.equation) }
    }

    @Test
    fun `brake is MIN(100, 1·1 × MAX(A − 10, 0))`() {
        // A = 0x3C = 60: 1.1 × (60 − 10) = 55 %
        assertEquals(55.0, assertNotNull(brake.decode(messages("7B8 03 61 41 3C 00 00 00 00"))), 1e-9)
        // A = 0x64 = 100: 1.1 × 90 = 99 %
        assertEquals(99.0, assertNotNull(brake.decode(messages("7B8 03 61 41 64 00 00 00 00"))), 1e-9)
        // A = 0x0A = 10 (pedal released): 1.1 × 0 = 0 %; A = 5 is below the offset, also 0 %
        assertEquals(0.0, brake.decode(messages("7B8 03 61 41 0A 00 00 00 00")))
        assertEquals(0.0, brake.decode(messages("7B8 03 61 41 05 00 00 00 00")))
        // A = 0xFF = 255: 1.1 × 245 = 269.5, capped at 100 %
        assertEquals(100.0, brake.decode(messages("7B8 03 61 41 FF 00 00 00 00")))
    }

    @Test
    fun `accelerator is MIN(A, 100)`() {
        // A = 0x4B = 75 %
        assertEquals(75.0, accelerator.decode(messages("7E8 03 41 5A 4B 00 00 00 00")))
        // A = 0x6E = 110, capped at 100 %
        assertEquals(100.0, accelerator.decode(messages("41 5A 6E", headers = false)))
    }

    /**
     * 21 01 from the engine ECU: 64 bytes, `61 01` and then data bytes d0..d61 (here d_i = i), sent as
     * an ISO-TP first frame and nine consecutive frames. d28 is 0x7D = 125, so oil is 125 − 40 = 85 °C.
     */
    private val oilResponseHeadersOn = """
        7E8 10 40 61 01 00 01 02 03
        7E8 21 04 05 06 07 08 09 0A
        7E8 22 0B 0C 0D 0E 0F 10 11
        7E8 23 12 13 14 15 16 17 18
        7E8 24 19 1A 1B 7D 1D 1E 1F
        7E8 25 20 21 22 23 24 25 26
        7E8 26 27 28 29 2A 2B 2C 2D
        7E8 27 2E 2F 30 31 32 33 34
        7E8 28 35 36 37 38 39 3A 3B
        7E8 29 3C 3D 00 00 00 00 00

        >
    """.trimIndent()

    /** The same answer with headers off: the length (0x040 = 64) and numbered frames. */
    private val oilResponseHeadersOff = """
        040
        0: 61 01 00 01 02 03
        1: 04 05 06 07 08 09 0A
        2: 0B 0C 0D 0E 0F 10 11
        3: 12 13 14 15 16 17 18
        4: 19 1A 1B 7D 1D 1E 1F
        5: 20 21 22 23 24 25 26
        6: 27 28 29 2A 2B 2C 2D
        7: 2E 2F 30 31 32 33 34
        8: 35 36 37 38 39 3A 3B
        9: 3C 3D 00 00 00 00 00
        >
    """.trimIndent()

    @Test
    fun `engine oil temp is BytesToUInt(raw, 28, 1) − 40, counted after the 61 01 echo`() {
        val on = messages(oilResponseHeadersOn)
        assertEquals(0x7E8, on.single().header)
        assertEquals(64, on.single().bytes.size)
        assertEquals(85.0, oil.decode(on))
        assertEquals(85.0, oil.decode(messages(oilResponseHeadersOff, headers = false)))
        // Counting the echo would read d26 = 0x1A = 26: −14 °C, clearly not a running engine.
        val withEcho = PidChannel("x", "x", 0x7E0, 0x21, 0x01, "BytesToUInt(raw, 26, 1) - 40", "°C", PollClass.SLOW)
        assertEquals(-14.0, withEcho.decode(on))
    }

    @Test
    fun `engine oil temp is hidden when the response is too short`() {
        // 61 01 and 28 data bytes: offset 28 is past the end.
        val short = ByteArray(30).also { it[0] = 0x61; it[1] = 0x01 }
        assertNull(oil.decode(short))
        // Another PID's answer is not this channel's.
        assertNull(oil.decode(hexBytes("61 02 00 00")!!))
    }

    @Test
    fun `ATF temp is (A×256 + B) × 7 ÷ 1800 − 40 in integers`() {
        // A = 0x78 = 120, B = 0x89 = 137: 120×256 + 137 = 30857; ×7 = 215999; ÷1800 = 119.999 → 119; −40 = 79 °C
        assertEquals(79.0, atf.decode(messages("7E9 04 61 82 78 89 00 00 00")))
        // A = 0x5D = 93, B = 0xC0 = 192: 24000; ×7 = 168000; ÷1800 = 93.3 → 93; −40 = 53 °C
        assertEquals(53.0, atf.decode(messages("7E9 04 61 82 5D C0 00 00 00")))
        // Only A: hidden.
        assertNull(atf.decode(messages("7E9 03 61 82 5D 00 00 00 00")))
    }

    @Test
    fun `profile channels replace the generic ones with the same id`() {
        val supported = setOf(0x05, 0x0C, 0x0D, 0x5A, 0x5C)
        val polled = profile.channelsToPoll(supported)
        val ids = polled.map { it.id }
        assertEquals(listOf("coolant_temp", "rpm", "speed", "brake", "accelerator", "oil_temp", "atf_temp"), ids)
        assertEquals("MIN(A, 100)", polled.single { it.id == "accelerator" }.equation)
        assertEquals(0x7E0, polled.single { it.id == "oil_temp" }.header)
        assertTrue("throttle" !in ids) // not in the car's bitmask
        val generic = CarProfile.Generic.channelsToPoll(supported).map { it.id }
        assertEquals(listOf("coolant_temp", "rpm", "speed", "accelerator", "oil_temp"), generic)
    }
}
