@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.nmea

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaDecoderTest {
    private class Sink : SampleSink {
        val channels = mutableListOf<ChannelDef>()
        val sets = mutableListOf<SampleSet>()
        val events = mutableListOf<Pair<String, String>>()
        var now = 0L

        override fun defineChannel(def: ChannelDef) {
            channels += def
        }

        override fun submit(set: SampleSet) {
            sets += set
        }

        override fun event(kind: String, text: String) {
            events += kind to text
        }

        override fun nowMicros() = now
    }

    private fun SampleSet.value(def: ChannelDef) = values.firstOrNull { it.channelId == def.id }?.value

    private fun NmeaDecoder.feed(text: String) = feed(text.toByteArray(Charsets.US_ASCII))

    private fun rmc(time: String, date: String = "111026", status: String = "A"): String {
        val mode = if (status == "A") "A" else "N"
        return nmeaSentence("GNRMC,$time,$status,5013.1234,N,01902.5678,E,20.0,90.5,$date,,,$mode")
    }

    private fun gga(time: String, quality: Int = 1, talker: String = "GN") =
        nmeaSentence("${talker}GGA,$time,5013.1234,N,01902.5678,E,$quality,12,0.8,250.5,M,40.0,M,,")

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli().toDouble()

    @Test
    fun `defines GNSS and NMEA channels with unique ids`() {
        val sink = Sink()
        NmeaDecoder(sink).defineChannels()
        assertEquals(sink.channels.size, sink.channels.map { it.id }.toSet().size)
        assertTrue(sink.channels.containsAll(GnssChannels.ALL))
        assertTrue(NmeaChannels.ALL.all { it.id in 200..299 })
    }

    @Test
    fun `one sample set per fix with GNSS values`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        sink.now = 5_000
        decoder.feed(gga("101112.20") + nmeaSentence("GNGSA,A,3,01,02,03,04,05,06,,,,,,,1.8,1.0,1.5,1"))
        sink.now = 9_000
        decoder.feed(rmc("101112.20"))
        val s = sink.sets.single()
        assertEquals(5_000L, s.tMicros)
        assertEquals(utc("2026-10-11T10:11:12.200Z"), s.value(GnssChannels.UTC_MS)!!, 0.0)
        assertEquals(50 + 13.1234 / 60, s.value(GnssChannels.LAT)!!, 1e-12)
        assertEquals(19 + 2.5678 / 60, s.value(GnssChannels.LON)!!, 1e-12)
        assertEquals(20.0 * 1.852, s.value(GnssChannels.SPEED_KMH)!!, 1e-9)
        assertEquals(90.5, s.value(GnssChannels.HEADING)!!, 0.0)
        assertEquals(250.5, s.value(GnssChannels.ALT_M)!!, 0.0)
        assertEquals(12.0, s.value(GnssChannels.SATS)!!, 0.0)
        assertEquals(1.0, s.value(NmeaChannels.FIX_QUALITY)!!, 0.0)
        assertEquals(3.0, s.value(NmeaChannels.FIX_TYPE)!!, 0.0)
        assertEquals(0.8, s.value(NmeaChannels.HDOP)!!, 0.0)
        assertEquals(1.8, s.value(NmeaChannels.PDOP)!!, 0.0)
        assertEquals(listOf(NmeaEvents.FIX_ACQUIRED to "sats=12"), sink.events)
        assertEquals(1, decoder.epochs)
    }

    @Test
    fun `no fix gives no position, and fix changes are events`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        decoder.feed(rmc("101112.00", status = "V") + gga("101112.00", quality = 0))
        decoder.feed(rmc("101113.00") + gga("101113.00"))
        decoder.feed(rmc("101114.00", status = "V") + gga("101114.00"))
        decoder.feed(rmc("101115.00") + gga("101115.00", quality = 0))
        assertEquals(4, sink.sets.size)
        val withPosition = sink.sets.map { it.value(GnssChannels.LAT) != null }
        assertEquals(listOf(false, true, false, false), withPosition)
        val noFix = sink.sets.first()
        assertNull(noFix.value(GnssChannels.SPEED_KMH))
        assertNull(noFix.value(GnssChannels.HEADING))
        assertNull(noFix.value(GnssChannels.ALT_M))
        assertEquals(12.0, noFix.value(GnssChannels.SATS)!!, 0.0)
        assertEquals(utc("2026-10-11T10:11:12Z"), noFix.value(GnssChannels.UTC_MS)!!, 0.0)
        // No "lost" before the first fix; then acquired, lost, and no repeat while still lost.
        assertEquals(
            listOf(NmeaEvents.FIX_ACQUIRED, NmeaEvents.FIX_LOST),
            sink.events.map { it.first },
        )
    }

    @Test
    fun `a receiver without a fix or time submits nothing at 0, 0`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        decoder.feed(nmeaSentence("GPRMC,,V,,,,,,,,,,N") + nmeaSentence("GPGGA,,,,,,0,00,99.9,,M,,M,,"))
        val s = sink.sets.single()
        assertFalse(s.values.any { it.channelId == GnssChannels.LAT.id || it.channelId == GnssChannels.UTC_MS.id })
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `date rolls over with the RMC date`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        decoder.feed(rmc("235959.00", date = "311226") + gga("235959.00"))
        decoder.feed(rmc("000000.00", date = "010127") + gga("000000.00"))
        val times = sink.sets.map { it.value(GnssChannels.UTC_MS)!! }
        assertEquals(utc("2026-12-31T23:59:59Z"), times[0], 0.0)
        assertEquals(utc("2027-01-01T00:00:00Z"), times[1], 0.0)
    }

    @Test
    fun `fixes without a date use the last one and pass midnight`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        decoder.feed(rmc("235958.00", date = "311226") + gga("235958.00"))
        // GGA only from here: each fix ends when the next one starts.
        decoder.feed(gga("235959.00") + gga("000000.00") + gga("000001.00"))
        val times = sink.sets.map { it.value(GnssChannels.UTC_MS)!! }
        assertEquals(
            listOf("2026-12-31T23:59:58Z", "2026-12-31T23:59:59Z", "2027-01-01T00:00:00Z").map(::utc),
            times,
        )
        // A GGA-only fix has a position but no speed or heading.
        assertTrue(sink.sets[1].value(GnssChannels.LAT) != null)
        assertNull(sink.sets[1].value(GnssChannels.SPEED_KMH))
    }

    @Test
    fun `speed from VTG when there is no RMC`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        val vtg = nmeaSentence("GPVTG,054.7,T,034.4,M,005.5,N,010.2,K,A")
        decoder.feed(gga("101112.00", talker = "GP") + vtg + gga("101113.00", talker = "GP") + vtg)
        val s = sink.sets.single()
        assertEquals(10.2, s.value(GnssChannels.SPEED_KMH)!!, 0.0)
        assertEquals(54.7, s.value(GnssChannels.HEADING)!!, 0.0)
        // GGA has no date and none was seen yet, so there is no UTC time.
        assertNull(s.value(GnssChannels.UTC_MS))
    }

    @Test
    fun `bad checksum is ignored and reset drops a partial sentence`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        val good = rmc("101112.00")
        val corrupted = good.replace("5013.1234", "5913.1234")
        decoder.feed(corrupted + gga("101112.00"))
        assertTrue(sink.sets.isEmpty())
        assertEquals(1, decoder.checksumErrors)
        decoder.feed(good.substring(0, 20))
        decoder.reset()
        decoder.feed(good.substring(20) + good + gga("101112.00"))
        assertEquals(1, sink.sets.size)
        assertEquals(50 + 13.1234 / 60, sink.sets.single().value(GnssChannels.LAT)!!, 1e-12)
    }
}
