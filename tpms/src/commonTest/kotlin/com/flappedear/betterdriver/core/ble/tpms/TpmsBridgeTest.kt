@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.tpms

import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TpmsBridgeTest {
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

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** A pressure notification as RCTPMS's encodePacket writes it: PID little-endian, centibar big-endian. */
    private fun pressure(pid: Int, centibar: Int) = bytes(pid, 0, 0, 0, centibar shr 8, centibar and 0xFF)

    private fun temp(pid: Int, c: Int) = bytes(pid, 0, 0, 0, c)

    @Test
    fun `decodes the README examples and the PID map`() {
        // README: "01 2e" = 302 = 3.02 bar; "19" = 25 °C.
        assertEquals(TpmsValue(0, pressure = true, value = 3.02), parseTpmsValue(bytes(1, 0, 0, 0, 0x01, 0x2E)))
        assertEquals(TpmsValue(3, pressure = true, value = 2.2), parseTpmsValue(pressure(4, 220)))
        assertEquals(TpmsValue(0, pressure = false, value = 25.0), parseTpmsValue(bytes(8, 0, 0, 0, 0x19)))
        assertEquals(TpmsValue(3, pressure = false, value = 200.0), parseTpmsValue(temp(11, 200)))
        assertEquals("tyre_pressure_rr", parseTpmsValue(pressure(4, 220))?.channel?.name)
        assertEquals("tyre_temp_fr", parseTpmsValue(temp(9, 30))?.channel?.name)
    }

    @Test
    fun `other PIDs and short values are not tyre values`() {
        assertNull(parseTpmsValue(pressure(5, 220)))
        assertNull(parseTpmsValue(temp(7, 20)))
        assertNull(parseTpmsValue(temp(12, 20)))
        // PID 1 with a high byte set is PID 0x01000001, not 1.
        assertNull(parseTpmsValue(bytes(1, 0, 0, 1, 1, 0x2E)))
        // A pressure needs two bytes after the PID.
        assertNull(parseTpmsValue(bytes(1, 0, 0, 0, 1)))
        assertNull(parseTpmsValue(bytes(8, 0, 0, 0)))
    }

    @Test
    fun `the filter allows every PID once a second and the bridge is told by its name`() {
        assertEquals(listOf<Byte>(0x01, 0x03, 0xE8.toByte()), TpmsIds.AllowAllEverySecond.toList())
        assertTrue(TpmsIds.isBridge("RC TPMS AMOLED"))
        assertFalse(TpmsIds.isBridge("RC DIY"))
        assertFalse(TpmsIds.isBridge(null))
    }

    @Test
    fun `records all eight channels with the names Overlays reads`() {
        val sink = Sink()
        val session = TpmsSession(sink, "AA:BB", "RC TPMS AMOLED")
        session.start()
        assertEquals(TpmsChannels.ALL, sink.channels)
        assertEquals(
            listOf("tyre_pressure_fl", "tyre_pressure_fr", "tyre_pressure_rl", "tyre_pressure_rr"),
            sink.channels.take(4).map { it.name },
        )
        assertEquals(TpmsEvents.DEVICE to "address=AA:BB name=RC TPMS AMOLED", sink.events.single())
        for (w in 1..4) session.feed(pressure(w, 200 + w))
        for (w in 8..11) session.feed(temp(w, 20 + w))
        assertEquals(8, sink.sets.size)
        assertEquals(TpmsChannels.ALL.map { it.id }, sink.sets.map { it.values.single().channelId })
        assertEquals(2.01, sink.sets.first().values.single().value)
        assertEquals(TyreReading(2.04, 31.0, stale = false), session.readings()[3])
    }

    @Test
    fun `logs connects and drops but not a drop before data`() {
        val sink = Sink()
        val session = TpmsSession(sink, "AA:BB", "RC TPMS AMOLED")
        session.start()
        session.linkChanged(LinkState.Connecting)
        session.linkChanged(LinkState.Reconnecting(1, 1_000, "no data"))
        session.linkChanged(LinkState.Streaming(8))
        session.linkChanged(LinkState.Streaming(8))
        session.linkChanged(LinkState.Reconnecting(1, 1_000, "lost"))
        assertEquals(
            listOf(TpmsEvents.CONNECTED to "AA:BB", TpmsEvents.DISCONNECTED to "lost"),
            sink.events.drop(1),
        )
    }

    @Test
    fun `a wheel repeating the same reading for 5 min is stale until it changes`() {
        val sink = Sink()
        val decoder = TpmsDecoder(sink)
        fun second(s: Long, centibar: Int, c: Int) {
            sink.now = s * 1_000_000
            decoder.feed(pressure(1, centibar))
            decoder.feed(temp(8, c))
        }
        for (s in 0L..299L) second(s, 230, 40)
        assertEquals(600, sink.sets.size)
        assertTrue(sink.events.isEmpty())
        second(300, 230, 40)
        assertEquals(listOf(TpmsEvents.STALE to "wheel=FL"), sink.events)
        // The repeats of a stale wheel are left out.
        second(301, 230, 40)
        assertEquals(600, sink.sets.size)
        assertTrue(decoder.readings()[0].stale)
        // Only the temperature changes: fresh again, recorded again.
        second(302, 230, 41)
        assertEquals(TpmsEvents.FRESH to "wheel=FL", sink.events.last())
        assertFalse(decoder.readings()[0].stale)
        assertEquals(601, sink.sets.size)
    }

    @Test
    fun `a pressure that keeps changing keeps a steady temperature fresh`() {
        val sink = Sink()
        val decoder = TpmsDecoder(sink)
        for (s in 0L..700L) {
            sink.now = s * 1_000_000
            decoder.feed(pressure(2, 230 + (s % 2).toInt()))
            decoder.feed(temp(9, 40))
        }
        assertTrue(sink.events.isEmpty())
        assertEquals(1402, sink.sets.size)
    }
}
