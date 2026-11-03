@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the simulated vLinker does after its [n]th open: lose the link [dropAfterMs] after it opened (the
 * phone hears a GATT disconnect), or [hangAfterMs] after it go silent with the link still up (the phone
 * hears nothing at all, as when the adapter's firmware stalls or it loses power and the phone's Bluetooth
 * stack has not noticed yet).
 */
private data class VlinkerFault(val dropAfterMs: Long = Long.MAX_VALUE, val hangAfterMs: Long = Long.MAX_VALUE)

/**
 * The Vgate vLinker MC+ on Arek's first-generation FR-S (FBBD-271, FBBD-320), behind a Bluetooth LE serial
 * pair, in real time: an STN1151 that answers the setup as it did in the 2026-11-02 log (ELM327 v2.2,
 * STN1151 v4.3.2, protocol A6, the FR-S VIN), takes STN pass filters, and while monitoring (`STM`, `AT MA`)
 * prints the FR-S broadcast frames (steering 0D0, brake 0D1, pedal/rpm 140, oil/coolant 360) every 10 ms
 * until it gets a byte. ATF answers behind header 7E1. Each open follows its [faults] entry; while [away]
 * holds, opening fails as a connect to an adapter that is off or out of range does.
 */
private class VlinkerFrs(private val faults: List<VlinkerFault> = emptyList()) : ObdTarget {
    override val where = "vLinker MC-IOS (Bluetooth LE)"
    override val transport = ObdTransport.BluetoothLe
    val opens = AtomicInteger()
    val away = AtomicBoolean(false)
    val connectTries = AtomicInteger()

    override fun open(bytes: ObdBytes): ObdConnection {
        connectTries.incrementAndGet()
        if (away.get()) throw IOException("setup timed out")
        val fault = faults.getOrElse(opens.getAndIncrement()) { VlinkerFault() }
        return Adapter(fault, bytes).connection
    }

    private class Adapter(private val fault: VlinkerFault, bytes: ObdBytes) {
        private val opened = System.nanoTime()
        private val binder = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sim-vlinker-binder").apply { isDaemon = true }
        }
        private val pending = StringBuilder()
        private var headers = false
        private var header = 0x7DF
        private var filter: Int? = null
        private val passes = LinkedHashSet<Int>()
        private var stn = false
        private var stream: ScheduledFuture<*>? = null
        private var lost = false

        val connection: BleSerialConnection = BleSerialConnection(
            object : BleSerialPort {
                override fun write(chunk: ByteArray): Boolean {
                    binder.schedule({ written(chunk) }, 2, TimeUnit.MILLISECONDS)
                    return true
                }

                override fun disconnect() {
                    binder.shutdownNow()
                }
            },
            bytes,
            BleSerialConnection.DEFAULT_PAYLOAD,
        )

        init {
            if (fault.dropAfterMs != Long.MAX_VALUE) {
                binder.schedule({ loseLink() }, fault.dropAfterMs, TimeUnit.MILLISECONDS)
            }
        }

        private fun ageMs() = (System.nanoTime() - opened) / 1_000_000

        private fun hung() = ageMs() >= fault.hangAfterMs

        private fun loseLink() {
            lost = true
            stream?.cancel(false)
            connection.lost("GATT status 8 (link lost)")
        }

