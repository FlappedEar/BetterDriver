@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import platform.Foundation.NSData
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A vLinker-like ELM327 behind a Bluetooth LE serial pair: answers each command in its write. */
private class SimPort(private val answers: Map<String, String>) : ObdBlePort {
    private val lock = NSRecursiveLock()
    private val pending = StringBuilder()
    private var events: ObdBleEvents? = null
    val commands = ArrayList<String>()

    /** Why the next opens fail, as the Swift link says it ("Bluetooth not allowed"); null opens. */
    var refuse: String? = null
    var opens = 0

    /** False: CoreBluetooth takes no more writes. */
    var writable = true

    override val payload = 20

    override fun open(events: ObdBleEvents): String? {
        lock.lock()
        opens++
        lock.unlock()
        refuse?.let { return it }
        this.events = events
        return null
    }

    /** The adapter goes out of range or is switched off, as CoreBluetooth's didDisconnect reports it. */
    fun drop() {
        events?.lost("disconnected")
    }

    override fun writeChunk(data: NSData): Boolean {
        if (!writable) return false
        val bytes = data.toByteArray()
        check(bytes.size <= payload) { "chunk of ${bytes.size} bytes" }
        val out = ArrayList<String>()
        lock.lock()
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c != '\r') {
                pending.append(c)
            } else {
                val cmd = pending.toString().trim()
                pending.clear()
                if (cmd.isNotEmpty()) {
                    commands += cmd
                    out += (answers[cmd] ?: "?") + "\r\r>"
                }
            }
        }
        lock.unlock()
        events?.writeDone(true)
        // Answers come in 20-byte notifications, as over the vLinker's LE link.
        for (text in out) text.encodeToByteArray().toList().chunked(20).forEach {
            events?.notified(it.toByteArray().toNSData())
        }
        return true
    }

    override fun disconnect() {
        events?.lost("closed")
    }
}

private class Sink : SampleSink {
    private val lock = NSRecursiveLock()
    private val defs = ArrayList<ChannelDef>()
    private val sets = ArrayList<SampleSet>()
    private val events = ArrayList<Pair<String, String>>()

    fun <T> read(body: () -> T): T {
        lock.lock()
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }

    val channelNames get() = read { defs.map { it.name } }
    val setCount get() = read { sets.size }
    val eventKinds get() = read { events.map { it.first } }
    val eventCount get() = read { events.size }

    override fun defineChannel(def: ChannelDef) = read { defs += def; Unit }

    override fun submit(set: SampleSet) = read { sets += set; Unit }

    override fun event(kind: String, text: String) = read { events += kind to text; Unit }

    override fun nowMicros(): Long = 10_000_000L
}

private val Car = mapOf(
    "ATZ" to "\r\rELM327 v2.3",
    "ATI" to "ELM327 v2.3",
    "AT@1" to "OBDII to RS232 Interpreter",
    "ATE0" to "ATE0\rOK",
    "ATL0" to "OK",
    "ATS0" to "OK",
    "ATH0" to "OK",
    "ATAT1" to "OK",
    "ATSP0" to "OK",
    "ATRV" to "12.4V",
    "ATDPN" to "A6",
    "0100" to "SEARCHING...\r410008188000",
    "010C" to "410C1AF8",
    "010D" to "410D32",
    "0105" to "41055A",
    "0111" to "41117F",
)

/**
 * FBBD-333: the iOS side of an OBD-II connection on the simulator: the connector on its own NSThread,
 * the NSCondition monitor, NSData in and out and the session channels, against a simulated adapter
 * over a Bluetooth LE pair.
 */
class ObdFeedTest {
    private val logs = ArrayList<String>()
    private val listener = object : ObdFeedListener {
        override fun log(text: String) {
            synchronizedLogs { logs += text }
        }
    }
    private val logLock = NSRecursiveLock()

    private fun <T> synchronizedLogs(body: () -> T): T {
        logLock.lock()
        try {
            return body()
        } finally {
            logLock.unlock()
        }
    }

    private fun stopped() = synchronizedLogs { logs.any { it.startsWith("reader stopped") } }

    private fun waitFor(timeoutS: Double, done: () -> Boolean): Boolean {
        var waited = 0.0
        while (!done() && waited < timeoutS) {
            NSThread.sleepForTimeInterval(0.05)
            waited += 0.05
        }
        return done()
    }

