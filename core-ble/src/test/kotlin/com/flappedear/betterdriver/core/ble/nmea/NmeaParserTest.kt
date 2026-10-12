@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.nmea

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaParserTest {
    private fun lines(vararg chunks: String): List<String> {
        val reader = NmeaLineReader()
        val out = mutableListOf<String>()
        chunks.forEach { chunk -> reader.feed(chunk.toByteArray(Charsets.US_ASCII)) { out += it } }
        return out
    }

    private fun epochs(text: String, chunkSize: Int = text.length): List<NmeaEpoch> {
        val parser = NmeaParser()
        val out = mutableListOf<NmeaEpoch>()
        text.chunked(chunkSize).forEachIndexed { i, chunk ->
            parser.feed(chunk.toByteArray(Charsets.US_ASCII), i.toLong()) { out += it }
        }
        return out
    }

    @Test
    fun `checksum matches the textbook sentences`() {
        // The widely quoted examples from the NMEA 0183 descriptions.
        assertEquals(0x6A, nmeaChecksum("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"))
        assertEquals(0x47, nmeaChecksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
        assertEquals(
            listOf("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"),
            lines("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"),
        )
    }

    @Test
    fun `sentences split across reads are reassembled`() {
        val text = nmeaSentence("GNRMC,101112.00,A,5013.1234,N,01902.5678,E,10.0,90.0,111026,,,A") +
            nmeaSentence("GNGGA,101112.00,5013.1234,N,01902.5678,E,1,12,0.8,250.5,M,40.0,M,,")
        for (size in 1..text.length) {
            val got = lines(*text.chunked(size).toTypedArray())
            assertEquals("chunk size $size", 2, got.size)
            assertTrue(got[0].startsWith("GNRMC,"))
        }
    }

    @Test
    fun `bad checksums, missing checksums and garbage are skipped`() {
        val good = nmeaSentence("GPVTG,084.4,T,,M,022.4,N,041.5,K,A")
        val reader = NmeaLineReader()
        val out = mutableListOf<String>()
        val bad = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6B\r\n"
        val noChecksum = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,\r\n"
        val binary = byteArrayOf(0xB5.toByte(), 0x62, 0x01, 0x07, 0x00, 0x24, 0x0A)
        reader.feed(binary) { out += it }
        reader.feed("041.5,K,A*00\n\$GPGG".toByteArray()) { out += it }
        reader.feed((bad + noChecksum + good).toByteArray()) { out += it }
        assertEquals(listOf(good.trim().drop(1).substringBefore('*')), out)
        assertEquals(1, reader.checksumErrors)
        // The 0x24 in the binary (an empty "$" line), "$GPGG" cut by the next '$', and the line without a checksum.
        assertEquals(3, reader.discarded)
    }

    @Test
    fun `lowercase checksum digits and LF-only line ends are accepted`() {
        val body = "GPVTG,084.4,T,,M,022.4,N,041.5,K,A"
        val line = "$" + body + "*" + "%02x".format(nmeaChecksum(body)) + "\n"
        assertEquals(listOf(body, body), lines(line, line))
    }

    @Test
    fun `parses RMC from any talker`() {
        val rmc = parseNmeaSentence("GNRMC,235959.50,A,5013.1234,S,01902.5678,W,10.0,271.3,311226,,,D") as Rmc
        assertEquals("GN", rmc.talker)
        assertEquals((23 * 3_600 + 59 * 60 + 59) * 1_000L + 500, rmc.timeOfDayMs)
        assertTrue(rmc.valid)
        assertEquals(-(50 + 13.1234 / 60), rmc.latDeg!!, 1e-12)
        assertEquals(-(19 + 2.5678 / 60), rmc.lonDeg!!, 1e-12)
        assertEquals(10.0, rmc.speedKnots!!, 0.0)
        assertEquals(271.3, rmc.courseDeg!!, 0.0)
        assertEquals(LocalDate.of(2026, 12, 31).toEpochDay(), rmc.epochDay)
        for (talker in listOf("GP", "GL", "GA", "GB", "BD")) {
            assertTrue(parseNmeaSentence("${talker}RMC,000000,A,0000.0000,N,00000.0000,E,0,0,010126,,") is Rmc)
        }
    }

    @Test
    fun `RMC with status V or mode N is not valid`() {
        assertFalse((parseNmeaSentence("GPRMC,,V,,,,,,,,,,N") as Rmc).valid)
        assertFalse((parseNmeaSentence("GPRMC,101112,A,5013.1,N,01902.5,E,0,,111026,,,N") as Rmc).valid)
        val noFix = parseNmeaSentence("GPRMC,,V,,,,,,,,,,N") as Rmc
        assertNull(noFix.timeOfDayMs)
        assertNull(noFix.latDeg)
        assertNull(noFix.epochDay)
    }

    @Test
    fun `parses GGA, VTG and GSA`() {
        val gga = parseNmeaSentence("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,") as Gga
        assertEquals(48.1173, gga.latDeg!!, 1e-9)
        assertEquals(11.516666666, gga.lonDeg!!, 1e-8)
        assertEquals(8, gga.satellites)
        assertEquals(0.9, gga.hdop!!, 0.0)
        assertEquals(545.4, gga.altitudeM!!, 0.0)
        assertTrue(gga.hasFix)
        assertFalse((parseNmeaSentence("GPGGA,123519,,,,,0,00,99.9,,M,,M,,") as Gga).hasFix)

        val vtg = parseNmeaSentence("GNVTG,054.7,T,034.4,M,005.5,N,010.2,K,A") as Vtg
        assertEquals(54.7, vtg.courseDeg!!, 0.0)
        assertEquals(10.2, vtg.speedKmh!!, 0.0)
        val knotsOnly = parseNmeaSentence("GPVTG,054.7,T,,M,005.5,N,,K") as Vtg
        assertEquals(5.5 * 1.852, knotsOnly.speedKmh!!, 1e-9)
        assertNull(parseNmeaSentence("GPVTG,,T,,M,,N,,K,N"))

        val gsa = parseNmeaSentence("GNGSA,A,3,01,02,03,04,05,06,,,,,,,1.8,1.0,1.5,1") as Gsa
        assertEquals(3, gsa.fixType)
        assertEquals(1.8, gsa.pdop!!, 0.0)
        assertEquals(1.0, gsa.hdop!!, 0.0)
    }

    @Test
    fun `ignores other and proprietary sentences and bad fields`() {
        assertNull(parseNmeaSentence("GPGSV,3,1,11,03,03,111,00,04,15,270,00,06,01,010,00,13,06,292,00"))
        assertNull(parseNmeaSentence("PGRME,15.0,M,45.0,M,25.0,M"))
        assertNull(parseNmeaSentence("GPRMC,123519,A"))
        assertNull(timeOfDay("246000"))
        assertNull(coordinate("4867.000", "N", 90.0))
        assertNull(coordinate("9100.000", "N", 90.0))
        assertNull(date("310226"))
    }

    @Test
    fun `one epoch per fix as soon as RMC and GGA are both in`() {
        // SiRF/MediaTek order: GGA GSA RMC VTG.
        val text = (101112..101114).joinToString("") { t ->
            nmeaSentence("GPGGA,$t.000,5013.1234,N,01902.5678,E,1,09,0.9,250.5,M,40.0,M,,") +
                nmeaSentence("GPGSA,A,3,01,02,03,04,05,06,07,08,09,,,,1.6,0.9,1.3") +
                nmeaSentence("GPGSV,3,1,11,03,03,111,00,04,15,270,00,06,01,010,00,13,06,292,00") +
                nmeaSentence("GPRMC,$t.000,A,5013.1234,N,01902.5678,E,20.0,90.0,111026,,,A") +
                nmeaSentence("GPVTG,90.0,T,,M,20.0,N,37.0,K,A")
        }
        val got = epochs(text, chunkSize = 7)
        assertEquals(3, got.size)
        got.forEach { assertTrue(it.rmc != null && it.gga != null && it.gsa != null) }
        // The first VTG comes after its epoch was passed on; from then on each epoch waits for it.
        assertEquals(listOf(null, 37.0, 37.0), got.map { it.vtg?.speedKmh })
        assertEquals(listOf(36_672_000L, 36_673_000L, 36_674_000L), got.map { it.timeOfDayMs })
    }

    @Test
    fun `without GGA an epoch ends when the time changes`() {
        // u-blox order with GGA turned off: RMC VTG.
        val text = (101112..101114).joinToString("") { t ->
            nmeaSentence("GNRMC,$t.00,A,5013.1234,N,01902.5678,E,20.0,90.0,111026,,,A") +
                nmeaSentence("GNVTG,90.0,T,,M,20.0,N,37.0,K,A")
        }
        val sizes = mutableListOf<NmeaEpoch>()
        val parser = NmeaParser()
        text.lines().filter { it.isNotEmpty() }.forEachIndexed { i, line ->
            parser.feed((line + "\n").toByteArray(), i * 100L) { sizes += it }
        }
        // The first fix waits for the next one to end it; after that each one is passed on as soon
        // as its RMC and VTG are in, so the last fix is not held back.
        assertEquals(3, sizes.size)
        assertTrue(sizes.all { it.vtg?.speedKmh == 37.0 })
        // Stamped when the fix's first sentence arrived, not when the next one ended it.
        assertEquals(listOf(0L, 200L, 400L), sizes.map { it.arrivalMicros })
    }

    @Test
    fun `a receiver that sends GGA after all is waited for again`() {
        val rmc = { t: Int -> nmeaSentence("GPRMC,$t.00,A,5013.1234,N,01902.5678,E,20.0,90.0,111026,,,A") }
        val gga = { t: Int -> nmeaSentence("GPGGA,$t.00,5013.1234,N,01902.5678,E,1,09,0.9,250.5,M,40.0,M,,") }
        // The first GGA was lost, so the first fix looks like RMC only.
        val got = epochs(rmc(101112) + rmc(101113) + gga(101113) + rmc(101114) + gga(101114))
        assertEquals(listOf(null, null, 9), got.map { it.gga?.satellites })
    }

    @Test
    fun `a second talker's copy of a sentence does not make a second epoch`() {
        val text = nmeaSentence("GPRMC,101112.00,A,5013.1234,N,01902.5678,E,20.0,90.0,111026,,,A") +
            nmeaSentence("GNRMC,101112.00,A,5013.1234,N,01902.5678,E,20.0,90.0,111026,,,A") +
            nmeaSentence("GNGGA,101112.00,5013.1234,N,01902.5678,E,1,12,0.8,250.5,M,40.0,M,,") +
            nmeaSentence("GPGGA,101112.00,5013.1234,N,01902.5678,E,1,06,1.2,250.5,M,40.0,M,,")
        val got = epochs(text)
        assertEquals(1, got.size)
        assertEquals("GP", got.single().rmc!!.talker)
        assertEquals(12, got.single().gga!!.satellites)
    }

    @Test
    fun `fixes without a time still make one epoch each`() {
        val noFix = nmeaSentence("GPGGA,,,,,,0,00,99.9,,M,,M,,") + nmeaSentence("GPRMC,,V,,,,,,,,,,N")
        assertEquals(3, epochs(noFix + noFix + noFix).size)
    }
}