        /** Binder thread: the GATT stack acknowledged the write; the adapter reads it. */
        @Suppress("ReturnCount") // Lost, hung, or a byte that stops the monitor: each ends the write here.
        private fun written(chunk: ByteArray) {
            if (lost) return
            // A hung adapter's radio may still acknowledge writes; its firmware answers nothing.
            connection.writeDone(true)
            if (hung()) {
                stream?.cancel(false)
                return
            }
            if (stream != null) {
                // Any byte stops the monitor.
                stream?.cancel(false)
                stream = null
                send("STOPPED\r\r>")
                return
            }
            for (b in chunk) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c != '\r') {
                    pending.append(c)
                    continue
                }
                val cmd = pending.toString().trim().uppercase()
                pending.setLength(0)
                if (cmd.isNotEmpty()) answer(cmd)
            }
        }

        @Suppress("CyclomaticComplexMethod") // One branch per command the adapter knows.
        private fun answer(cmd: String) {
            when {
                cmd == "ATMA" || cmd == "STM" -> return monitor(stnFilters = cmd == "STM")
                cmd == "ATH1" -> headers = true
                cmd == "ATH0" -> headers = false
                cmd == "ATCRA" || cmd == "ATAR" -> filter = null
                cmd.startsWith("ATCRA") -> filter = cmd.removePrefix("ATCRA").toInt(16)
                cmd.startsWith("ATSH") -> header = cmd.removePrefix("ATSH").toInt(16)
                cmd == "STFCP" -> passes.clear()
                cmd.startsWith("STFAP") -> passes += cmd.removePrefix("STFAP").trim().substringBefore(',').toInt(16)
            }
            val text = when {
                cmd == "ATZ" -> "\r\rELM327 v2.2"
                cmd == "ATI" -> "ELM327 v2.2"
                cmd == "AT@1" -> "OBDII to RS232 Interpreter"
                cmd == "STI" -> "STN1151 v4.3.2"
                cmd == "STDI" -> "vLinker MC r2"
                cmd == "ATRV" -> "14.4V"
                cmd == "ATDPN" -> "A6"
                cmd == "0100" -> "SEARCHING...\r410000100000"
                cmd == "010C" -> "410C1AF8"
                cmd == "0902" -> vin()
                cmd.startsWith("2182") -> if (header == 0x7E1) "61825A66" else "NO DATA"
                cmd.startsWith("AT") || cmd.startsWith("ST") -> "OK"
                else -> "NO DATA"
            }
            send("$text\r\r>")
        }

        /** Frames every 10 ms, through the pass filters ([stnFilters]) or the `AT CRA` filter. */
        private fun monitor(stnFilters: Boolean) {
            stn = stnFilters
            stream = binder.scheduleAtFixedRate({
                if (lost || hung()) return@scheduleAtFixedRate
                val lines = Frames.filterKeys { id ->
                    if (stn) id in passes else filter == null || id == filter
                }.map { (id, data) -> (if (headers) "%03X".format(id) else "") + data }
                if (lines.isNotEmpty()) send(lines.joinToString("\r", postfix = "\r"))
            }, 5, 10, TimeUnit.MILLISECONDS)
        }

        private fun send(text: String) {
            if (lost || hung()) return
            text.encodeToByteArray().toList().chunked(BleSerialConnection.DEFAULT_PAYLOAD).forEach {
                connection.notified(it.toByteArray())
            }
        }

        private fun vin(): String {
            val bytes = "490201" + FrsVin.map { "%02X".format(it.code) }.joinToString("")
            val pairs = bytes.chunked(2)
            return "014\r0:" + pairs.take(6).joinToString("") + "\r1:" + pairs.drop(6).take(7).joinToString("") +
                "\r2:" + pairs.drop(13).joinToString("")
        }
    }

    companion object {
        /** A made-up first-generation FR-S VIN: the real prefix, a zero serial. */
        const val FrsVin = "JF1ZNAA1XD1700000"

        /** Steering -30.0°, brake, pedal 20 % / 750 rpm, oil 77 °C / coolant 85 °C. */
        val Frames = mapOf(
            0x0D0 to "2C01000000000000",
            0x0D1 to "0000200000000000",
            0x140 to "3300EE0200001900",
            0x360 to "0000757D00000000",
        )
    }
}

/** Steering values with the real time they came at, connects and drops, and every log line. */
private class VlinkerHeard : ObdConnectorListener {
    val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    val ready = AtomicInteger()
    val ended: MutableList<String> = Collections.synchronizedList(ArrayList())
    val steeringAt: MutableList<Long> = Collections.synchronizedList(ArrayList())

    override fun ready(
        target: ObdTarget,
        adapter: String,
        protocol: String,
        voltage: String?,
        supported: Set<Int>,
        channels: List<PidChannel>,
    ) {
        ready.incrementAndGet()
    }

    override fun value(channel: PidChannel, value: Double, atMs: Long) {
        if (channel.id == "steering_angle") steeringAt += wallMs()
    }

    override fun ended(target: ObdTarget, reason: String) {
        ended += reason
    }

    override fun log(text: String) {
        logs += text
        timed += "+${wallMs() - started} ms $text"
    }

    /** Every log line with the time since the test began, for the failure messages. */
    private val started = wallMs()
    val timed: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** At least [connections] connections made, and steering in the last 200 ms. */
    fun live(connections: Int) = ready.get() >= connections && steeringAfter(wallMs() - 200) != null

    fun steeringAfter(ms: Long) = synchronized(steeringAt) { steeringAt.firstOrNull { it >= ms } }
}

