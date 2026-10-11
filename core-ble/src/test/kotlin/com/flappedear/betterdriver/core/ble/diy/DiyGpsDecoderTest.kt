@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.diy

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiyGpsDecoderTest {
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

    private val sink = Sink()
    private var timeRequests = 0
    private val decoder = DiyGpsDecoder(sink) { timeRequests++ }

    private fun SampleSet.value(def: ChannelDef) = values.firstOrNull { it.channelId == def.id }?.value

    private fun main(sync: Int, second: Int, fix: Boolean = true) = DiyTestData.main(
        sync, minute = 5, second = second, ms = 250, fixQuality = if (fix) 1 else 0, satellites = 11,
        latDeg = 50.5, lonDeg = 19.25, altitudeM = 300.0, speedKmh = 88.5, bearingDeg = 271.5,
        hdop = 0.9, vdop = 1.4,
    )

    private fun feedAt(micros: Long, uuid: UUID, bytes: ByteArray) {
        sink.now = micros
        decoder.feed(uuid, bytes)
    }

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli().toDouble()

    @Test
    fun `defines GNSS and DIY channels with ids 510 to 519`() {
        decoder.defineChannels()
        assertTrue(sink.channels.containsAll(GnssChannels.ALL))
        assertTrue(DiyChannels.ALL.all { it.id in 510..519 })
        assertEquals(sink.channels.size, sink.channels.map { it.id }.toSet().size)
    }

    @Test
    fun `a GPS main value with its time is one sample set with UTC, stamped on arrival`() {
        feedAt(1_000, DiyGatt.GPS_TIME, DiyTestData.time(3, 2026, 10, 11, 14))
        feedAt(2_000, DiyGatt.GPS_MAIN, main(3, 7))
        val s = sink.sets.single()
        assertEquals(2_000L, s.tMicros)
        assertEquals(utc("2026-10-11T14:05:07.250Z"), s.value(GnssChannels.UTC_MS)!!, 0.0)
        assertEquals(50.5, s.value(GnssChannels.LAT)!!, 1e-9)
        assertEquals(19.25, s.value(GnssChannels.LON)!!, 1e-9)
        assertEquals(88.5, s.value(GnssChannels.SPEED_KMH)!!, 1e-9)
        assertEquals(271.5, s.value(GnssChannels.HEADING)!!, 1e-9)
        assertEquals(300.0, s.value(GnssChannels.ALT_M)!!, 1e-9)
        assertEquals(11.0, s.value(GnssChannels.SATS)!!, 0.0)
        assertEquals(1.0, s.value(DiyChannels.FIX_QUALITY)!!, 0.0)
        assertEquals(0.9, s.value(DiyChannels.HDOP)!!, 1e-9)
        assertEquals(1.4, s.value(DiyChannels.VDOP)!!, 1e-9)
        assertEquals(listOf(DiyEvents.FIX_ACQUIRED to "sats=11"), sink.events)
        assertEquals(0, timeRequests)
        assertEquals(1L, decoder.fixes)
    }

    @Test
    fun `a main value waits for the time with its sync bits, which is asked for`() {
        feedAt(0, DiyGatt.GPS_MAIN, main(1, 0))
        feedAt(100_000, DiyGatt.GPS_MAIN, main(1, 1))
        assertTrue(sink.sets.isEmpty())
        assertEquals("asked once, not per value", 1, timeRequests)
        feedAt(150_000, DiyGatt.GPS_TIME, DiyTestData.time(1, 2026, 10, 11, 9))
        assertEquals(listOf(0L, 100_000L), sink.sets.map { it.tMicros })
        assertEquals(utc("2026-10-11T09:05:00.250Z"), sink.sets[0].value(GnssChannels.UTC_MS)!!, 0.0)
        assertEquals(utc("2026-10-11T09:05:01.250Z"), sink.sets[1].value(GnssChannels.UTC_MS)!!, 0.0)
    }

    @Test
    fun `without a matching time a main value goes on after 1 s without UTC, and the time is asked again`() {
        feedAt(0, DiyGatt.GPS_TIME, DiyTestData.time(1, 2026, 10, 11, 9))
        feedAt(10_000, DiyGatt.GPS_MAIN, main(2, 0))
        feedAt(900_000, DiyGatt.GPS_MAIN, main(2, 1))
        assertTrue(sink.sets.isEmpty())
        feedAt(1_010_000, DiyGatt.GPS_MAIN, main(2, 2))
        assertEquals(1, sink.sets.size)
        assertNull(sink.sets[0].value(GnssChannels.UTC_MS))
        assertEquals(50.5, sink.sets[0].value(GnssChannels.LAT)!!, 1e-9)
        assertEquals(2, timeRequests)
    }

    @Test
    fun `around the hour, values with the previous sync bits still find their time`() {
        feedAt(0, DiyGatt.GPS_TIME, DiyTestData.time(4, 2026, 10, 11, 9))
        feedAt(1, DiyGatt.GPS_TIME, DiyTestData.time(5, 2026, 10, 11, 10))
        feedAt(2, DiyGatt.GPS_MAIN, main(4, 59))
        feedAt(3, DiyGatt.GPS_MAIN, main(5, 0))
        assertEquals(utc("2026-10-11T09:05:59.250Z"), sink.sets[0].value(GnssChannels.UTC_MS)!!, 0.0)
        assertEquals(utc("2026-10-11T10:05:00.250Z"), sink.sets[1].value(GnssChannels.UTC_MS)!!, 0.0)
    }

    @Test
    fun `no position without a fix, and fix changes are events`() {
        feedAt(0, DiyGatt.GPS_TIME, DiyTestData.time(0, 2026, 10, 11, 9))
        feedAt(1, DiyGatt.GPS_MAIN, main(0, 0, fix = false))
        feedAt(2, DiyGatt.GPS_MAIN, main(0, 1))
        feedAt(3, DiyGatt.GPS_MAIN, main(0, 2, fix = false))
        val (none, fix, lost) = sink.sets
        assertNull(none.value(GnssChannels.LAT))
        assertNull(none.value(GnssChannels.SPEED_KMH))
        assertEquals(0.0, none.value(DiyChannels.FIX_QUALITY)!!, 0.0)
        assertTrue(none.value(GnssChannels.UTC_MS) != null)
        assertEquals(50.5, fix.value(GnssChannels.LAT)!!, 1e-9)
        assertNull(lost.value(GnssChannels.LAT))
        assertEquals(listOf(DiyEvents.FIX_ACQUIRED, DiyEvents.FIX_LOST), sink.events.map { it.first })
    }

    @Test
    fun `reset passes held values on without UTC and forgets the times`() {
        feedAt(0, DiyGatt.GPS_TIME, DiyTestData.time(1, 2026, 10, 11, 9))
        feedAt(1, DiyGatt.GPS_MAIN, main(2, 0))
        decoder.reset()
        assertNull(sink.sets.single().value(GnssChannels.UTC_MS))
        feedAt(2, DiyGatt.GPS_MAIN, main(1, 1))
        assertEquals("the old time is gone", 1, sink.sets.size)
    }

    @Test
    fun `other characteristics and short values are ignored`() {
        feedAt(0, DiyGatt.CAN_MAIN, ByteArray(20))
        feedAt(1, DiyGatt.GPS_MAIN, ByteArray(10))
        feedAt(2, DiyGatt.GPS_TIME, ByteArray(1))
        assertTrue(sink.sets.isEmpty())
        assertEquals(0, timeRequests)
    }
}
