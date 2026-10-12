@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** FBBD-53: ELM327 text into per-ECU messages. */
class ElmParserTest {
    private fun msg(header: Int?, hex: String) = EcuMessage(header, hexBytes(hex)!!)

    private fun messages(text: String, headers: Boolean, command: String? = null) =
        assertIs<ElmResponse.Messages>(ElmParser.parse(text, headers, command)).messages

    private fun error(text: String, headers: Boolean = false) =
        assertIs<ElmResponse.Error>(ElmParser.parse(text, headers)).kind

    @Test
    fun `headers off, with and without spaces`() {
        val expected = listOf(msg(null, "41 0C 1A F8"))
        assertEquals(expected, messages("41 0C 1A F8 \r\r>", headers = false))
        assertEquals(expected, messages("410C1AF8\r\r>", headers = false))
        assertEquals(expected, messages("410c1af8\n>", headers = false))
        assertEquals(1726.0, StandardPids.Rpm.decode(expected))
    }

    @Test
    fun `headers on, 11-bit, with and without spaces, padding cut by the PCI length`() {
        val expected = listOf(msg(0x7E8, "41 0C 1A F8"))
        assertEquals(expected, messages("7E8 04 41 0C 1A F8 AA AA AA\r\r>", headers = true))
        assertEquals(expected, messages("7E804410C1AF8AAAAAA\r\r>", headers = true))
        assertEquals(expected, messages("7E804410C1AF8\r>", headers = true))
    }

    @Test
    fun `headers on, 29-bit`() {
        val expected = listOf(msg(0x18DAF110, "41 0D 32"))
        assertEquals(expected, messages("18 DA F1 10 03 41 0D 32 00 00 00 00\r>", headers = true))
        assertEquals(expected, messages("18DAF11003410D3200000000\r>", headers = true))
        assertEquals("18DAF110: 41 0D 32", expected.single().toString())
    }

    @Test
    fun `several ECUs answer one request`() {
        val on = messages("7E8 06 41 00 BE 3F A8 13 00\r7E9 06 41 00 98 18 80 11 00\r\r>", headers = true)
        assertEquals(listOf(msg(0x7E8, "41 00 BE 3F A8 13"), msg(0x7E9, "41 00 98 18 80 11")), on)
        val off = messages("41 00 BE 3F A8 13\r41 00 98 18 80 11\r\r>", headers = false)
        assertEquals(listOf(msg(null, "41 00 BE 3F A8 13"), msg(null, "41 00 98 18 80 11")), off)
    }

    @Test
    fun `multi-frame with headers off`() {
        // 09 02 (VIN) as in the ELM327 data sheet: 0x014 = 20 bytes in frames 0, 1, 2.
        val text = "014\r0: 49 02 01 31 44 34\r1: 47 50 30 30 52 35 35\r2: 42 31 32 33 34 35 36\r\r>"
        val expected = msg(null, "49 02 01 31 44 34 47 50 30 30 52 35 35 42 31 32 33 34 35 36")
        assertEquals(listOf(expected), messages(text, headers = false))
        // Without spaces, and with a single-frame answer from another ECU after it.
        val compact = "014\r0:490201314434\r1:47503030523535\r2:42313233343536\r490201FF\r>"
        assertEquals(listOf(expected, msg(null, "49 02 01 FF")), messages(compact, headers = false))
    }

    @Test
    fun `multi-frame with headers on, interleaved from two ECUs`() {
        val text = """
            7E8 10 0A 61 01 01 02 03 04
            7E9 10 09 61 01 0A 0B 0C 0D
            7E8 21 05 06 07 08 00 00 00
            7E9 21 0E 0F 10 00 00 00 00
            >
        """.trimIndent()
        assertEquals(
            listOf(msg(0x7E8, "61 01 01 02 03 04 05 06 07 08"), msg(0x7E9, "61 01 0A 0B 0C 0D 0E 0F 10")),
            messages(text, headers = true),
        )
    }

