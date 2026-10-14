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

    private fun packetAt(iTowMs: Long) = RaceBoxTestData.packet { p ->
        for (i in 0 until 4) p[i] = (iTowMs shr (8 * i)).toByte()
    }

    /**
     * FBBD-218: when the link carries fewer messages than the RaceBox sends, they arrive ever later
     * and then go missing. Either asks the link for a faster connection; a link keeping up does not.
     */
    @Test
    fun `a link falling behind or losing messages asks for a faster link`() {
        val sink = Sink()
        val reasons = mutableListOf<String>()
        val decoder = RaceBoxDecoder(sink) { reasons += it }
        var tow = 100_000L
        fun message(delayMicros: Long) {
            sink.now = tow * 1_000 + delayMicros
            decoder.feed(packetAt(tow))
            tow += 40
        }
        repeat(100) { message(30_000) }
        assertTrue(reasons.isEmpty())
        // The delay grows by 2 ms a message: 100 ms behind after 50 messages.
        for (i in 1..60) message(30_000L + i * 2_000L)
        assertTrue(reasons.toString(), reasons.any { it.endsWith("ms behind") })
        reasons.clear()
        // Caught up again, then one message never comes.
        repeat(20) { message(30_000) }
        reasons.clear()
        tow += 40
        message(30_000)
        assertEquals(listOf("messages missing"), reasons)
    }

    /**
     * Arek's 2026-10-14 drive: before the fix the RaceBox's time ran off GNSS time, so the delay it gave
     * rose by about 0.65 s; with the rise capped for a backlog (FBBD-218), every sample after the fix was
     * stamped 0.65 s early against the video. The delay is taken afresh when the first fix comes.
     */
    @Test
    fun `the delay is taken afresh when the first fix comes`() {
        val sink = Sink()
        val decoder = RaceBoxDecoder(sink)
        var tow = 100_000L
        fun message(delayMicros: Long, fix: Boolean) {
            sink.now = tow * 1_000 + delayMicros
            decoder.feed(
                RaceBoxTestData.packet { p ->
                    for (i in 0 until 4) p[i] = (tow shr (8 * i)).toByte()
                    if (!fix) p[21] = 0x00
                },
            )
            tow += 40
        }
        repeat(250) { message(30_000, fix = false) }
        // The receiver's own time falls 650 ms behind over a minute without a fix.
        for (i in 1..1500) message(30_000L + i * 650_000L / 1500, fix = false)
        repeat(500) { message(680_000, fix = true) }
        val last = sink.sets.last()
        assertEquals(sink.now, last.tMicros)
        val stamps = sink.sets.map { it.tMicros }
        assertEquals(stamps, stamps.sorted())
        // A fix lost and found again while the link has a 300 ms backlog keeps the delay it had.
        repeat(25) { message(680_000, fix = false) }
        repeat(50) { message(980_000, fix = true) }
        assertTrue(sink.now - sink.sets.last().tMicros > 250_000)
        // After a reconnect the receiver may have been off: its next first fix is taken afresh again.
        decoder.reset()
        repeat(25) { message(980_000, fix = false) }
        repeat(25) { message(1_500_000, fix = true) }
        assertEquals(sink.now, sink.sets.last().tMicros)
    }
}
