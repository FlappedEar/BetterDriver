@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.lns

import com.flappedear.betterdriver.core.ble.sigUuid
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
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

    fun u24(v: Int) = u16(v).u8(v shr 16)

    fun s32(v: Int) = u24(v).u8(v shr 24)

    fun bytes(): ByteArray = out.toByteArray()
}

class LnsDecoderTest {
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

    private fun status(s: Int) = s shl 7

    /** Speed 20.00 m/s, 50.2205667° N, 19.0426333° E, status as given. */
    private fun speedAndLocation(status: Int = LnsPositionStatus.OK) =
        Value().u16(0b101 or status(status)).u16(2_000).s32(502_205_667).s32(190_426_333).bytes()

    /** Every field: flags 0x7F, position OK, 3D, barometric elevation. */
    private fun everything(heading: Int = 9_050, flagsExtra: Int = 0) = Value()
        .u16(0x7F or status(LnsPositionStatus.OK) or (1 shl 9) or (1 shl 10) or flagsExtra)
        .u16(1_234) // 12.34 m/s
        .u24(123_456) // 12,345.6 m
        .s32(-339_249_000) // 33.9249° S
        .s32(1_511_234_567) // 151.1234567° E
        .u24(-1_550) // -15.50 m as sint24
        .u16(heading)
        .u8(42)
        .u16(2026).u8(10).u8(11).u8(14).u8(30).u8(5)
        .bytes()

    @Test
    fun `parses every field and the flag-only values`() {
        val v = checkNotNull(LocationAndSpeed.parse(everything()))
        assertEquals(12.34, checkNotNull(v.speedMs), 1e-9)
        assertEquals(12_345.6, checkNotNull(v.totalDistanceM), 1e-9)
        assertEquals(-33.9249, checkNotNull(v.latDeg), 1e-9)
        assertEquals(151.1234567, checkNotNull(v.lonDeg), 1e-9)
        assertEquals(-15.5, checkNotNull(v.elevationM), 1e-9)
        assertEquals(90.5, checkNotNull(v.headingDeg), 1e-9)
        assertEquals(42, v.rollingTimeS)
        assertEquals(Instant.parse("2026-10-11T14:30:05Z").toEpochMilli(), v.utcMillis)
        assertEquals(LnsPositionStatus.OK, v.positionStatus)
        assertTrue(v.is3d)
        assertEquals(LnsElevationSource.BAROMETRIC, v.elevationSource)
        assertFalse(v.headingFromCompass)
    }

    @Test
    fun `parses only the fields the flags select, in spec order`() {
        // Elevation and heading only: no speed, distance or location before them.
        val v = checkNotNull(LocationAndSpeed.parse(Value().u16(0b11000).u24(25_050).u16(35_999).bytes()))
        assertNull(v.speedMs)
        assertNull(v.latDeg)
        assertEquals(250.5, checkNotNull(v.elevationM), 1e-9)
        assertEquals(359.99, checkNotNull(v.headingDeg), 1e-9)
        assertFalse(v.hasLocation)
        // Flags only.
        assertEquals(LocationAndSpeed(0), LocationAndSpeed.parse(Value().u16(0).bytes()))
        // Total distance and rolling time.
        val d = checkNotNull(LocationAndSpeed.parse(Value().u16(0b100010).u24(0xFFFFFF).u8(255).bytes()))
        assertEquals(1_677_721.5, checkNotNull(d.totalDistanceM), 1e-6)
        assertEquals(255, d.rollingTimeS)
    }

    @Test
    fun `positive sint24 elevation and maximum uint16 speed`() {
        val v = checkNotNull(LocationAndSpeed.parse(Value().u16(0b1001).u16(0xFFFF).u24(0x7FFFFF).bytes()))
        assertEquals(655.35, checkNotNull(v.speedMs), 1e-9)
        assertEquals(83_886.07, checkNotNull(v.elevationM), 1e-9)
    }

    @Test
    fun `truncated values are dropped`() {
        val full = everything()
        for (n in 0 until full.size) {
            assertNull("length $n", LocationAndSpeed.parse(full.copyOf(n)))
        }
        assertTrue(LocationAndSpeed.parse(full + byteArrayOf(1, 2)) != null)
        val sink = Sink()
        val decoder = LnsDecoder(sink)
        decoder.feed(full.copyOf(10))
        decoder.feed(byteArrayOf(0x04))
        assertTrue(sink.sets.isEmpty())
        assertEquals(2L, decoder.truncated)
        assertEquals(0L, decoder.decoded)
    }

    @Test
    fun `unknown or impossible UTC dates give no time`() {
        fun utc(year: Int, month: Int, day: Int) = LocationAndSpeed.parse(
            Value().u16(1 shl 6).u16(year).u8(month).u8(day).u8(1).u8(2).u8(3).bytes(),
        )?.utcMillis
        assertNull(utc(0, 10, 11))
        assertNull(utc(2026, 0, 11))
        assertNull(utc(2026, 10, 0))
        assertNull(utc(2026, 2, 30))
        assertEquals(Instant.parse("2024-02-29T01:02:03Z").toEpochMilli(), utc(2024, 2, 29))
    }

    @Test
    fun `defines GNSS and LNS channels with unique ids`() {
        val sink = Sink()
        LnsDecoder(sink).defineChannels()
        assertEquals(sink.channels.size, sink.channels.map { it.id }.toSet().size)
        assertTrue(sink.channels.containsAll(GnssChannels.ALL))
        assertTrue(LnsChannels.ALL.all { it.id in 300..399 })
    }

