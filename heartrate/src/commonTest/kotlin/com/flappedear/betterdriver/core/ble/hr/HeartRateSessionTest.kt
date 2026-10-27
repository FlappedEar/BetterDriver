@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartRateSessionTest {
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

    private val worn = byteArrayOf(0x06, 90)

    @Test
    fun `logs the strap connects and drops as the Android source did`() {
        val sink = Sink()
        val session = HeartRateSession(sink, "A1", "Polar H10") {}
        session.start()
        assertEquals(HeartRateChannels.ALL, sink.channels)
        assertEquals(HeartRateEvents.DEVICE to "address=A1 name=Polar H10", sink.events.single())
        // A drop before any data is not a disconnect.
        assertTrue(session.reconnecting("timeout"))
        session.connecting()
        assertTrue(session.streaming())
        assertTrue(session.streaming())
        session.feed(worn)
        assertTrue(session.reconnecting("lost"))
        assertEquals(
            listOf(HeartRateEvents.CONNECTED to "A1", HeartRateEvents.DISCONNECTED to "lost"),
            sink.events.drop(1),
        )
        assertEquals(90.0, sink.sets.single().values.single().value)
    }

    @Test
    fun `the status shows only a fresh heart rate while streaming`() {
        val sink = Sink()
        val session = HeartRateSession(sink, "A1", "strap") {}
        session.start()
        assertEquals(HeartRateStatus(HeartRateLink.CONNECTING, null), session.status())
        session.streaming()
        sink.now = 1_000_000
        session.feed(worn)
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, 90), session.status())
        sink.now = 6_000_001
        assertEquals(HeartRateStatus(HeartRateLink.STREAMING, null), session.status())
        session.feed(byteArrayOf(0x04, 0))
        assertEquals(HeartRateStatus(HeartRateLink.NO_CONTACT, null), session.status())
        session.reconnecting("lost")
        assertEquals(HeartRateStatus(HeartRateLink.LOST, null), session.status())
    }

    @Test
    fun `a strap not worn at the start is off for the session`() {
        val sink = Sink()
        var offs = 0
        val session = HeartRateSession(sink, "A1", "strap") { offs++ }
        session.start()
        session.streaming()
        session.feed(byteArrayOf(0, 21))
        sink.now = StartWindowMicros + 1
        session.feed(worn)
        assertTrue(session.off)
        assertEquals(1, offs)
        assertEquals(
            HeartRateEvents.OFF to "sent no plausible heart rate (1 left out) in the first 45 s: off for this session",
            sink.events.last(),
        )
        assertTrue(sink.sets.isEmpty())
        assertNull(session.status())
        assertFalse(session.streaming())
        assertEquals(1, offs)
    }

    @Test
    fun `a strap worn at the start stays on`() {
        val sink = Sink()
        val session = HeartRateSession(sink, "A1", "strap") {}
        session.start()
        session.streaming()
        repeat(WornAfterValues) { session.feed(worn) }
        sink.now = StartWindowMicros * 2
        session.feed(worn)
        assertFalse(session.off)
        assertEquals(WornAfterValues + 1, sink.sets.size)
    }
}
