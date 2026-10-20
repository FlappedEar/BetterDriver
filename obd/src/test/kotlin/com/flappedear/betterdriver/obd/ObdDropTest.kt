@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import java.io.Closeable
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a simulated adapter does on its [n]th open (from 0): drop the connection after [dropAfter]
 * commands, or stop answering, with the line still open, after [hangAfter] commands.
 */
private data class Fault(val dropAfter: Int = Int.MAX_VALUE, val hangAfter: Int = Int.MAX_VALUE)

/**
 * [SimElm] over a byte stream (Wi-Fi TCP or Bluetooth Classic SPP) whose [faults] say, per open,
 * when it drops or hangs; opens past the list behave.
 */
private class FlakyStreamTarget(
    private val elm: SimElm,
    override val where: String,
    override val transport: ObdTransport,
    private val faults: List<Fault>,
) : ObdTarget {
    val opens = AtomicInteger()

    override fun open(bytes: ObdBytes): ObdConnection {
        val fault = faults.getOrElse(opens.getAndIncrement()) { Fault() }
        val toAdapter = PipedInputStream(4_096)
        val phoneOut = PipedOutputStream(toAdapter)
        val toPhone = PipedInputStream(4_096)
        val adapterOut = PipedOutputStream(toPhone)
        val start = elm.commands.size
        Thread({
            val buf = ByteArray(256)
            try {
                var n = toAdapter.read(buf)
                while (n >= 0 && elm.commands.size - start < fault.dropAfter) {
                    val answers = elm.receive(buf.copyOf(n))
                    if (elm.commands.size - start <= fault.hangAfter) {
                        for (answer in answers) {
                            adapterOut.write(answer)
                            adapterOut.flush()
                        }
                    }
                    n = toAdapter.read(buf)
                }
            } catch (_: IOException) {
                // The phone closed its end.
            }
            adapterOut.close()
        }, "sim-flaky-stream").apply {
            isDaemon = true
            start()
        }
        val both = Closeable {
            phoneOut.close()
            toPhone.close()
        }
        return StreamObdConnection(toPhone, phoneOut, both, bytes, "sim-flaky-reader")
    }
}

/** [SimElm] behind a Bluetooth LE serial pair whose [faults] say, per open, when the link is lost or hangs. */
private class FlakyBleTarget(
    private val elm: SimElm,
    override val where: String,
    private val faults: List<Fault>,
) : ObdTarget {
    override val transport = ObdTransport.BluetoothLe
    val opens = AtomicInteger()

    override fun open(bytes: ObdBytes): ObdConnection {
        val fault = faults.getOrElse(opens.getAndIncrement()) { Fault() }
        val binder = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sim-flaky-binder").apply { isDaemon = true }
        }
        val start = elm.commands.size
        lateinit var connection: BleSerialConnection
        val port = object : BleSerialPort {
            override fun write(chunk: ByteArray): Boolean {
                binder.schedule({
                    connection.writeDone(true)
                    val answers = elm.receive(chunk)
                    val count = elm.commands.size - start
                    when {
                        count >= fault.dropAfter -> connection.lost("GATT status 8 (link lost)")
                        count <= fault.hangAfter -> answers.forEach { a ->
                            a.toList().chunked(BleSerialConnection.DEFAULT_PAYLOAD).forEach {
                                connection.notified(it.toByteArray())
                            }
                        }
                    }
                }, 2, TimeUnit.MILLISECONDS)
                return true
            }

            override fun disconnect() {
                binder.shutdownNow()
            }
        }
        connection = BleSerialConnection(port, bytes, BleSerialConnection.DEFAULT_PAYLOAD)
        return connection
    }
}

private class Heard : ObdConnectorListener {
    val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    val ready: MutableList<ObdTarget> = Collections.synchronizedList(ArrayList())
    val ended: MutableList<String> = Collections.synchronizedList(ArrayList())
    val values = AtomicInteger()

    override fun ready(
        target: ObdTarget,
        adapter: String,
        protocol: String,
        voltage: String?,
        supported: Set<Int>,
        channels: List<PidChannel>,
    ) {
        ready += target
    }

    override fun value(channel: PidChannel, value: Double, atMs: Long) {
        values.incrementAndGet()
    }

    override fun ended(target: ObdTarget, reason: String) {
        ended += reason
    }

    override fun log(text: String) {
        logs += text
    }
}

/** Real time, waits cut to 20 ms. */
private object QuickClock : ObdClock {
    override fun nowMs() = System.nanoTime() / 1_000_000

    override fun sleep(ms: Long) = Thread.sleep(ms.coerceAtMost(20))
}

/** Real time and real waits, and an interrupt ends a wait quietly: the app's clock (ObdLink). */
private object AppClock : ObdClock {
    override fun nowMs() = System.nanoTime() / 1_000_000

    override fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // Only stopping interrupts.
        }
    }
}

/** Time that passes only when the connector waits, so minutes of searching take no time. */
private class VirtualClock : ObdClock {
    @Volatile
    var now = 0L