    @Test
    fun `a fix becomes the same channels as the NMEA decoder's`() {
        val sink = Sink()
        sink.now = 1_500
        LnsDecoder(sink).feed(everything())
        val set = sink.sets.single()
        assertEquals(1_500L, set.tMicros)
        assertEquals(-33.9249, checkNotNull(set.value(GnssChannels.LAT)), 1e-9)
        assertEquals(151.1234567, checkNotNull(set.value(GnssChannels.LON)), 1e-9)
        assertEquals(12.34 * 3.6, checkNotNull(set.value(GnssChannels.SPEED_KMH)), 1e-9)
        assertEquals(90.5, checkNotNull(set.value(GnssChannels.HEADING)), 1e-9)
        assertEquals(-15.5, checkNotNull(set.value(GnssChannels.ALT_M)), 1e-9)
        assertEquals(Instant.parse("2026-10-11T14:30:05Z").toEpochMilli().toDouble(), set.value(GnssChannels.UTC_MS))
        assertEquals(1.0, set.value(LnsChannels.POSITION_STATUS))
        assertEquals(12_345.6, checkNotNull(set.value(LnsChannels.TOTAL_DISTANCE)), 1e-9)
        assertNull(set.value(LnsChannels.COMPASS_HEADING))
        assertEquals(listOf(LnsEvents.FIX_ACQUIRED to "status=1"), sink.events)
    }

    @Test
    fun `a compass heading is not the course`() {
        val sink = Sink()
        LnsDecoder(sink).feed(everything(heading = 18_000, flagsExtra = 1 shl 12))
        val set = sink.sets.single()
        assertNull(set.value(GnssChannels.HEADING))
        assertEquals(180.0, checkNotNull(set.value(LnsChannels.COMPASS_HEADING)), 1e-9)
    }

    @Test
    fun `no position and last known position leave out position and speed`() {
        for (status in listOf(LnsPositionStatus.LAST_KNOWN, LnsPositionStatus.NO_POSITION)) {
            val sink = Sink()
            val value = if (status == LnsPositionStatus.NO_POSITION) {
                Value().u16(0b101).u16(2_000).s32(0).s32(0).bytes()
            } else {
                speedAndLocation(status)
            }
            LnsDecoder(sink).feed(value)
            val set = sink.sets.single()
            assertNull(set.value(GnssChannels.LAT))
            assertNull(set.value(GnssChannels.SPEED_KMH))
            assertEquals(status.toDouble(), set.value(LnsChannels.POSITION_STATUS))
            assertTrue("no event before a first fix", sink.events.isEmpty())
        }
    }

    @Test
    fun `estimated positions and servers without position status count as a fix`() {
        for (status in listOf(LnsPositionStatus.ESTIMATED, LnsPositionStatus.NO_POSITION)) {
            val sink = Sink()
            LnsDecoder(sink).feed(speedAndLocation(status))
            assertEquals(50.2205667, checkNotNull(sink.sets.single().value(GnssChannels.LAT)), 1e-9)
            assertEquals(72.0, checkNotNull(sink.sets.single().value(GnssChannels.SPEED_KMH)), 1e-9)
        }
    }

    @Test
    fun `fix events on changes, and split values keep the fix state`() {
        val sink = Sink()
        val decoder = LnsDecoder(sink)
        decoder.feed(speedAndLocation())
        // The second half of a split value: elevation and heading only, no location.
        decoder.feed(Value().u16(0b11000).u24(10_000).u16(4_500).bytes())
        decoder.feed(speedAndLocation(LnsPositionStatus.LAST_KNOWN))
        decoder.feed(Value().u16(0b11000).u24(10_000).u16(4_500).bytes())
        assertEquals(
            listOf(LnsEvents.FIX_ACQUIRED to "status=1", LnsEvents.FIX_LOST to "status=3"),
            sink.events,
        )
        assertEquals(100.0, checkNotNull(sink.sets[1].value(GnssChannels.ALT_M)), 1e-9)
        assertEquals(45.0, checkNotNull(sink.sets[1].value(GnssChannels.HEADING)), 1e-9)
        assertNull(sink.sets[1].value(LnsChannels.POSITION_STATUS))
        assertTrue("nothing to log without a fix", sink.sets.size == 3)
        assertEquals(4L, decoder.decoded)
    }

    @Test
    fun `reset forgets the fix so a reconnect reports it again`() {
        val sink = Sink()
        val decoder = LnsDecoder(sink)
        decoder.feed(speedAndLocation())
        decoder.reset()
        decoder.feed(Value().u16(0b11000).u24(10_000).u16(4_500).bytes())
        decoder.feed(speedAndLocation())
        assertEquals(2, sink.events.count { it.first == LnsEvents.FIX_ACQUIRED })
        assertTrue("no altitude before the fix is known again", sink.sets.size == 2)
    }

    @Test
    fun `scan filter matches the Location and Navigation Service only`() {
        val lns = sigUuid(0x1819)
        assertEquals(UUID.fromString("00001819-0000-1000-8000-00805f9b34fb"), lns)
        assertTrue(isLnsAdvertisement("XGPS160", listOf(sigUuid(0x180F), lns)))
        assertTrue(isLnsAdvertisement(null, listOf(lns)))
        assertFalse(isLnsAdvertisement("XGPS160", null))
        assertFalse(isLnsAdvertisement("XGPS160", emptyList()))
        assertFalse(isLnsAdvertisement("HRM", listOf(sigUuid(0x180D))))
        assertFalse(isLnsAdvertisement("RaceBox Mini 1234567890", listOf(lns)))
    }
}
