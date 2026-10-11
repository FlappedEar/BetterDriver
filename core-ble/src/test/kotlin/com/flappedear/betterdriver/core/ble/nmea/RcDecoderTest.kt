@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.nmea

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RaceChrono DIY $RC2/$RC3 lines through [NmeaDecoder] (FBBD-115). */
class RcDecoderTest {
    private class Sink : SampleSink {
        val channels = mutableListOf<ChannelDef>()
        val sets = mutableListOf<SampleSet>()
        var now = 0L

        override fun defineChannel(def: ChannelDef) {
            channels += def
        }

        override fun submit(set: SampleSet) {
            sets += set
        }

        override fun event(kind: String, text: String) = Unit

        override fun nowMicros() = now
    }

    private fun SampleSet.value(def: ChannelDef) = values.firstOrNull { it.channelId == def.id }?.value

    private fun NmeaDecoder.feed(text: String) = feed(text.toByteArray(Charsets.US_ASCII))

    private fun rc3(count: Int, x: String = "0.100") =
        nmeaSentence("RC3,,$count,$x,-0.200,1.000,1.50,-2.25,3.00,,,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15")

    @Test
    fun `RC channels are defined on the first RC line only, with ids 500 to 509`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        decoder.defineChannels()
        assertTrue(sink.channels.none { it in RcChannels.ALL })
        decoder.feed(rc3(0) + rc3(1))
        assertEquals(RcChannels.ALL, sink.channels.filter { it in RcChannels.ALL })
        assertTrue(RcChannels.ALL.all { it.id in 500..509 })
        val all = sink.channels.map { it.id }
        assertEquals(all.size, all.toSet().size)
        assertEquals(listOf("g", "g", "g", "deg/s", "deg/s", "deg/s"), RcChannels.ALL.map { it.unit })
    }

    @Test
    fun `each RC3 line is one sample set with acceleration and rotation, stamped on arrival`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        sink.now = 7_000
        decoder.feed(rc3(0))
        val s = sink.sets.single()
        assertEquals(7_000L, s.tMicros)
        assertEquals(0.1, s.value(RcChannels.ACCEL_X)!!, 0.0)
        assertEquals(-0.2, s.value(RcChannels.ACCEL_Y)!!, 0.0)
        assertEquals(1.0, s.value(RcChannels.ACCEL_Z)!!, 0.0)
        assertEquals(1.5, s.value(RcChannels.GYRO_X)!!, 0.0)
        assertEquals(-2.25, s.value(RcChannels.GYRO_Y)!!, 0.0)
        assertEquals(3.0, s.value(RcChannels.GYRO_Z)!!, 0.0)
        assertEquals(6, s.values.size)
        assertEquals(0L, decoder.epochs)
    }

    @Test
    fun `RC2 has no rotation, and empty fields are left out`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        decoder.feed(nmeaSentence("RC2,,5,0.5,,-1.0,,,,,,,,,,"))
        val s = sink.sets.single()
        assertEquals(0.5, s.value(RcChannels.ACCEL_X)!!, 0.0)
        assertNull(s.value(RcChannels.ACCEL_Y))
        assertNull(s.value(RcChannels.GYRO_X))
        assertEquals(2, s.values.size)
        decoder.feed(nmeaSentence("RC2,,6,,,,,,,,,,,,,"))
        assertEquals("a line without any motion value adds no set", 1, sink.sets.size)
    }

    @Test
    fun `missed lines are counted from the wrapping line counter`() {
        val decoder = NmeaDecoder(Sink())
        decoder.feed(rc3(65_534) + rc3(65_535) + rc3(0) + rc3(3))
        assertEquals(2L, decoder.rcMissed)
        decoder.reset()
        decoder.feed(rc3(100))
        assertEquals("no gap is counted across a reconnect", 2L, decoder.rcMissed)
    }

    @Test
    fun `RC lines mixed with NMEA leave the fixes alone`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        sink.now = 1_000
        decoder.feed(nmeaSentence("GNGGA,101112.00,5013.1234,N,01902.5678,E,1,9,0.9,250.0,M,40.0,M,,"))
        sink.now = 2_000
        decoder.feed(nmeaSentence("RC2,101112.00,,0.3,0.4,1.0,,,,,,,,,,"))
        sink.now = 3_000
        decoder.feed(nmeaSentence("GNRMC,101112.00,A,5013.1234,N,01902.5678,E,10.0,45.0,111026,,,A"))
        assertEquals(2, sink.sets.size)
        val (rc, fix) = sink.sets
        assertEquals(2_000L, rc.tMicros)
        assertEquals(0.3, rc.value(RcChannels.ACCEL_X)!!, 0.0)
        assertFalse(rc.values.any { it.channelId in GnssChannels.IDS })
        assertEquals(1_000L, fix.tMicros)
        assertEquals(45.0, fix.value(GnssChannels.HEADING)!!, 0.0)
        assertNull(fix.value(RcChannels.ACCEL_X))
        assertEquals(1L, decoder.epochs)
    }

    @Test
    fun `lines with a bad checksum are dropped`() {
        val sink = Sink()
        val decoder = NmeaDecoder(sink)
        decoder.feed(rc3(1).replace("0.100", "0.900"))
        assertTrue(sink.sets.isEmpty())
        assertEquals(1L, decoder.checksumErrors)
    }
}
