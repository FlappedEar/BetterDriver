@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceBoxDecoderTest {
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
    fun `defines GNSS and RaceBox channels with unique ids`() {
        val sink = Sink()
        RaceBoxDecoder(sink).defineChannels()
        assertEquals(sink.channels.size, sink.channels.map { it.id }.toSet().size)
        assertTrue(sink.channels.containsAll(GnssChannels.ALL))
        assertTrue(RaceBoxChannels.ALL.all { it.id in 100..199 })
    }

    @Test
    fun `a Mini has no input voltage channel`() {
        val sink = Sink()
        val decoder = RaceBoxDecoder(sink, isMicro = false)
        decoder.defineChannels()
        decoder.feed(RaceBoxTestData.packet())
        assertFalse(RaceBoxChannels.INPUT_VOLTS in sink.channels)
        assertEquals(null, sink.sets.single().value(RaceBoxChannels.INPUT_VOLTS))
    }

    @Test
    fun `one sample set per message, on the recorder clock`() {
        val sink = Sink()
        val decoder = RaceBoxDecoder(sink)
        sink.now = 1_000
        decoder.feed(RaceBoxTestData.microPacket())
        sink.now = 41_000
        decoder.feed(RaceBoxTestData.microPacket())
        assertEquals(listOf(1_000L, 41_000L), sink.sets.map { it.tMicros })
        val s = sink.sets.first()
        assertEquals(42.6719035, s.value(GnssChannels.LAT)!!, 1e-9)
        assertEquals(23.2887238, s.value(GnssChannels.LON)!!, 1e-9)
        assertEquals(0.126, s.value(GnssChannels.SPEED_KMH)!!, 1e-9)
        assertEquals(590.095, s.value(GnssChannels.ALT_M)!!, 1e-9)
        assertEquals(11.0, s.value(GnssChannels.SATS)!!, 0.0)
        assertEquals(0.974, s.value(RaceBoxChannels.ACCEL_Z)!!, 1e-9)
        assertEquals(12.1, s.value(RaceBoxChannels.INPUT_VOLTS)!!, 1e-9)
        assertEquals(2, decoder.messages)
    }

    @Test
    fun `heading is kept with a fix even when the heading flag is clear`() {
        val sink = Sink()
        // 271.5 degrees = 0x019E46B0; the example's fix flags (0x01) have bit 5 clear.
        val packet = RaceBoxTestData.packet { p ->
            intArrayOf(0xB0, 0x46, 0x9E, 0x01).forEachIndexed { i, b -> p[52 + i] = b.toByte() }
        }
        RaceBoxDecoder(sink).feed(packet)
        assertEquals(271.5, sink.sets.single().value(GnssChannels.HEADING)!!, 1e-9)
    }

    @Test
    fun `without a fix there is no position, so no VBO row, but IMU data is kept`() {
        val sink = Sink()
        RaceBoxDecoder(sink).feed(RaceBoxTestData.packet { it[21] = 0x00 })
        val s = sink.sets.single()
        assertEquals(null, s.value(GnssChannels.LAT))
        assertEquals(null, s.value(GnssChannels.LON))
        assertEquals(0.113, s.value(RaceBoxChannels.ACCEL_Y)!!, 1e-9)
    }

    @Test
    fun `fix changes are logged as events, starting without a fix is not`() {
        val sink = Sink()
        val decoder = RaceBoxDecoder(sink)
        decoder.feed(RaceBoxTestData.packet { it[21] = 0x00 })
        decoder.feed(RaceBoxTestData.packet())
        decoder.feed(RaceBoxTestData.packet())
        decoder.feed(RaceBoxTestData.packet { it[21] = 0x00 })
        assertEquals(listOf(RaceBoxEvents.FIX_ACQUIRED, RaceBoxEvents.FIX_LOST), sink.events.map { it.first })
    }

    @Test
    fun `other UBX messages and bad packets are ignored`() {
        val sink = Sink()
        val decoder = RaceBoxDecoder(sink)
        decoder.feed(ubxPacket(0xFF, 0x22, ByteArray(12)))
        decoder.feed(RaceBoxTestData.packet().also { it[40] = (it[40] + 1).toByte() })
        assertTrue(sink.sets.isEmpty())
        assertEquals(1, decoder.checksumErrors)
    }

    @Test
    fun `a calibration levels acceleration and removes the gyro offset`() {
        val sink = Sink()
        val cal = ImuCalibration(
            gyroBias = Vec3(-2.0, 0.5, 0.0),
            level = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            rollDeg = 0.0,
            pitchDeg = 0.0,
            atMillis = 0,
        )
        RaceBoxDecoder(sink, calibration = cal).feed(RaceBoxTestData.microPacket())
        val set = sink.sets.single()
        // Example packet: rotation X = -2.09, Y = 0.86 deg/s (p. 8, signed).
        assertEquals(-0.09, set.value(RaceBoxChannels.GYRO_X)!!, 1e-9)
        assertEquals(0.36, set.value(RaceBoxChannels.GYRO_Y)!!, 1e-9)
        assertEquals(0.974, set.value(RaceBoxChannels.ACCEL_Z)!!, 1e-9)
    }
}
