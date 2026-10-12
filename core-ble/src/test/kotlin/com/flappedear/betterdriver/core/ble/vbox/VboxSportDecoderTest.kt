@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.vbox

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

class VboxSportDecoderTest {
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

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

    private val phoneNow = utc("2026-10-11T10:11:30Z")

    @Test
    fun `defines GNSS and VBOX channels with unique ids in 600 to 619`() {
        val sink = Sink()
        VboxSportDecoder(sink).defineChannels()
        assertEquals(sink.channels.size, sink.channels.map { it.id }.toSet().size)
        assertTrue(sink.channels.containsAll(GnssChannels.ALL))
        assertTrue(VboxSportChannels.ALL.all { it.id in 600..619 })
    }

    @Test
    fun `decodes the bluetooth default message`() {
        val reader = VboxSportFrameReader()
        val f = VboxSportFix.from(buildList { reader.feed(bluetoothDefaultMessage()) { add(it) } }.single())
        assertEquals(10, f.satellites)
        assertTrue(f.dgps)
        assertEquals(36_672_200L, f.timeOfDayMs)
        assertEquals(3013.1234 / 60, f.latDeg!!, 1e-12)
        // West-positive in the stream; 1142.5678 minutes east.
        assertEquals(1142.5678 / 60, f.lonDeg!!, 1e-12)
        assertEquals(20.0 * 1.852, f.speedKmh!!, 1e-9)
        assertEquals(90.5, f.headingDeg!!, 0.0)
        assertEquals(-12.34, f.altitudeM!!, 1e-9)
        assertEquals(0.8, f.hdop!!, 0.0)
        assertTrue(f.hasFix)
    }

    @Test
    fun `one sample set per message with GNSS values`() {
        val sink = Sink()
        val decoder = VboxSportDecoder(sink) { phoneNow }
        sink.now = 7_000
        decoder.feed(bluetoothDefaultMessage())
        val s = sink.sets.single()
        assertEquals(7_000L, s.tMicros)
        assertEquals(utc("2026-10-11T10:11:12.200Z").toDouble(), s.value(GnssChannels.UTC_MS)!!, 0.0)
        assertEquals(3013.1234 / 60, s.value(GnssChannels.LAT)!!, 1e-12)
        assertEquals(1142.5678 / 60, s.value(GnssChannels.LON)!!, 1e-12)
        assertEquals(37.04, s.value(GnssChannels.SPEED_KMH)!!, 1e-9)
        assertEquals(90.5, s.value(GnssChannels.HEADING)!!, 0.0)
        assertEquals(-12.34, s.value(GnssChannels.ALT_M)!!, 1e-9)
        assertEquals(10.0, s.value(GnssChannels.SATS)!!, 0.0)
        assertEquals(1.0, s.value(VboxSportChannels.DGPS)!!, 0.0)
        assertEquals(0.8, s.value(VboxSportChannels.HDOP)!!, 0.0)
        assertEquals(listOf(VboxSportEvents.FIX_ACQUIRED to "sats=10"), sink.events)
        assertEquals(1L, decoder.messages)
    }

    @Test
    fun `southern and western positions keep their sign`() {
        val data = bluetoothDefaultData(latRaw = -201_234_567, lonRaw = 301_234_567)
        val f = VboxSportFix.from(VboxSportMessage(0x3FF, 0x71, data))
        assertEquals(-2012.34567 / 60, f.latDeg!!, 1e-12)
        assertEquals(-3012.34567 / 60, f.lonDeg!!, 1e-12)
    }

    @Test
    fun `no fix gives no position or time, and fix changes are events`() {
        val sink = Sink()
        val decoder = VboxSportDecoder(sink) { phoneNow }
        val noSats = bluetoothDefaultMessage(data = bluetoothDefaultData(sats = 0, latRaw = 0, lonRaw = 0))
        val twoSats = bluetoothDefaultMessage(data = bluetoothDefaultData(sats = 2))
        decoder.feed(noSats)
        decoder.feed(bluetoothDefaultMessage())
        decoder.feed(twoSats)
        decoder.feed(noSats)
        assertEquals(listOf(false, true, false, false), sink.sets.map { it.value(GnssChannels.LAT) != null })
        val first = sink.sets.first()
        assertNull(first.value(GnssChannels.UTC_MS))
        assertNull(first.value(GnssChannels.SPEED_KMH))
        assertEquals(0.0, first.value(GnssChannels.SATS)!!, 0.0)
        assertEquals(listOf(VboxSportEvents.FIX_ACQUIRED, VboxSportEvents.FIX_LOST), sink.events.map { it.first })
    }

    @Test
    fun `a stream that never passes its CRC is reported once per connection`() {
        val sink = Sink()
        val decoder = VboxSportDecoder(sink) { phoneNow }
        val bad = bluetoothDefaultMessage().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        repeat(BadMessagesBeforeEvent.toInt() * 2) { decoder.feed(bad) }
        assertEquals(listOf(VboxSportEvents.NO_VALID_MESSAGES), sink.events.map { it.first })
        assertTrue(sink.sets.isEmpty())
        decoder.reset()
        repeat(BadMessagesBeforeEvent.toInt() - 1) { decoder.feed(bad) }
        assertEquals(1, sink.events.size)
        decoder.feed(bad)
        assertEquals(2, sink.events.size)
    }

    @Test
    fun `good messages suppress the CRC report`() {
        val sink = Sink()
        val decoder = VboxSportDecoder(sink) { phoneNow }
        decoder.feed(bluetoothDefaultMessage())
        val bad = bluetoothDefaultMessage().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        repeat(BadMessagesBeforeEvent.toInt() * 2) { decoder.feed(bad) }
        assertFalse(sink.events.any { it.first == VboxSportEvents.NO_VALID_MESSAGES })
    }

    @Test
    fun `messages read together are stamped apart by their GNSS times`() {
        val sink = Sink()
        val decoder = VboxSportDecoder(sink) { phoneNow }
        sink.now = 1_000_000
        // Three 20 Hz messages in one read; ticks are 10 ms.
        val read = (0..2).map { bluetoothDefaultMessage(data = bluetoothDefaultData(ticks = 3_667_220L + 5 * it)) }
        decoder.feed(read.reduce(ByteArray::plus))
        assertEquals(listOf(900_000L, 950_000L, 1_000_000L), sink.sets.map { it.tMicros })
        // Two with the same time are still one after the other.
        sink.now = 2_000_000
        decoder.feed(bluetoothDefaultMessage() + bluetoothDefaultMessage())
        assertEquals(listOf(2_000_000L, 2_000_001L), sink.sets.drop(3).map { it.tMicros })
    }

    @Test
    fun `time of day takes the date nearest the phone clock`() {
        val tod = 23 * 3_600_000L + 59 * 60_000L
        assertEquals(utc("2026-10-10T23:59:00Z"), utcFromTimeOfDay(tod, utc("2026-10-11T00:00:30Z")))
        assertEquals(utc("2026-10-11T00:00:10Z"), utcFromTimeOfDay(10_000, utc("2026-10-10T23:59:50Z")))
        assertEquals(utc("2026-10-11T10:11:12Z"), utcFromTimeOfDay(36_672_000, phoneNow))
    }
}