private fun wallMs() = System.nanoTime() / 1_000_000

/** Real time and real waits; an interrupt ends a wait quietly, as the app's clock does. */
private object VlinkerClock : ObdClock {
    override fun nowMs() = System.nanoTime() / 1_000_000

    override fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // Only stopping interrupts.
        }
    }
}

/**
 * FBBD-271 before Moto Park (Arek 2026-11-03: "obd ma być jak skała na vlinkerze"): the vLinker MC+ on the
 * FR-S, CAN first through STN filters, as in the 2026-11-02 log, losing its link or going silent at the
 * worst moments, in real time against the shared connector both apps run. Each test says how long car data
 * may be missing; the probe at connect is cut to 300 ms so a reconnect takes about 3 s here (about 10 s in
 * the car, where the probe is 2 s and the BLE connect 1-2 s).
 */
class VlinkerFaultTest {
    private fun connector(target: ObdTarget, heard: VlinkerHeard) = ObdConnector(
        { listOf(target) },
        heard,
        ObdStatusTracker(),
        VlinkerClock,
        probeMs = 300,
    )

    /** Runs [c] on its own thread as the app does until [until] holds (at most [limitMs]), then stops it. */
    private fun runUntil(c: ObdConnector, heard: VlinkerHeard, limitMs: Long, until: () -> Boolean) {
        val running = AtomicBoolean(true)
        var escaped: Throwable? = null
        val thread = Thread({ c.run { running.get() } }, "obd-vlinker-test").apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, e -> escaped = e }
            start()
        }
        val deadline = wallMs() + limitMs
        while (!until() && wallMs() < deadline) Thread.sleep(10)
        running.set(false)
        c.stop()
        thread.interrupt()
        thread.join(5_000)
        assertFalse(thread.isAlive, "the OBD thread did not end")
        assertNull(escaped, "uncaught on the OBD thread: $escaped")
        assertTrue(heard.logs.none { it.startsWith("round failed") }, heard.logs.toString())
    }

    @Test
    fun `a link lost while listening to CAN is back with steering within 8 s`() {
        val target = VlinkerFrs(listOf(VlinkerFault(dropAfterMs = 4_000)))
        val heard = VlinkerHeard()
        val start = wallMs()
        runUntil(connector(target, heard), heard, 30_000) { heard.live(connections = 2) }
        val drop = start + 4_000
        val back = heard.steeringAfter(drop + 100)
        assertTrue(back != null, heard.logs.toString())
        assertTrue(back - drop <= 8_000, "steering back ${back - drop} ms after the drop: ${heard.logs}")
        assertEquals(1, heard.ended.count { it.contains("link lost") }, heard.ended.toString())
        assertTrue(heard.logs.any { it.startsWith("car data back") }, heard.logs.toString())
    }

    @Test
    fun `a vLinker that goes silent while listening to CAN is dropped and back with steering within 18 s`() {
        // The link stays up and the phone hears nothing: no GATT disconnect tells it the adapter is gone.
        val target = VlinkerFrs(listOf(VlinkerFault(hangAfterMs = 4_000)))
        val heard = VlinkerHeard()
        val start = wallMs()
        runUntil(connector(target, heard), heard, 60_000) { heard.live(connections = 2) }
        val hang = start + 4_000
        val back = heard.steeringAfter(hang + 100)
        assertTrue(back != null, "steering never came back: ${heard.logs}")
        assertTrue(
            back - hang <= 18_000,
            "steering back ${back - hang} ms after the adapter went silent: ${heard.timed}",
        )
        assertEquals(listOf("adapter stopped answering"), heard.ended.take(1), heard.logs.toString())
    }

    @Test
    fun `a vLinker that goes silent during setup is tried again at once`() {
        // Hangs 1.5 s after the connect: in the middle of the setup or the probe, before the car answered.
        val target = VlinkerFrs(listOf(VlinkerFault(hangAfterMs = 1_500)))
        val heard = VlinkerHeard()
        val start = wallMs()
        runUntil(connector(target, heard), heard, 60_000) { heard.live(connections = 1) }
        val first = heard.steeringAfter(start)
        val after = first?.minus(start)
        assertTrue(after != null && after <= 18_000, "steering $after ms after start: ${heard.timed}")
        assertEquals(2, target.opens.get(), heard.logs.toString())
    }

    @Test
    fun `an adapter away for a while after the drop is back within 6 s of returning`() {
        // Ignition cycled in the pit lane, or the adapter rebooted: gone for 6 s, then back.
        val target = VlinkerFrs(listOf(VlinkerFault(dropAfterMs = 4_000)))
        val heard = VlinkerHeard()
        val start = wallMs()
        val returned = AtomicBoolean(false)
        var returnedAt = 0L
        val c = connector(target, heard)
        runUntil(c, heard, 40_000) {
            val now = wallMs()
            if (now - start in 4_000..10_000) target.away.set(true)
            if (now - start > 10_000 && !returned.get()) {
                target.away.set(false)
                returned.set(true)
                returnedAt = now
            }
            returned.get() && heard.ready.get() >= 2 && heard.steeringAfter(now - 200) != null
        }
        val back = heard.steeringAfter(returnedAt)
        assertTrue(back != null, heard.logs.toString())
        // A retry every 2 s after a mid-drive loss, then setup and probe (about 2.5 s here).
        val late = back - returnedAt
        assertTrue(late <= 6_000, "steering back $late ms after the adapter returned: ${heard.logs}")
        assertTrue(target.connectTries.get() >= 3, "retried while away: ${target.connectTries.get()}")
    }

    @Test
    fun `ten drops and stalls in one session all come back, and car data keeps flowing between them`() {
        // A long session's worth of trouble at once: link losses and silent stalls, early and late in
        // each connection (setup, probe, listening, polling ATF).
        val faults = listOf(
            VlinkerFault(dropAfterMs = 3_000),
            VlinkerFault(hangAfterMs = 2_500),
            VlinkerFault(dropAfterMs = 600),
            VlinkerFault(dropAfterMs = 2_000),
            VlinkerFault(hangAfterMs = 4_000),
            VlinkerFault(dropAfterMs = 150),
            VlinkerFault(dropAfterMs = 5_000),
            VlinkerFault(hangAfterMs = 900),
            VlinkerFault(dropAfterMs = 2_500),
            VlinkerFault(dropAfterMs = 3_500),
        )
        val target = VlinkerFrs(faults)
        val heard = VlinkerHeard()
        runUntil(connector(target, heard), heard, 150_000) {
            target.opens.get() > faults.size && heard.steeringAfter(wallMs() - 200) != null
        }
        assertTrue(target.opens.get() > faults.size, "only ${target.opens.get()} opens: ${heard.logs}")
        assertTrue(heard.steeringAfter(wallMs() - 1_000) != null, "no steering at the end: ${heard.logs}")
        // Never stuck in the slow ladder for an adapter that keeps coming back.
        assertTrue(heard.logs.none { it.startsWith("still searching") }, heard.logs.toString())
    }
}

