@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Notifications of a closed connection never reach the decoder of the next one (audit A09). The
 * link is modelled as [GattLink] runs it: a notification is delivered through the gate and counted
 * (the watchdog's data counter), and a reconnect closes the gate, resets the decoder, then opens the
 * gate for the new connection.
 */
class CurrentConnectionTest {
    private class Sink : SampleSink {
        val sets = mutableListOf<SampleSet>()

        override fun defineChannel(def: ChannelDef) = Unit

        override fun submit(set: SampleSet) {
            sets += set
        }

        override fun event(kind: String, text: String) = Unit

        override fun nowMicros() = 0L
    }

    private class Link {
        val sink = Sink()
        val decoder = RaceBoxDecoder(sink)
        val gate = CurrentConnection<Any>()
        var received = 0

        fun notification(from: Any, bytes: ByteArray) = gate.deliver(from) {
            received++
            decoder.feed(bytes)
        }

        fun reconnect(to: Any) {
            gate.close()
            decoder.reset()
            gate.open(to)
        }
    }

    private val old = Any()
    private val new = Any()

    @Test
    fun `a late notification of the old connection changes nothing on the new one`() {
        val link = Link()
        link.gate.open(old)
        assertTrue(link.notification(old, RaceBoxTestData.packet()))
        link.reconnect(new)
        assertFalse(link.notification(old, RaceBoxTestData.packet()))
        assertEquals(1, link.sets())
        assertEquals(1, link.received)
        assertEquals(1, link.decoder.messages)
    }

    @Test
    fun `half a packet of the old connection does not join the new connection's data`() {
        val link = Link()
        val packet = RaceBoxTestData.packet()
        link.gate.open(old)
        assertTrue(link.notification(old, packet.copyOfRange(0, 50)))
        link.reconnect(new)
        // The rest of the old packet comes late, then the new connection's first whole packet.
        assertFalse(link.notification(old, packet.copyOfRange(50, packet.size)))
        assertTrue(link.notification(new, packet))
        assertEquals(1, link.sets())
        assertEquals(0L, link.decoder.checksumErrors)
        assertEquals(2, link.received)
    }

    @Test
    fun `after stop nothing gets through`() {
        val link = Link()
        link.gate.open(old)
        link.gate.close()
        assertFalse(link.notification(old, RaceBoxTestData.packet()))
        assertEquals(0, link.received)
        assertEquals(0, link.sets())
    }

    @Test
    fun `close waits for a delivery already running`() {
        val gate = CurrentConnection<Any>()
        gate.open(old)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivering = thread { gate.deliver(old) { inside.countDown(); release.await() } }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val closed = CountDownLatch(1)
        val closing = thread { gate.close(); closed.countDown() }
        assertFalse("close returned while old bytes were still being handled", closed.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        delivering.join()
        closing.join()
        assertFalse(gate.deliver(old) {})
    }

    private fun Link.sets() = sink.sets.size
}