    override fun nowMs() = now

    override fun sleep(ms: Long) {
        now += ms
    }
}

/**
 * FBBD-271 and FBBD-269, from the drive of 20 Oct: a Wi-Fi adapter that drops mid-session (and the
 * phone off its network for a while) is connected again, over every transport; stopping a session
 * while a request waits for the adapter ends the OBD thread quietly, never with an uncaught
 * exception (which killed the recorder process). Simulated adapters only.
 */
class ObdDropTest {
    /**
     * Runs a connector as the app does (its own thread, stopped by clearing running, closing the
     * connection and interrupting) until [until], then stops it; with [interruptFirst] the interrupt
     * comes alone and the close only after the thread has ended. Returns what it heard and the
     * exception that escaped the thread, if any.
     */
    private fun runLikeTheApp(
        connector: (Heard) -> ObdConnector,
        interruptFirst: Boolean = false,
        until: (Heard) -> Boolean,
    ): Pair<Heard, Throwable?> {
        val heard = Heard()
        val c = connector(heard)
        val running = AtomicBoolean(true)
        var escaped: Throwable? = null
        val thread = Thread({ c.run { running.get() } }, "obd-test").apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, e -> escaped = e }
            start()
        }
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!until(heard) && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(until(heard), "condition not met in 20 s: ${heard.logs}")
        running.set(false)
        if (interruptFirst) {
            // The interrupt alone, before any close: the path that crashed on 20 Oct.
            thread.interrupt()
            thread.join(5_000)
            c.stop()
        } else {
            c.stop()
            thread.interrupt()
        }
        thread.join(5_000)
        assertFalse(thread.isAlive, "the OBD thread did not end: ${heard.logs}")
        return heard to escaped
    }

    private fun reconnectsAfterDrop(target: ObdTarget, gone: Int) {
        // After the drop the phone has no way to the adapter for [gone] rounds, as when its Wi-Fi went.
        val dropped = AtomicBoolean(false)
        var roundsWithout = 0
        val targets = {
            if (dropped.get() && roundsWithout < gone) {
                roundsWithout++
                emptyList()
            } else {
                listOf(target)
            }
        }
        val (heard, escaped) = runLikeTheApp({ h ->
            val marking = object : ObdConnectorListener by h {
                override fun ended(target: ObdTarget, reason: String) {
                    dropped.set(true)
                    h.ended(target, reason)
                }
            }
            ObdConnector(targets, marking, ObdStatusTracker(), QuickClock, retryMs = listOf(10L))
        }) { it.ready.size >= 2 && it.values.get() >= 10 }
        assertNull(escaped)
        assertEquals(2, heard.ready.size, heard.logs.toString())
        assertTrue(heard.ended.first().startsWith("connection lost"), heard.ended.toString())
        assertEquals(gone, roundsWithout)
        assertEquals(1, heard.logs.count { it.startsWith("no adapter:") }, heard.logs.toString())
    }

    @Test
    fun `a Wi-Fi adapter that drops mid-session is connected again once the phone is back on its network`() {
        val target = FlakyStreamTarget(
            SimElm(SimElm.car(SimElm.Vlinker)),
            WifiAt,
            ObdTransport.Wifi,
            listOf(Fault(dropAfter = 40)),
        )
        reconnectsAfterDrop(target, gone = 4)
    }

    @Test
    fun `a Bluetooth Classic adapter that drops mid-session is connected again`() {
        val target = FlakyStreamTarget(
            SimElm(SimElm.car(SimElm.Stn)),
            "OBDLink MX+ (Bluetooth)",
            ObdTransport.BluetoothClassic,
            listOf(Fault(dropAfter = 40)),
        )
        reconnectsAfterDrop(target, gone = 2)
    }

    @Test
    fun `a Bluetooth LE adapter whose link is lost mid-session is connected again`() {
        val elm = SimElm(SimElm.car(SimElm.Vlinker))
        val target = FlakyBleTarget(elm, "vLinker MC+ (Bluetooth LE)", listOf(Fault(dropAfter = 40)))
        reconnectsAfterDrop(target, gone = 2)
    }

    private fun stopsQuietlyDuringRequest(target: ObdTarget, elm: SimElm, interruptFirst: Boolean) {
        val (heard, escaped) = runLikeTheApp({ h ->
            ObdConnector({ listOf(target) }, h, ObdStatusTracker(), AppClock)
        }, interruptFirst) {
            // Set up, polled a while, then a request is waiting for an answer that never comes.
            it.ready.isNotEmpty() && elm.commands.size > HangAfter
        }
        assertNull(escaped, "uncaught on the OBD thread: $escaped")
        assertEquals(1, heard.ready.size)
        val ended = heard.logs.filter { it.startsWith("connection to ${target.where} ended") }
        // The stop came while the request waited: the interrupt or the close ended it, not a timeout.
        val by = if (interruptFirst) "interrupted" else "closed"
        assertEquals(listOf("connection to ${target.where} ended: connection lost: $by"), ended, heard.logs.toString())
    }

    @Test
    fun `stopping during a Wi-Fi request ends the OBD thread quietly`() {
        val elm = SimElm(SimElm.car(SimElm.Vlinker))
        val target = FlakyStreamTarget(elm, WifiAt, ObdTransport.Wifi, listOf(Fault(hangAfter = HangAfter)))
        stopsQuietlyDuringRequest(target, elm, interruptFirst = false)
    }

    @Test
    fun `an interrupt that comes before the close during a Wi-Fi request is not a crash`() {
        // The order that crashed the recorder on 20 Oct at 16:23:05 (FBBD-269).
        val elm = SimElm(SimElm.car(SimElm.Vlinker))
        val target = FlakyStreamTarget(elm, WifiAt, ObdTransport.Wifi, listOf(Fault(hangAfter = HangAfter)))
        stopsQuietlyDuringRequest(target, elm, interruptFirst = true)
    }

    @Test
    fun `stopping during a Bluetooth Classic request ends the OBD thread quietly`() {
        val elm = SimElm(SimElm.car(SimElm.Stn))
        val target = FlakyStreamTarget(
            elm,
            "OBDLink MX+ (Bluetooth)",
            ObdTransport.BluetoothClassic,
            listOf(Fault(hangAfter = HangAfter)),
        )
        stopsQuietlyDuringRequest(target, elm, interruptFirst = true)
    }

    @Test
    fun `stopping during a Bluetooth LE request ends the OBD thread quietly`() {
        val elm = SimElm(SimElm.car(SimElm.Vlinker))
        val target = FlakyBleTarget(elm, "vLinker MC+ (Bluetooth LE)", listOf(Fault(hangAfter = HangAfter)))
        stopsQuietlyDuringRequest(target, elm, interruptFirst = true)
    }

    @Test
    fun `an interrupted command fails like a closed line`() {
        val line = ElmLine(write = {})
        Thread.currentThread().interrupt()
        val failed = runCatching { line.command("010C", 1_000) }.exceptionOrNull()
        // Clear the flag again so the test runner's thread is left as it was.
        assertTrue(Thread.interrupted())
        assertTrue(failed is IOException, failed.toString())
    }

    @Test
    fun `Wi-Fi coming back ends a long wait at once`() {
        val target = FlakyStreamTarget(SimElm(SimElm.car(SimElm.Vlinker)), WifiAt, ObdTransport.Wifi, emptyList())
        val wifi = AtomicBoolean(false)
        lateinit var c: ObdConnector
        val started = System.nanoTime()
        val (heard, escaped) = runLikeTheApp({ h ->
            val targets = { if (wifi.get()) listOf(target) else emptyList() }
            ObdConnector(targets, h, ObdStatusTracker(), AppClock, retryMs = listOf(60_000L))
                .also { c = it }
        }) {
            if (!wifi.get() && it.logs.any { l -> l.startsWith("no adapter:") }) {
                wifi.set(true)
                c.retryNow()
            }
            it.ready.isNotEmpty()
        }
        assertNull(escaped)
        assertEquals(1, heard.ready.size)
        assertTrue(System.nanoTime() - started < 30_000_000_000L, "waited for the 60 s retry")
    }

    @Test
    fun `a round that fails unexpectedly is logged and retried`() {
        val target = FlakyStreamTarget(SimElm(SimElm.car(SimElm.Vlinker)), WifiAt, ObdTransport.Wifi, emptyList())
        var rounds = 0
        val (heard, escaped) = runLikeTheApp({ h ->
            ObdConnector({
                if (rounds++ == 0) error("broken target list") else listOf(target)
            }, h, ObdStatusTracker(), QuickClock, retryMs = listOf(10L))
        }) { it.ready.isNotEmpty() }
        assertNull(escaped)
        val failed = heard.logs.filter { it.startsWith("round failed: java.lang.IllegalStateException") }
        assertEquals(1, failed.size, heard.logs.toString())
    }

    @Test
    fun `a long search is logged again every minute, so the log shows it is still running`() {
        val clock = VirtualClock()
        val (heard, _) = runLikeTheApp({ h ->
            ObdConnector({ emptyList() }, h, ObdStatusTracker(), clock, retryMs = listOf(20_000L))
        }) { clock.now >= 200_000L }
        assertEquals(1, heard.logs.count { it.startsWith("no adapter:") }, heard.logs.toString())
        val still = heard.logs.filter { it.startsWith("still searching after ") }
        assertTrue(still.size >= 3, heard.logs.toString())
        assertTrue(still.first().startsWith("still searching after 60 s: no adapter:"), still.first())
    }

    private companion object {
        /** Setup and discovery take about 15 commands; the hang comes while polling. */
        const val HangAfter = 30

        /** Where cheap Wi-Fi ELM327 adapters listen. */
        const val WifiAt = "192.168.0.10:35000"

    }
}
