@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.nmea

import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [TcpLink] (and through it [StreamLink]) against a real socket on the loopback interface. */
class TcpLinkTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val port get() = server.localPort
    private val host = InetAddress.getLoopbackAddress().hostAddress!!
    private val states = CopyOnWriteArrayList<LinkState>()
    private val received = ByteArrayOutputStream()
    private var link: TcpLink? = null

    @After
    fun tearDown() {
        link?.stop()
        server.close()
    }

    private fun start(timing: StreamTiming = StreamTiming(firstRetryMs = 100, maxRetryMs = 200)): TcpLink {
        val onData = { bytes: ByteArray -> synchronized(received) { received.write(bytes) } }
        return TcpLink(host, port, onData, onState = { states += it }, timing).also {
            link = it
            it.start()
        }
    }

    private fun receivedText() = synchronized(received) { received.toString(Charsets.US_ASCII.name()) }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what; states $states")
            Thread.sleep(20)
        }
    }

    private fun Socket.send(text: String) {
        getOutputStream().apply {
            write(text.toByteArray(Charsets.US_ASCII))
            flush()
        }
    }

    @Test
    fun `connects, passes bytes on and reports the line rate`() {
        start()
        server.accept().use { device ->
            assertEquals(LinkState.Connecting, states.first())
            repeat(3) { device.send("\$GPGGA,$it\r\n") }
            waitFor("data") { receivedText() == "\$GPGGA,0\r\n\$GPGGA,1\r\n\$GPGGA,2\r\n" }
            waitFor("streaming") { states.any { it is LinkState.Streaming } }
            val hz = states.filterIsInstance<LinkState.Streaming>().first().hz
            assertTrue("hz $hz", hz in 0..3)
        }
    }

    @Test
    fun `reconnects after the device closes the connection`() {
        start()
        server.accept().use { it.send("a\n") }
        waitFor("reconnecting") { states.any { it is LinkState.Reconnecting } }
        val r = states.filterIsInstance<LinkState.Reconnecting>().first()
        assertEquals("stream ended", r.reason)
        assertEquals(1, r.attempt)
        server.accept().use { device ->
            device.send("b\n")
            waitFor("data after reconnect") { receivedText() == "a\nb\n" }
        }
    }

    @Test
    fun `asks for the network again on each connect`() {
        var asked = 0
        val network = {
            asked++
            TcpNetwork.Default
        }
        link = TcpLink(host, port, onData = {}, timing = StreamTiming(firstRetryMs = 100), network = network).also {
            it.start()
        }
        server.accept().close()
        server.accept().use { waitFor("second connect") { asked == 2 } }
    }

    @Test
    fun `a silent connection is closed and retried`() {
        start(StreamTiming(firstRetryMs = 100, maxRetryMs = 200, silenceTimeoutMs = 500))
        server.accept().use {
            waitFor("silence detected") { states.any { it is LinkState.Reconnecting } }
            assertEquals("no data after connecting", states.filterIsInstance<LinkState.Reconnecting>().first().reason)
        }
    }

    @Test
    fun `a refused connect is retried with back-off`() {
        server.close()
        start()
        waitFor("two attempts") { states.filterIsInstance<LinkState.Reconnecting>().size >= 2 }
        val (first, second) = states.filterIsInstance<LinkState.Reconnecting>()
        assertTrue(first.reason, first.reason.startsWith("connect to $host:$port failed"))
        assertEquals(100L, first.delayMs)
        assertEquals(200L, second.delayMs)
    }

    @Test
    fun `stop ends in Idle and nothing after it`() {
        val l = start()
        server.accept().use { device ->
            device.send("x\n")
            waitFor("data") { receivedText() == "x\n" }
            l.stop()
            assertEquals(LinkState.Idle, states.last())
            val count = states.size
            Thread.sleep(300)
            assertEquals(count, states.size)
            assertEquals(LinkState.Idle, l.state.value)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a port out of range is refused`() {
        TcpLink(host, 0, onData = {})
    }

    @Test
    fun `TcpNmeaSource records fixes and RC lines from the stream`() {
        val sink = object : SampleSink {
            val channels = CopyOnWriteArrayList<ChannelDef>()
            val sets = CopyOnWriteArrayList<SampleSet>()
            val events = CopyOnWriteArrayList<Pair<String, String>>()

            override fun defineChannel(def: ChannelDef) {
                channels += def
            }

            override fun submit(set: SampleSet) {
                sets += set
            }

            override fun event(kind: String, text: String) {
                events += kind to text
            }

            override fun nowMicros() = 0L
        }
        val source = TcpNmeaSource(host, port, "ESP32 DIY")
        source.start(sink)
        try {
            server.accept().use { device ->
                device.send(
                    nmeaSentence("GNRMC,101112.00,A,5013.1234,N,01902.5678,E,10.0,45.0,111026,,,A") +
                        nmeaSentence("RC2,101112.00,,0.3,0.4,1.0,,,,,,,,,,") +
                        nmeaSentence("GNGGA,101112.00,5013.1234,N,01902.5678,E,1,9,0.9,250.0,M,40.0,M,,"),
                )
                waitFor("two sets") { sink.sets.size == 2 }
                waitFor("connected event") { sink.events.any { it.first == NmeaEvents.CONNECTED } }
            }
        } finally {
            source.stop()
        }
        assertEquals(NmeaEvents.DEVICE to "address=$host:$port name=ESP32 DIY", sink.events.first())
        assertTrue(sink.events.contains(NmeaEvents.CONNECTED to "$host:$port"))
        assertTrue(sink.sets.any { set -> set.values.any { it.channelId == GnssChannels.LAT.id } })
        assertTrue(sink.sets.any { set -> set.values.any { it.channelId == RcChannels.ACCEL_X.id } })
        assertTrue(sink.channels.containsAll(RcChannels.ALL))
    }
}
