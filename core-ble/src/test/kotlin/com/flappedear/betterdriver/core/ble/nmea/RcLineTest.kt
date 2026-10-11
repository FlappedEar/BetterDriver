@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RcLineTest {
    /** The first $RC3 example of RaceChrono's "Tutorial: DIY devices" (racechrono.com/article/2572). */
    private val tutorialRc3 =
        "\$RC3,,2,0.240,-0.560,-0.290,7.938,-0.125,0.063,,,7.063,-40.438,2.625,70.875,9.875,-48.250," +
            "-6.360,-0.340,7.450,-6.100,-0.940,7.170,0,1,10*2F\r\n"

    private fun lines(text: String): List<String> {
        val out = mutableListOf<String>()
        NmeaLineReader().feed(text.toByteArray(Charsets.US_ASCII)) { out += it }
        return out
    }

    @Test
    fun `the tutorial's RC3 example passes the checksum and parses`() {
        val body = lines(tutorialRc3).single()
        assertTrue(isRcLine(body))
        val rc = parseRcLine(body)!!
        assertEquals(3, rc.version)
        assertNull(rc.timeOfDayMs)
        assertEquals(2, rc.count)
        assertEquals(0.240, rc.accelXg!!, 0.0)
        assertEquals(-0.560, rc.accelYg!!, 0.0)
        assertEquals(-0.290, rc.accelZg!!, 0.0)
        assertEquals(7.938, rc.gyroXdps!!, 0.0)
        assertEquals(-0.125, rc.gyroYdps!!, 0.0)
        assertEquals(0.063, rc.gyroZdps!!, 0.0)
        assertEquals(listOf<Double?>(null, null), rc.digital)
        assertEquals(15, rc.analog.size)
        assertEquals(7.063, rc.analog.first()!!, 0.0)
        assertEquals(10.0, rc.analog.last()!!, 0.0)
    }

    @Test
    fun `RC2 has acceleration, digital and 8 analog fields and no gyro`() {
        val body = "RC2,101112.50,65535,1.000,-1.000,0.981,6500,1,1,2,3,4,5,6,7,8"
        assertEquals(body, lines(nmeaSentence(body)).single())
        val rc = parseRcLine(body)!!
        assertEquals(2, rc.version)
        assertEquals((10 * 3600 + 11 * 60 + 12) * 1000L + 500, rc.timeOfDayMs)
        assertEquals(65535, rc.count)
        assertEquals(1.0, rc.accelXg!!, 0.0)
        assertEquals(-1.0, rc.accelYg!!, 0.0)
        assertEquals(0.981, rc.accelZg!!, 0.0)
        assertNull(rc.gyroXdps)
        assertEquals(listOf<Double?>(6500.0, 1.0), rc.digital)
        assertEquals((1..8).map { it.toDouble() }, rc.analog)
    }

    @Test
    fun `mixed output has an empty count, and short lines get empty trailing fields`() {
        val rc = parseRcLine("RC2,101112.00,,0.1,0.2,0.3")!!
        assertNull(rc.count)
        assertEquals(0.3, rc.accelZg!!, 0.0)
        assertEquals(2, rc.digital.size)
        assertTrue(rc.digital.all { it == null })
        assertEquals(8, rc.analog.size)
    }

    @Test
    fun `values that are not numbers or out of range are empty`() {
        val rc = parseRcLine("RC3,,1,abc,NaN,2000000.001,-2000000.000,,1e3")!!
        assertNull(rc.accelXg)
        assertNull(rc.accelYg)
        assertNull(rc.accelZg)
        assertEquals(-2_000_000.0, rc.gyroXdps!!, 0.0)
        assertNull(rc.gyroYdps)
        assertEquals(1000.0, rc.gyroZdps!!, 0.0)
    }

    @Test
    fun `other sentences, extra fields and bad counts are rejected`() {
        assertFalse(isRcLine("GPRMC,1,2"))
        assertFalse(isRcLine("RC1,,1,0,0,0"))
        assertNull(parseRcLine("RC1,,1,0,0,0"))
        assertNull(parseRcLine("GNGGA,101112.00"))
        assertNull(parseRcLine("RC2,,1,0,0,0,0,0,1,2,3,4,5,6,7,8,9"))
        assertNull(parseRcLine("RC2,,65536,0,0,0"))
        assertNull(parseRcLine("RC2,,-1,0,0,0"))
        assertNull(parseRcLine("RC2,,x,0,0,0"))
        assertTrue(parseRcLine("RC2") == null)
    }

    @Test
    fun `an RC3 line with every field at its widest fits the line reader`() {
        val body = "RC3,235959.999,65535," + List(23) { "-1999999.999" }.joinToString(",")
        assertTrue(body.length <= MaxNmeaSentenceChars - 3)
        assertEquals(body, lines(nmeaSentence(body)).single())
        assertEquals(-1_999_999.999, parseRcLine(body)!!.analog.last()!!, 0.0)
    }
}