    @Test
    fun `consecutive frame numbers wrap from F to 0`() {
        // 6 + 16 × 7 = 118 bytes: frames 21..2F, then 20.
        val length = 6 + 16 * 7
        val lines = ArrayList<String>()
        var n = 0
        fun next(count: Int) = (0 until count).joinToString(" ") { hex(n++ and 0xFF, 2) }
        lines += "7E8 10 ${hex(length, 2)} ${next(6)}"
        for (i in 1..16) lines += "7E8 2${hex(i and 0xF, 1)} ${next(7)}"
        val bytes = messages(lines.joinToString("\r"), headers = true).single().bytes
        assertEquals(length, bytes.size)
        assertEquals((0 until length).map { it.toByte() }, bytes.toList())
    }

    @Test
    fun `progress lines, the prompt and the echo are skipped`() {
        assertEquals(listOf(msg(null, "41 0D 32")), messages("SEARCHING...\r41 0D 32\r\r>", headers = false))
        assertEquals(listOf(msg(null, "41 0D 32")), messages("BUS INIT: ...OK\r41 0D 32\r>", headers = false))
        assertEquals(listOf(msg(null, "41 0D 32")), messages("010D\r41 0D 32\r\r>", headers = false, command = "01 0D"))
    }

    @Test
    fun `no data and errors`() {
        assertEquals(ElmResponse.NoData, ElmParser.parse("NO DATA\r\r>", headers = false))
        assertEquals(ElmResponse.NoData, ElmParser.parse("SEARCHING...\rNO DATA\r\r>", headers = true))
        assertEquals(ElmResponse.NoData, ElmParser.parse(">", headers = false))
        assertEquals(ElmError.UNKNOWN_COMMAND, error("?\r\r>"))
        assertEquals(ElmError.STOPPED, error("STOPPED\r\r>"))
        assertEquals(ElmError.CAN_ERROR, error("CAN ERROR\r\r>", headers = true))
        assertEquals(ElmError.BUS_ERROR, error("BUS ERROR\r>"))
        assertEquals(ElmError.BUFFER_FULL, error("7E8 10 40 61 01 00 01 02 03\rBUFFER FULL\r>", headers = true))
        assertEquals(ElmError.DATA_ERROR, error("41 0C 1A F8 <DATA ERROR\r>"))
        assertEquals(ElmError.RX_ERROR, error("<RX ERROR\r>"))
        assertEquals(ElmError.UNABLE_TO_CONNECT, error("SEARCHING...\rUNABLE TO CONNECT\r\r>"))
        assertEquals(ElmError.LOW_VOLTAGE_RESET, error("LV RESET\r>"))
        assertEquals(ElmError.OTHER, error("ERR94\r>"))
        assertEquals(ElmError.OTHER, error("BUS INIT: ...ERROR\r>"))
        assertEquals(ElmError.OTHER, error("ACT ALERT\r>"))
    }

    @Test
    fun `garbled and incomplete answers`() {
        assertEquals(ElmError.GARBLED, error("41 0C 1A F\r>"))
        assertEquals(ElmError.GARBLED, error("41 0C ZZ\r>"))
        assertEquals(ElmError.GARBLED, error("0: 49 02 01\r>")) // a numbered frame with no length line
        assertEquals(ElmError.GARBLED, error("7E8\r>", headers = true))
        // Frame 22 missing.
        assertEquals(
            ElmError.INCOMPLETE,
            error("7E8 10 14 61 01 00 01 02 03\r7E8 21 04 05 06 07 08 09 0A\r>", headers = true),
        )
        // Frames out of order.
        assertEquals(
            ElmError.INCOMPLETE,
            error("7E8 10 0A 61 01 01 02 03 04\r7E8 22 05 06 07 08 00 00 00\r>", headers = true),
        )
        assertEquals(ElmError.INCOMPLETE, error("014\r0: 49 02 01 31 44 34\r1: 47 50 30 30 52 35 35\r>"))
        // One ECU complete, another cut short: the complete one is still used.
        val partial = "7E9 03 61 82 5D 00 00 00 00\r7E8 10 14 61 01 00 01 02 03\r>"
        assertEquals(listOf(msg(0x7E9, "61 82 5D")), messages(partial, headers = true))
    }

    @Test
    fun `a negative response is a message no channel decodes`() {
        val response = messages("7E8 03 7F 21 12 00 00 00 00\r>", headers = true)
        assertEquals(listOf(msg(0x7E8, "7F 21 12")), response)
        assertEquals(null, CarProfiles.ScionFrs2013.channels.first { it.id == "oil_temp" }.decode(response))
    }
}
