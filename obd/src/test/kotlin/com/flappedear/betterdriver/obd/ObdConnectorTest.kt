@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real time, but the retry waits are cut short so a reconnect takes milliseconds. */
private object FastClock : ObdClock {
    override fun nowMs() = System.nanoTime() / 1_000_000

    override fun sleep(ms: Long) = Thread.sleep(ms.coerceAtMost(20))
}

private class Seen : ObdConnectorListener {
    val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    val values: MutableList<Pair<String, Double>> = Collections.synchronizedList(ArrayList())
    val ready: MutableList<Pair<ObdTarget, String>> = Collections.synchronizedList(ArrayList())
    val ended: MutableList<Pair<ObdTarget, String>> = Collections.synchronizedList(ArrayList())

    override fun ready(
        target: ObdTarget,
        adapter: String,
        protocol: String,
        voltage: String?,
        supported: Set<Int>,
        channels: List<PidChannel>,
    ) {
        ready += target to adapter
    }

    override fun value(channel: PidChannel, value: Double, atMs: Long) {
        values += channel.id to value
    }

    override fun ended(target: ObdTarget, reason: String) {
        ended += target to reason
    }

    override fun log(text: String) {
        logs += text
    }
}

/**
 * FBBD-121: the whole OBD-II connection loop against simulated adapters over each transport: an
 * STN-based adapter over a Bluetooth Classic stream, a vLinker-like one over a Bluetooth LE serial
 * pair, and the order adapters are tried in. No hardware: each simulated adapter answers the real
 * ELM327 command set, cut into pieces the way each transport delivers it.
 */
class ObdConnectorTest {
    private fun connect(
        targets: () -> List<ObdTarget>,
        fallback: () -> List<ObdTarget> = { emptyList() },
        until: (Seen) -> Boolean,
    ): Pair<Seen, ObdStatus?> {
        val seen = Seen()
        val tracker = ObdStatusTracker()
        val connector = ObdConnector(targets, seen, tracker, FastClock, fallback = fallback, retryMs = listOf(10L))
        val deadline = System.nanoTime() + 15_000_000_000L
        // The page's view at the moment [until] is met, before stopping ends the connection.
        var status: ObdStatus? = null
        connector.run {
            val done = until(seen)
            if (done && status == null) status = tracker.snapshot(FastClock.nowMs())
            !done && System.nanoTime() < deadline
        }
        connector.stop()
        return seen to status
    }

    @Test
    fun `an STN adapter over Bluetooth Classic is set up, identified and polled`() {
        val elm = SimElm(SimElm.car(SimElm.Stn))
        val target = StreamSimTarget(elm, "OBDLink MX+ (Bluetooth)")
        val (seen, status) = connect({ listOf(target) }) { it.values.size >= 40 }
        assertEquals(listOf(target as ObdTarget to "ELM327 v1.4b"), seen.ready.toList())
        assertTrue(
            "identity ATI=ELM327 v1.4b AT@1=OBDLink MX+ STI=STN2255 v5.6.19 STDI=OBDLink MX+ r1.1" in seen.logs,
            seen.logs.toString(),
        )
        assertTrue("connected to OBDLink MX+ (Bluetooth) over Bluetooth" in seen.logs, seen.logs.toString())
        assertTrue(Pair("rpm", 1726.0) in seen.values)
        assertTrue(Pair("speed", 50.0) in seen.values)
        assertEquals(ObdLinkState.Connected, status?.state)
        assertEquals("ELM327 v1.4b · ISO 15765-4 CAN (11 bit, 500 kbaud) · 12.4V", status?.detail)
        // Identity is asked once per connection, after ATI and before the car.
        val cmds = elm.commands.toList()
        val ati = cmds.indexOf("ATI")
        assertEquals(listOf("ATI", "AT@1", "STI", "STDI", "ATRV"), cmds.subList(ati, ati + 5))
    }

