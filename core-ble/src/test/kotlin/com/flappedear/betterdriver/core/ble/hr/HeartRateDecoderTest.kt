@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.core.ble.sigUuid
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds a little-endian characteristic value field by field, as the GATT spec lays it out. */
private class Value {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }

    fun u16(v: Int) = u8(v).u8(v shr 8)

    fun bytes(): ByteArray = out.toByteArray()
}

private const val Value16 = 0x01
private const val ContactNotDetected = 0x04
private const val ContactDetected = 0x06
private const val Energy = 0x08
private const val Rr = 0x10

class HeartRateDecoderTest {
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
    fun `uint8 value without anything else`() {
        val m = checkNotNull(HeartRateMeasurement.parse(Value().u8(0).u8(152).bytes()))
        assertEquals(152, m.bpm)
        assertEquals(SensorContact.NOT_SUPPORTED, m.contact)
        assertNull(m.energyKj)
        assertTrue(m.rrMs.isEmpty())
    }

    @Test
    fun `uint16 value, contact, energy and RR intervals`() {
        val bytes = Value().u8(Value16 or ContactDetected or Energy or Rr)
            .u16(301).u16(0x1234).u16(1024).u16(512).u16(410).bytes()
        val m = checkNotNull(HeartRateMeasurement.parse(bytes))
        assertEquals(301, m.bpm)
        assertEquals(SensorContact.DETECTED, m.contact)
        assertEquals(0x1234, m.energyKj)
        assertEquals(listOf(1000.0, 500.0, 400.390625), m.rrMs)
    }

    @Test
    fun `RR intervals without energy, and contact values`() {
        val m = checkNotNull(HeartRateMeasurement.parse(Value().u8(Rr or ContactNotDetected).u8(60).u16(1024).bytes()))
        assertEquals(60, m.bpm)
        assertNull(m.energyKj)
        assertEquals(listOf(1000.0), m.rrMs)
        assertEquals(SensorContact.NOT_DETECTED, m.contact)
        assertEquals(SensorContact.NOT_SUPPORTED, HeartRateMeasurement.parse(byteArrayOf(0x02, 60))?.contact)
        // The RR flag with no room left for an interval is just an empty list.
        assertEquals(emptyList<Double>(), HeartRateMeasurement.parse(Value().u8(Rr).u8(60).bytes())?.rrMs)
    }

    @Test
    fun `truncated values are dropped, a trailing half interval is ignored`() {
        assertNull(HeartRateMeasurement.parse(ByteArray(0)))
        assertNull(HeartRateMeasurement.parse(byteArrayOf(0)))
        assertNull(HeartRateMeasurement.parse(byteArrayOf(Value16.toByte(), 60)))
        assertNull(HeartRateMeasurement.parse(byteArrayOf(Energy.toByte(), 60, 1)))
        val odd = Value().u8(Rr).u8(70).u16(1024).u8(7).bytes()
        assertEquals(listOf(1000.0), HeartRateMeasurement.parse(odd)?.rrMs)
        val sink = Sink()
        val decoder = HeartRateDecoder(sink)
        decoder.feed(byteArrayOf(Value16.toByte(), 60))
        assertTrue(sink.sets.isEmpty())
        assertEquals(1L, decoder.truncated)
    }

    @Test
    fun `defines the heart rate channels with unique ids`() {
        val sink = Sink()
        HeartRateDecoder(sink).defineChannels()
        assertEquals(HeartRateChannels.ALL, sink.channels)
        assertEquals(sink.channels.size, sink.channels.map { it.id }.toSet().size)
        assertTrue(HeartRateChannels.ALL.all { it.id in 400..499 })
        assertEquals("heart_rate", HeartRateChannels.HEART_RATE.name)
        assertEquals("bpm", HeartRateChannels.HEART_RATE.unit)
    }

    @Test
    fun `heart rate and energy in one set on arrival`() {
        val sink = Sink()
        sink.now = 5_000_000
        HeartRateDecoder(sink).feed(Value().u8(Energy).u8(88).u16(12).bytes())
        val set = sink.sets.single()
        assertEquals(5_000_000L, set.tMicros)
        assertEquals(88.0, set.value(HeartRateChannels.HEART_RATE))
        assertEquals(12.0, set.value(HeartRateChannels.ENERGY))
        assertNull(set.value(HeartRateChannels.RR_INTERVAL))
    }