/** FBBD-271: what counts as a silent adapter on the line itself. */
class ElmLineSilenceTest {
    @Test
    fun `three commands without one byte end the line as an adapter that stopped answering`() {
        val line = ElmLine(write = {}, resyncMs = 50, silentLimit = 3)
        assertNull(line.command("ATRV", 20))
        assertNull(line.command("ATRV", 20))
        val e = kotlin.runCatching { line.command("ATRV", 20) }.exceptionOrNull()
        assertTrue(e is IOException && e.message == ElmLine.SILENT, "$e")
        // The line stays ended: the next command fails at once.
        assertTrue(kotlin.runCatching { line.command("ATI", 20) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `a slow adapter that sends anything is never taken as gone`() {
        // Each command times out, but the adapter prints part of an answer every time ("SEARCHING...").
        lateinit var line: ElmLine
        line = ElmLine(write = { line.feed("SEARCHING...\r".encodeToByteArray()) }, resyncMs = 20, silentLimit = 3)
        repeat(6) { assertNull(line.command("0100", 20)) }
    }

    @Test
    fun `an answer between silent waits starts the count again`() {
        var answer = false
        lateinit var line: ElmLine
        line = ElmLine(write = { if (answer) line.feed("OK\r\r>".encodeToByteArray()) }, resyncMs = 20, silentLimit = 3)
        repeat(2) { assertNull(line.command("ATRV", 20)) }
        answer = true
        assertEquals("OK", line.command("ATE0", 200)?.trim())
        answer = false
        repeat(2) { assertNull(line.command("ATRV", 20)) }
    }

    @Test
    fun `without a limit a silent line only times out, as before`() {
        val line = ElmLine(write = {}, resyncMs = 10)
        repeat(5) { assertNull(line.command("ATRV", 10)) }
    }
}