    @Test
    fun `a vLinker-like adapter over Bluetooth LE works in 20-byte pieces`() {
        val elm = SimElm(SimElm.car(SimElm.Vlinker))
        val target = BleSimTarget(elm, "vLinker MC+ (Bluetooth LE)")
        val (seen, _) = connect({ listOf(target) }) { it.values.size >= 40 }
        assertEquals("ELM327 v2.3", seen.ready.single().second)
        // A plain ELM327 answers ? to the STN commands; the log shows it.
        assertTrue(
            "identity ATI=ELM327 v2.3 AT@1=OBDII to RS232 Interpreter STI=? STDI=?" in seen.logs,
            seen.logs.toString(),
        )
        assertTrue(Pair("rpm", 1726.0) in seen.values)
        assertTrue(target.writes.all { it <= 20 }, target.writes.toString())
        assertEquals(1, target.disconnects, "the GATT connection is released once")
    }

    @Test
    fun `a Bluetooth LE adapter with a small MTU gets commands in pieces`() {
        val elm = SimElm(SimElm.car(SimElm.Vlinker))
        val target = BleSimTarget(elm, "tiny (Bluetooth LE)", payload = 2)
        val (seen, _) = connect({ listOf(target) }) { it.values.size >= 10 }
        assertTrue(seen.values.isNotEmpty())
        assertTrue(target.writes.all { it <= 2 } && target.writes.size > elm.commands.size)
    }

    @Test
    fun `a paired Bluetooth adapter is used before the Wi-Fi one`() {
        val bt = StreamSimTarget(SimElm(SimElm.car(SimElm.Vlinker)), "vLinker MC+ (Bluetooth)")
        val wifi = StreamSimTarget(SimElm(SimElm.car(SimElm.Stn)), "192.168.0.10:35000", ObdTransport.Wifi)
        val (seen, _) = connect({ listOf(bt, wifi) }) { it.values.size >= 5 }
        assertEquals(bt, seen.ready.single().first)
        assertEquals(0, wifi.opens)
    }

    @Test
    fun `an adapter out of range falls through to the next, and the scan is not needed`() {
        val dead = DeadTarget("vLinker MC+ (Bluetooth)", ObdTransport.BluetoothClassic)
        val wifi = StreamSimTarget(SimElm(SimElm.car(SimElm.Stn)), "192.168.0.10:35000", ObdTransport.Wifi)
        var scans = 0
        val (seen, _) = connect({ listOf(dead, wifi) }, { scans++; emptyList() }) { it.values.size >= 5 }
        assertEquals(wifi, seen.ready.single().first)
        assertEquals(1, dead.tries)
        assertEquals(0, scans)
    }

    @Test
    fun `with nothing paired and no Wi-Fi the scan finds a Bluetooth LE adapter`() {
        val ble = BleSimTarget(SimElm(SimElm.car(SimElm.Vlinker)), "OBDII (Bluetooth LE)")
        var scans = 0
        val (seen, _) = connect({ emptyList() }, { scans++; listOf(ble) }) { it.values.size >= 5 }
        assertEquals(ble, seen.ready.single().first)
        assertEquals(1, scans)
    }

    @Test
    fun `no adapter at all is noted once and retried`() {
        var rounds = 0
        val (seen, status) = connect({ rounds++; emptyList() }) { rounds >= 5 }
        assertEquals(1, seen.logs.count { it.startsWith("no adapter:") }, seen.logs.toString())
        assertEquals(ObdLinkState.Searching, status?.state)
        assertEquals("Phone not on the adapter's Wi-Fi", status?.detail)
    }

    @Test
    fun `an adapter that drops the connection is connected again`() {
        val elm = SimElm(SimElm.car(SimElm.Stn))
        val target = StreamSimTarget(elm, "OBDLink MX+ (Bluetooth)", dropAfter = 30)
        val (seen, _) = connect({ listOf(target) }) { it.ready.size >= 2 }
        assertTrue(seen.ended.isNotEmpty(), seen.logs.toString())
        assertTrue(target.opens >= 2)
        assertEquals(2, seen.ready.size)
    }
}
