@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.core.ble.nmea.nmeaSentence
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The receiver session both platforms record through (FBBD-374), run on the JVM and the iOS
 * simulator: so the iPhone's Wi-Fi GPS, Bluetooth LE GPS and RaceChrono DIY record what Android's do.
 */
class GnssReceiverSessionTest {
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

    private fun SampleSet.value(def: ChannelDef): Double? = values.firstOrNull { it.channelId == def.id }?.value

    @Test
    fun `NMEA over Wi-Fi records fixes with UTC and logs connects and drops`() {
        val sink = Sink()
        val session = GnssReceiverSession.nmea(sink, "192.168.4.1:10110", "Wi-Fi GPS")
        session.start()
        assertTrue(sink.channels.any { it.id == GnssChannels.LAT.id })
        assertEquals("nmea_device" to "address=192.168.4.1:10110 name=Wi-Fi GPS", sink.events.first())

        session.linkChanged(LinkState.Connecting)
        session.linkChanged(LinkState.Streaming(2))
        val text = nmeaSentence("GPRMC,123519.00,A,5004.1234,N,01956.5678,E,54.0,90.0,011126,,,A") +
            nmeaSentence("GPGGA,123519.00,5004.1234,N,01956.5678,E,1,09,0.9,245.4,M,40.0,M,,")
        sink.now = 1_000_000
        session.feed(text.encodeToByteArray())

        val fix = sink.sets.single()
        assertEquals(1_000_000, fix.tMicros)
        assertEquals(50.0 + 4.1234 / 60, fix.value(GnssChannels.LAT)!!, 1e-9)
        assertEquals(19.0 + 56.5678 / 60, fix.value(GnssChannels.LON)!!, 1e-9)
        assertEquals(54.0 * 1.852, fix.value(GnssChannels.SPEED_KMH)!!, 1e-9)
        // 1 Nov 2026 12:35:19 UTC.
        assertEquals(1_793_536_519_000.0, fix.value(GnssChannels.UTC_MS))
        assertTrue(sink.events.any { it.first == "nmea_connected" })

        session.linkChanged(LinkState.Reconnecting(1, 1_000, "no data for 5001 ms"))
        assertEquals("nmea_disconnected" to "no data for 5001 ms", sink.events.last())
    }

    @Test
    fun `Bluetooth LE Location and Speed records position and speed`() {
        val sink = Sink()
        val session = GnssReceiverSession.lns(sink, "ID-1", "XGPS160")
        session.start()
        // Flags: speed, location, position status OK (bit 7).
        val flags = 0x01 or 0x04 or (1 shl 7)
        val lat = 500_123_456
        val lon = 199_876_543
        val value = byteArrayOf(
            flags.toByte(), (flags shr 8).toByte(),
            0xE8.toByte(), 0x03, // 10.00 m/s
            lat.toByte(), (lat shr 8).toByte(), (lat shr 16).toByte(), (lat shr 24).toByte(),
            lon.toByte(), (lon shr 8).toByte(), (lon shr 16).toByte(), (lon shr 24).toByte(),
        )
        session.feed(value)
        val set = sink.sets.single()
        assertEquals(50.0123456, set.value(GnssChannels.LAT)!!, 1e-9)
        assertEquals(36.0, set.value(GnssChannels.SPEED_KMH)!!, 1e-9)
        assertEquals("gnss_fix", sink.events.last().first)
    }

    @Test
    fun `RaceChrono DIY waits for GPS time and asks for it`() {
        val sink = Sink()
        var asked = 0
        val session = GnssReceiverSession.diy(sink, "ID-2", "DIY GPS") { asked++ }
        session.start()
        // GPS main, sync 1, 12 min 30 s into the hour, fix quality 1 with 8 satellites, 50°N 20°E.
        val units = (12 * 60 + 30) * 500
        val head = (1 shl 21) or units
        val main = ByteArray(20)
        main[0] = (head shr 16).toByte()
        main[1] = (head shr 8).toByte()
        main[2] = head.toByte()
        main[3] = ((1 shl 6) or 8).toByte()
        fun s32(at: Int, v: Int) = (0..3).forEach { main[at + it] = (v shr (24 - 8 * it)).toByte() }
        s32(4, 500_000_000)
        s32(8, 200_000_000)
        session.feed(main)
        assertEquals(1, asked)
        assertTrue(sink.sets.isEmpty())
        // GPS time, sync 1: 2026-11-01 12 h.
        val hours = 26 * 8928 + 10 * 744 + 0 * 24 + 12
        val time = (1 shl 21) or hours
        session.feedTime(byteArrayOf((time shr 16).toByte(), (time shr 8).toByte(), time.toByte()))
        val set = sink.sets.single()
        assertEquals(50.0, set.value(GnssChannels.LAT)!!, 1e-9)
        assertEquals(1_793_534_400_000.0 + 750_000, set.value(GnssChannels.UTC_MS))
    }
}