    @Test
    fun aVlinkerOverBluetoothLeIsSetUpAndRecorded() {
        val port = SimPort(Car)
        val sink = Sink()
        val logs = ArrayList<String>()
        val feed = ObdFeed(sink, { listOf(IosBleObdTarget("vLinker MC+", "uuid-1", port)) }, object : ObdFeedListener {
            override fun log(text: String) {
                logs += text
            }
        })
        feed.start()
        val recorded = waitFor(20.0) { sink.setCount >= 5 }
        val status = feed.status()
        feed.stop()
        assertTrue(recorded, "values recorded; adapter saw ${port.commands.take(30)}")
        assertTrue("obd_rpm" in sink.channelNames, "channels ${sink.channelNames}")
        assertEquals(ObdSessionEvents.CONNECTED, sink.eventKinds.first())
        assertEquals(ObdLinkState.Connected, status.state)
    }

    @Test
    fun withNoAdapterNearbyNothingIsRecordedAndTheFeedStops() {
        val sink = Sink()
        val feed = ObdFeed(sink, { emptyList() }, listener)
        feed.start()
        NSThread.sleepForTimeInterval(1.0)
        val status = feed.status()
        feed.stop()
        assertEquals(ObdLinkState.Searching, status.state)
        assertEquals(0, sink.eventCount, "events ${sink.eventKinds}")
        assertTrue(waitFor(10.0) { stopped() }, "the OBD thread ends; log ${synchronizedLogs { logs.toList() }}")
    }

    @Test
    fun bluetoothNotAllowedOnlyKeepsTheFeedSearching() {
        val port = SimPort(Car).apply { refuse = "Bluetooth not allowed" }
        val sink = Sink()
        val feed = ObdFeed(sink, { listOf(IosBleObdTarget("vLinker MC+", "uuid-1", port)) }, listener)
        feed.start()
        assertTrue(waitFor(10.0) { port.opens >= 1 })
        feed.retryNow()
        NSThread.sleepForTimeInterval(0.5)
        val status = feed.status()
        feed.stop()
        assertEquals(ObdLinkState.Searching, status.state)
        assertTrue(ObdSessionEvents.CONNECTED !in sink.eventKinds, "events ${sink.eventKinds}")
        assertTrue(waitFor(10.0) { stopped() }, "the OBD thread ends")
    }

    @Test
    fun aLostAdapterIsLoggedAndConnectedAgain() {
        val port = SimPort(Car)
        val sink = Sink()
        val feed = ObdFeed(sink, { listOf(IosBleObdTarget("vLinker MC+", "uuid-1", port)) }, listener)
        feed.start()
        assertTrue(waitFor(20.0) { sink.setCount >= 3 }, "first connection records")
        port.drop()
        assertTrue(waitFor(10.0) { ObdSessionEvents.DISCONNECTED in sink.eventKinds }, "events ${sink.eventKinds}")
        feed.retryNow()
        assertTrue(
            waitFor(20.0) { sink.eventKinds.count { it == ObdSessionEvents.CONNECTED } >= 2 },
            "connected again; events ${sink.eventKinds}",
        )
        feed.stop()
        assertTrue(waitFor(10.0) { stopped() }, "the OBD thread ends")
    }

    @Test
    fun refusedWritesEndTheConnectionWithoutStoppingTheRecording() {
        val port = SimPort(Car)
        val sink = Sink()
        val feed = ObdFeed(sink, { listOf(IosBleObdTarget("vLinker MC+", "uuid-1", port)) }, listener)
        feed.start()
        assertTrue(waitFor(20.0) { sink.setCount >= 3 }, "first connection records")
        port.writable = false
        assertTrue(waitFor(15.0) { ObdSessionEvents.DISCONNECTED in sink.eventKinds }, "events ${sink.eventKinds}")
        feed.stop()
        assertTrue(waitFor(10.0) { stopped() }, "the OBD thread ends")
    }

    @Test
    fun nsDataRoundTrips() {
        val bytes = byteArrayOf(0, 1, 0x7F, -1, 0x3E)
        assertTrue(bytes.contentEquals(bytes.toNSData().toByteArray()))
        assertEquals(0, ByteArray(0).toNSData().toByteArray().size)
    }

    @Test
    fun coreBluetoothShortUuidsMatchTheKnownPairs() {
        val all = listOf(
            GattCharacteristic("FFF0", "FFF1", BleSerial.PROPERTY_NOTIFY),
            GattCharacteristic("FFF0", "FFF2", BleSerial.PROPERTY_WRITE),
        )
        val expected = BleSerialPair(BleSerial.short(0xFFF0), BleSerial.short(0xFFF1), BleSerial.short(0xFFF2), true)
        assertEquals(expected, BleSerial.choose(all))
    }
}