    @Test
    fun `RR intervals are stamped at their beats, oldest first`() {
        val sink = Sink()
        sink.now = 10_000_000
        HeartRateDecoder(sink).feed(Value().u8(Rr).u8(100).u16(1024).u16(512).bytes())
        val rr = sink.sets.filter { it.value(HeartRateChannels.RR_INTERVAL) != null }
        assertEquals(listOf(9_500_000L, 10_000_000L), rr.map { it.tMicros })
        assertEquals(listOf(1000.0, 500.0), rr.map { it.value(HeartRateChannels.RR_INTERVAL) })
        assertEquals(100.0, sink.sets.last().value(HeartRateChannels.HEART_RATE))
    }

    @Test
    fun `RR stamps never go back in time or before the session`() {
        val sink = Sink()
        val decoder = HeartRateDecoder(sink)
        sink.now = 300_000
        decoder.feed(Value().u8(Rr).u8(100).u16(1024).bytes())
        sink.now = 600_000
        // Claims 2 s of beats in the 0.3 s since the last notification.
        decoder.feed(Value().u8(Rr).u8(100).u16(1024).u16(1024).bytes())
        val stamps = sink.sets.filter { it.value(HeartRateChannels.RR_INTERVAL) != null }.map { it.tMicros }
        assertEquals(listOf(300_000L, 300_001L, 600_000L), stamps)
    }

    @Test
    fun `no heart rate without skin contact, and contact events`() {
        val sink = Sink()
        val decoder = HeartRateDecoder(sink)
        decoder.feed(byteArrayOf(ContactDetected.toByte(), 90))
        decoder.feed(byteArrayOf(ContactNotDetected.toByte(), 0))
        decoder.feed(byteArrayOf(ContactNotDetected.toByte(), 0))
        decoder.feed(byteArrayOf(ContactDetected.toByte(), 95))
        assertEquals(listOf(90.0, 95.0), sink.sets.map { it.value(HeartRateChannels.HEART_RATE) })
        assertEquals(
            listOf(HeartRateEvents.CONTACT to "lost", HeartRateEvents.CONTACT to "detected"),
            sink.events,
        )
        decoder.reset()
        decoder.feed(byteArrayOf(ContactNotDetected.toByte(), 0))
        assertEquals(HeartRateEvents.CONTACT to "lost", sink.events.last())
        assertEquals(3, sink.events.size)
    }

    @Test
    fun `a heart rate no driver can have is left out like one without contact`() {
        // FBBD-285: a strap lying on a cable, not worn, sent 0 to 37 bpm (21 Oct 2026 19:32).
        // Worn drives were 76 to 118 bpm.
        val sink = Sink()
        val decoder = HeartRateDecoder(sink)
        decoder.feed(Value().u8(ContactDetected or Rr).u8(24).u16(2560).bytes())
        decoder.feed(Value().u8(Rr).u8(24).u16(2560).bytes())
        decoder.feed(byteArrayOf(0, 0))
        decoder.feed(byteArrayOf(0, 37))
        decoder.feed(byteArrayOf(0, 39))
        assertTrue(sink.sets.isEmpty())
        assertTrue(decoder.noContact)
        assertNull(decoder.lastBpm)
        assertFalse(decoder.everMeasured)
        assertEquals(5L, decoder.implausible)
        assertEquals(
            listOf(HeartRateEvents.IMPLAUSIBLE to "bpm=24 flags=0x16", HeartRateEvents.CONTACT to "lost"),
            sink.events,
        )
        decoder.feed(byteArrayOf(0, 40))
        decoder.feed(Value().u8(ContactDetected).u8(230).bytes())
        assertEquals(listOf(40.0, 230.0), sink.sets.map { it.value(HeartRateChannels.HEART_RATE) })
        assertFalse(decoder.noContact)
        decoder.feed(Value().u8(ContactDetected).u8(231).bytes())
        assertEquals(2, sink.sets.size)
        assertEquals(HeartRateEvents.IMPLAUSIBLE to "bpm=231 flags=0x06", sink.events[sink.events.size - 2])
        assertEquals(HeartRateEvents.CONTACT to "lost", sink.events.last())
        // Noise between plausible values starts the count again: worn only after 5 in a row.
        assertFalse(decoder.everMeasured)
        repeat(4) { decoder.feed(byteArrayOf(0, 90)) }
        assertFalse(decoder.everMeasured)
        decoder.feed(byteArrayOf(0, 90))
        assertTrue(decoder.everMeasured)
    }

    @Test
    fun `scan filter matches the Heart Rate Service only`() {
        assertTrue(isHeartRateAdvertisement(listOf(sigUuid(0x180F), sigUuid(0x180D))))
        assertFalse(isHeartRateAdvertisement(listOf(sigUuid(0x1819))))
        assertFalse(isHeartRateAdvertisement(null))
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", HeartRateGatt.SERVICE.toString())
        assertEquals("00002a37-0000-1000-8000-00805f9b34fb", HeartRateGatt.MEASUREMENT.toString())
    }
}
