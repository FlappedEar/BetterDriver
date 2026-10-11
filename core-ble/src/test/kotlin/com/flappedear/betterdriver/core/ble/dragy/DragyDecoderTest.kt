@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.dragy

import com.flappedear.betterdriver.core.ble.ubxPacket
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragyDecoderTest {
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

    @Test
    fun `defines GNSS and Dragy channels with unique ids in 620 to 639`() {
        val sink = Sink()
        DragyDecoder(sink).defineChannels()
        assertEquals(sink.channels.size, sink.channels.map { it.id }.toSet().size)
        assertTrue(sink.channels.containsAll(GnssChannels.ALL))
        assertTrue(DragyChannels.ALL.all { it.id in 620..639 })
    }

    @Test
    fun `one sample set per NAV-PVT from fragmented notifications`() {
        val sink = Sink()
        val decoder = DragyDecoder(sink)
        sink.now = 4_000
        // dragy-dash: CoreBluetooth "commonly delivers fragments, often in small chunks".
        navPvtPacket().toList().chunked(20).forEach { decoder.feed(it.toByteArray()) }
        val s = sink.sets.single()
        assertEquals(4_000L, s.tMicros)
        val utc = Instant.parse("2026-10-11T10:11:12.200Z").toEpochMilli().toDouble()
        assertEquals(utc, s.value(GnssChannels.UTC_MS)!!, 0.0)
        assertEquals(50.2187, s.value(GnssChannels.LAT)!!, 1e-9)
        assertEquals(19.0428, s.value(GnssChannels.LON)!!, 1e-9)
        assertEquals(37.0368, s.value(GnssChannels.SPEED_KMH)!!, 1e-9)
        assertEquals(90.5, s.value(GnssChannels.HEADING)!!, 1e-9)
        assertEquals(250.5, s.value(GnssChannels.ALT_M)!!, 1e-9)
        assertEquals(14.0, s.value(GnssChannels.SATS)!!, 0.0)
        assertEquals(3.0, s.value(DragyChannels.FIX_TYPE)!!, 0.0)
        assertEquals(1.5, s.value(DragyChannels.H_ACC)!!, 1e-9)
        assertEquals(1.08, s.value(DragyChannels.SPEED_ACC)!!, 1e-9)
        assertEquals(1.35, s.value(DragyChannels.PDOP)!!, 1e-9)
        assertEquals(listOf(DragyEvents.FIX_ACQUIRED to "sats=14"), sink.events)
        assertEquals(1L, decoder.messages)
    }

    @Test
    fun `no fix gives no position, and fix changes are events`() {
        val sink = Sink()
        val decoder = DragyDecoder(sink)
        decoder.feed(navPvtPacket(navPvtPayload(fixType = 0, flags = 0)))
        decoder.feed(navPvtPacket())
        decoder.feed(navPvtPacket(navPvtPayload(fixType = 2)))
        decoder.feed(navPvtPacket(navPvtPayload(fixType = 0, flags = 0)))
        assertEquals(listOf(false, true, false, false), sink.sets.map { it.value(GnssChannels.LAT) != null })
        assertNull(sink.sets.first().value(GnssChannels.SPEED_KMH))
        assertEquals(listOf(DragyEvents.FIX_ACQUIRED, DragyEvents.FIX_LOST), sink.events.map { it.first })
    }

    @Test
    fun `other UBX messages and bad checksums are ignored`() {
        val sink = Sink()
        val decoder = DragyDecoder(sink)
        val corrupt = navPvtPacket().also { it[40] = (it[40] + 1).toByte() }
        decoder.feed(ubxPacket(0x01, 0x35, ByteArray(8)) + corrupt + ubxPacket(0xFF, 0x01, ByteArray(80)))
        assertTrue(sink.sets.isEmpty())
        assertEquals(1L, decoder.checksumErrors)
    }

    @Test
    fun `reset drops a partial packet`() {
        val sink = Sink()
        val decoder = DragyDecoder(sink)
        decoder.feed(navPvtPacket().copyOf(50))
        decoder.reset()
        decoder.feed(navPvtPacket())
        assertEquals(1, sink.sets.size)
    }
}
