package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaceBoxSessionTest {
    private class Sink : SampleSink {
        val channels = mutableListOf<ChannelDef>()
        val sets = mutableListOf<SampleSet>()
        val events = mutableListOf<Pair<String, String>>()
        override fun defineChannel(def: ChannelDef) {
            channels += def
        }
        override fun submit(set: SampleSet) {
            sets += set
        }
        override fun event(kind: String, text: String) {
            events += kind to text
        }
        override fun nowMicros() = 5_000_000L
    }

    private fun session(sink: Sink) =
        RaceBoxSession(sink, isMicro = true, calibration = null, log = {}, onSlowLink = {})

    @Test
    fun `start defines the channels and notes no calibration`() {
        val sink = Sink()
        session(sink).start()
        assertTrue(sink.channels.contains(RaceBoxChannels.INPUT_VOLTS))
        assertEquals(listOf(RaceBoxEvents.IMU_CALIBRATION to "none"), sink.events)
    }

    @Test
    fun `connect and drop are events once data has flowed`() {
        val sink = Sink()
        val s = session(sink)
        s.reconnecting("timeout before any data")
        s.streaming("A")
        s.streaming("A")
        s.feed(RaceBoxTestData.microPacket())
        s.reconnecting("link lost")
        s.reconnecting("still lost")
        assertEquals(
            listOf(RaceBoxEvents.CONNECTED to "A", RaceBoxEvents.DISCONNECTED to "link lost"),
            sink.events.filter { it.first == RaceBoxEvents.CONNECTED || it.first == RaceBoxEvents.DISCONNECTED },
        )
        assertEquals(1, sink.sets.size)
    }

    @Test
    fun `a drop throws away a partial packet`() {
        val sink = Sink()
        val s = session(sink)
        val packet = RaceBoxTestData.microPacket()
        s.feed(packet.copyOfRange(0, 30))
        s.reconnecting("link lost")
        s.feed(packet.copyOfRange(30, packet.size))
        s.feed(packet)
        assertEquals(1, sink.sets.size)
    }
}
