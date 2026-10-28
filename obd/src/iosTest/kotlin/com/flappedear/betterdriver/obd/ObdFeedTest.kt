@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
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
    fun aWifiAdapterIsSetUpAndRecorded() {
        val adapter = SimWifiAdapter(Car)
        val sink = Sink()
        val feed = ObdFeed(sink, { listOf(IosWifiObdTarget("127.0.0.1", adapter.port)) }, listener)
        feed.start()
        val recorded = waitFor(20.0) { sink.setCount >= 5 }
        feed.stop()
        adapter.close()
        assertTrue(recorded, "values recorded; log ${synchronizedLogs { logs.toList() }}")
        assertTrue("obd_rpm" in sink.channelNames, "channels ${sink.channelNames}")
        assertEquals(ObdSessionEvents.CONNECTED, sink.eventKinds.first())
        assertTrue(waitFor(10.0) { stopped() }, "the OBD thread ends")
    }

    @Test
    fun withNoWifiAdapterTheFeedKeepsSearchingAndRecordsNothing() {
        // Nothing listens on the port: as with the phone on another network, every connect fails.
        val closedPort = SimWifiAdapter(Car).let { val p = it.port; it.close(); p }
        val sink = Sink()
        val feed = ObdFeed(sink, { listOf(IosWifiObdTarget("127.0.0.1", closedPort)) }, listener)
        feed.start()
        NSThread.sleepForTimeInterval(1.5)
        feed.retryNow()
        NSThread.sleepForTimeInterval(0.5)
        val status = feed.status()
        feed.stop()
        assertEquals(ObdLinkState.Searching, status.state)
        assertEquals(0, sink.eventCount, "events ${sink.eventKinds}")
        assertTrue(waitFor(10.0) { stopped() }, "the OBD thread ends; log ${synchronizedLogs { logs.toList() }}")
    }

    @Test
    fun aWifiAdapterThatDropsIsConnectedAgain() {
        val adapter = SimWifiAdapter(Car)
        val sink = Sink()
        val feed = ObdFeed(sink, { listOf(IosWifiObdTarget("127.0.0.1", adapter.port)) }, listener)
        feed.start()
        assertTrue(waitFor(20.0) { sink.setCount >= 3 }, "first connection records")
        adapter.dropClients()
        assertTrue(waitFor(15.0) { ObdSessionEvents.DISCONNECTED in sink.eventKinds }, "events ${sink.eventKinds}")
        feed.retryNow()
        assertTrue(
            waitFor(20.0) { sink.eventKinds.count { it == ObdSessionEvents.CONNECTED } >= 2 },
            "connected again; events ${sink.eventKinds}",
        )
        feed.stop()
        adapter.close()
        assertTrue(waitFor(10.0) { stopped() }, "the OBD thread ends")
    }

    @Test
    fun aWifiAddressThatIsNotIpv4FailsToOpenWithoutACrash() {
        val bytes = object : ObdBytes {
            override fun received(bytes: ByteArray) = Unit

            override fun closed(reason: String) = Unit
        }
        val failed = try {
            IosWifiObdTarget("adapter.local", 35000).open(bytes)
            false
        } catch (_: IOException) {
            true
        }
        assertTrue(failed)
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

/**
 * A cheap Wi-Fi ELM327 on the phone itself: a TCP server on 127.0.0.1 (a free port) that answers
 * each command line from [answers], as the adapter at 192.168.0.10:35000 does.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private class SimWifiAdapter(private val answers: Map<String, String>) {
    private val lock = NSRecursiveLock()
    private val listenFd: Int
    private val clients = ArrayList<Int>()

    @kotlin.concurrent.Volatile
    private var open = true
    val port: Int

    init {
        listenFd = platform.posix.socket(platform.posix.AF_INET, platform.posix.SOCK_STREAM, 0)
        check(listenFd >= 0)
        port = kotlinx.cinterop.memScoped {
            val addr = alloc<platform.posix.sockaddr_in>()
            addr.sin_len = sizeOf<platform.posix.sockaddr_in>().convert()
            addr.sin_family = platform.posix.AF_INET.convert()
            addr.sin_port = 0u
            platform.posix.inet_pton(platform.posix.AF_INET, "127.0.0.1", addr.sin_addr.ptr)
            val size = sizeOf<platform.posix.sockaddr_in>()
            check(platform.posix.bind(listenFd, addr.ptr.reinterpret(), size.convert()) == 0)
            check(platform.posix.listen(listenFd, 4) == 0)
            val len = alloc<platform.posix.socklen_tVar>()
            len.value = size.convert()
            platform.posix.getsockname(listenFd, addr.ptr.reinterpret(), len.ptr)
            val p = addr.sin_port.toInt()
            ((p and 0xFF) shl 8) or ((p shr 8) and 0xFF)
        }
        NSThread { accept() }.start()
    }

    private fun accept() {
        while (open) {
            val fd = platform.posix.accept(listenFd, null, null)
            if (fd < 0) return
            lock.lock()
            clients += fd
            lock.unlock()
            NSThread { serve(fd) }.start()
        }
    }

    private fun serve(fd: Int) {
        // A reply to a phone that hung up must not end the test run with SIGPIPE (Darwin SO_NOSIGPIPE).
        kotlinx.cinterop.memScoped {
            val on = alloc<kotlinx.cinterop.IntVar>()
            on.value = 1
            val size = sizeOf<kotlinx.cinterop.IntVar>()
            platform.posix.setsockopt(fd, platform.posix.SOL_SOCKET, 0x1022, on.ptr, size.convert())
        }
        val buf = ByteArray(256)
        val pending = StringBuilder()
        buf.usePinned { pin ->
            while (true) {
                val n = platform.posix.recv(fd, pin.addressOf(0), buf.size.convert(), 0)
                if (n <= 0) break
                for (i in 0 until n.toInt()) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c != '\r') pending.append(c) else answer(fd, pending)
                }
            }
        }
        platform.posix.close(fd)
    }

    private fun answer(fd: Int, pending: StringBuilder) {
        val cmd = pending.toString().trim()
        pending.clear()
        if (cmd.isEmpty()) return
        val out = ((answers[cmd] ?: "?") + "\r\r>").encodeToByteArray()
        out.usePinned { platform.posix.send(fd, it.addressOf(0), out.size.convert(), 0) }
    }

    /** The adapter's Wi-Fi drops: every open connection ends. */
    fun dropClients() {
        lock.lock()
        clients.forEach { platform.posix.shutdown(it, platform.posix.SHUT_RDWR) }
        clients.clear()
        lock.unlock()
    }

    fun close() {
        open = false
        dropClients()
        platform.posix.shutdown(listenFd, platform.posix.SHUT_RDWR)
        platform.posix.close(listenFd)
    }
}
