package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkEventsTest {
    private val events = mutableListOf<Pair<String, String>>()
    private val sink = object : SampleSink {
        override fun defineChannel(def: ChannelDef) = Unit

        override fun submit(set: SampleSet) = Unit

        override fun event(kind: String, text: String) {
            events += kind to text
        }

        override fun nowMicros() = 0L
    }

    @Test
    fun `connects and drops become events, and every reconnect resets the decoder`() {
        var resets = 0
        val onState = linkEvents(sink, "AA:BB", "up", "down") { resets++ }
        onState(LinkState.Connecting)
        onState(LinkState.Reconnecting(1, 1_000, "connect failed"))
        assertEquals("no drop event before data ever flowed", emptyList<Pair<String, String>>(), events)
        onState(LinkState.Connecting)
        onState(LinkState.Streaming(10))
        onState(LinkState.Streaming(9))
        onState(LinkState.Reconnecting(1, 1_000, "stream ended"))
        onState(LinkState.Connecting)
        onState(LinkState.Streaming(10))
        assertEquals(listOf("up" to "AA:BB", "down" to "stream ended", "up" to "AA:BB"), events)
        assertEquals(2, resets)
    }
}
