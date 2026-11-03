@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.obd

import com.flappedear.betterdriver.obd.BleSerialConnection
import com.flappedear.betterdriver.obd.BleSerialPort
import com.flappedear.betterdriver.obd.ObdBytes
import com.flappedear.betterdriver.obd.ObdConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private const val Ok = 0
private const val Connected = 2
private const val Disconnected = 0

/**
 * One Bluetooth LE OBD setup ([ObdGattSetup], FBBD-271) against a simulated adapter: the GATT
 * callbacks are called as Android calls them, in the orders a drive log showed or a stack can make.
 */
class ObdGattSetupTest {
    /** A simulated vLinker: counts the setup's GATT calls; [onDiscover] answers the lookup. */
    private class Adapter : ObdGattCalls {
        val mtuAsked = AtomicInteger()
        val discovered = AtomicInteger()
        val subscribed = AtomicInteger()
        val lines = AtomicInteger()
        var mtuAccepted = true
        var discoverAccepted = true
        var subscribeFailure: String? = null
        var onDiscover: () -> Unit = {}
        var beforeLine: () -> Unit = {}
        val port = Port()
        val bytes = Bytes()
        var lastPayload = 0

        override fun requestMtu(): Boolean {
            mtuAsked.incrementAndGet()
            return mtuAccepted
        }

        override fun discoverServices(): Boolean {
            discovered.incrementAndGet()
            if (discoverAccepted) onDiscover()
            return discoverAccepted
        }

        override fun subscribe(): String? {
            subscribed.incrementAndGet()
            return subscribeFailure
        }

        override fun line(payload: Int): BleSerialConnection {
            beforeLine()
            lines.incrementAndGet()
            lastPayload = payload
            return BleSerialConnection(port, bytes, payload)
        }
    }

    private class Port : BleSerialPort {
        val disconnects = AtomicInteger()

        override fun write(chunk: ByteArray) = true

        override fun disconnect() {
            disconnects.incrementAndGet()
        }
    }

    private class Bytes : ObdBytes {
        @Volatile
        var closed: String? = null

        override fun received(bytes: ByteArray) = Unit

        override fun closed(reason: String) {
            closed = reason
        }
    }

    @Test
    fun `a normal setup makes one line at the MTU's payload`() {
        val a = Adapter()
        val s = ObdGattSetup(a)
        s.connectionState(Ok, Connected)
        s.mtuChanged(247, ok = true)
        s.servicesDiscovered(Ok)
        s.descriptorWritten(Ok)
        val line = s.await(1_000L)
        assertNotNull(line)
        assertEquals(244, a.lastPayload)
        val calls = listOf(a.mtuAsked.get(), a.discovered.get(), a.subscribed.get(), a.lines.get())
        assertEquals(listOf(1, 1, 1, 1), calls)
        assertNull(s.failure)
    }

    @Test
    fun `an MTU that never answers, the services are looked up after the MTU wait, not the 15 s timeout`() {
        val a = Adapter()
        val s = ObdGattSetup(a, mtuAnswerMs = 100L)
        // The lookup answers on a binder thread, as Android does.
        a.onDiscover = {
            thread {
                s.servicesDiscovered(Ok)
                s.descriptorWritten(Ok)
            }
        }
        s.connectionState(Ok, Connected)
        val began = System.nanoTime()
        val line = s.await(15_000L)
        val tookMs = (System.nanoTime() - began) / 1_000_000L
        assertNotNull(line)
        assertTrue("took $tookMs ms", tookMs < 2_000L)
        // Without the MTU answer the line keeps the default 23-byte MTU.
        assertEquals(BleSerialConnection.DEFAULT_PAYLOAD, a.lastPayload)
    }

    @Test
    fun `a late MTU answer and repeated callbacks start no second lookup and never replace the line`() {
        val a = Adapter()
        val s = ObdGattSetup(a, mtuAnswerMs = 50L)
        s.connectionState(Ok, Connected)
        // The MTU wait runs out in await; the lookup starts without it.
        val waiter = thread { s.await(200L) }
        waiter.join()
        assertEquals(1, a.discovered.get())
        // Then the MTU answer comes after all, and Android 14's own exchange once more.
        s.mtuChanged(517, ok = true)
        s.mtuChanged(517, ok = true)
        s.connectionState(Ok, Connected)
        assertEquals(1, a.discovered.get())
        assertEquals(1, a.mtuAsked.get())
        s.servicesDiscovered(Ok)
        s.servicesDiscovered(Ok)
        assertEquals(1, a.subscribed.get())
        s.descriptorWritten(Ok)
        val first = s.connection
        s.descriptorWritten(Ok)
        assertSame(first, s.connection)
        assertEquals(1, a.lines.get())
        assertEquals(BleSerialConnection.DEFAULT_PAYLOAD, a.lastPayload)
    }

    @Test
    fun `GATT 133 before connecting fails at once and may be retried quickly`() {
        val a = Adapter()
        val s = ObdGattSetup(a)
        thread {
            Thread.sleep(30L)
            s.connectionState(133, Disconnected)
        }
        val began = System.nanoTime()
        assertNull(s.await(15_000L))
        assertTrue((System.nanoTime() - began) / 1_000_000L < 2_000L)
        assertEquals(ObdSetupFailure("connection state 0, status 133", quick = true), s.failure)
        assertEquals(0, a.mtuAsked.get())
    }

    @Test
    fun `a drop with status 147 during the service lookup fails the setup at once`() {
        val a = Adapter()
        val s = ObdGattSetup(a)
        s.connectionState(Ok, Connected)
        s.mtuChanged(247, ok = true)
        s.connectionState(147, Disconnected)
        assertNull(s.await(15_000L))
        assertEquals(ObdSetupFailure("connection state 0, status 147", quick = true), s.failure)
        // The lookup's answer after the drop does nothing more.
        s.servicesDiscovered(129)
        assertEquals(ObdSetupFailure("connection state 0, status 147", quick = true), s.failure)
    }

    @Test
    fun `a refused MTU request goes straight to the lookup, a refused lookup fails quick`() {
        val a = Adapter()
        a.mtuAccepted = false
        a.discoverAccepted = false
        val s = ObdGattSetup(a)
        s.connectionState(Ok, Connected)
        assertEquals(1, a.discovered.get())
        assertEquals(ObdSetupFailure("discoverServices failed", quick = true), s.failure)
    }

    @Test
    fun `an adapter without the serial line is not retried at once`() {
        val a = Adapter()
        a.subscribeFailure = "no serial characteristic in []"
        val s = ObdGattSetup(a)
        s.connectionState(Ok, Connected)
        s.mtuChanged(247, ok = true)
        s.servicesDiscovered(Ok)
        assertNull(s.await(1_000L))
        assertEquals(ObdSetupFailure("no serial characteristic in []", quick = false), s.failure)
    }

    @Test
    fun `a failed notification descriptor write fails quick`() {
        val a = Adapter()
        val s = ObdGattSetup(a)
        s.connectionState(Ok, Connected)
        s.mtuChanged(247, ok = true)
        s.servicesDiscovered(Ok)
        s.descriptorWritten(133)
        assertNull(s.await(1_000L))
        assertEquals(ObdSetupFailure("enable notifications status 133", quick = true), s.failure)
        assertEquals(0, a.lines.get())
    }

    @Test
    fun `a setup that does not finish fails slow after its timeout`() {
        val s = ObdGattSetup(Adapter())
        s.connectionState(Ok, Connected)
        assertNull(s.await(1_000L))
        assertEquals(ObdSetupFailure("no serial line within 1 s", quick = false), s.failure)
    }

    @Test
    fun `a drop after the line is up ends the line with the reason`() {
        val a = Adapter()
        val s = ObdGattSetup(a)
        s.connectionState(Ok, Connected)
        s.mtuChanged(247, ok = true)
        s.servicesDiscovered(Ok)
        s.descriptorWritten(Ok)
        assertNotNull(s.await(1_000L))
        s.connectionState(8, Disconnected)
        assertEquals("connection state 0, status 8", a.bytes.closed)
        // A setup failure is only for a line that never came up.
        assertNull(s.failure)
    }

    @Test
    fun `a callback that threw ends the setup at once, not after 15 s`() {
        val a = Adapter()
        val s = ObdGattSetup(a)
        s.connectionState(Ok, Connected)
        // BleObdTarget's guard turns an exception in a callback into this.
        s.failed("MTU threw java.lang.SecurityException: Need BLUETOOTH_CONNECT")
        assertNull(s.await(15_000L))
        assertEquals(false, s.failure?.quick)
    }

    @Test
    fun `a line made after the setup was abandoned is closed, never handed out`() {
        val a = Adapter()
        val s = ObdGattSetup(a)
        s.connectionState(Ok, Connected)
        s.mtuChanged(247, ok = true)
        s.servicesDiscovered(Ok)
        // open() gives up (15 s) while the descriptor answer is making the line.
        a.beforeLine = { s.abandon() }
        s.descriptorWritten(Ok)
        assertNull(s.connection)
        assertEquals(1, a.lines.get())
        assertEquals(1, a.port.disconnects.get())
        // An answer after that makes nothing.
        s.descriptorWritten(Ok)
        assertEquals(1, a.lines.get())
    }

    @Test
    fun `stopping interrupts the wait`() {
        val s = ObdGattSetup(Adapter())
        var interrupted = false
        val t = thread {
            try {
                s.await(15_000L)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        Thread.sleep(50L)
        t.interrupt()
        t.join(2_000L)
        assertTrue(interrupted)
    }

    @Test
    fun `quick retries, a status failure twice after 0_5 s and 1_5 s within 15 s, never a slow one`() {
        val quick = ObdSetupFailure("connection state 0, status 133", quick = true)
        assertEquals(500L, ObdQuickRetry.delayMs(quick, retries = 0, elapsedMs = 300L))
        assertEquals(1_500L, ObdQuickRetry.delayMs(quick, retries = 1, elapsedMs = 2_000L))
        assertNull(ObdQuickRetry.delayMs(quick, retries = 2, elapsedMs = 4_000L))
        assertNull(ObdQuickRetry.delayMs(quick, retries = 0, elapsedMs = 15_000L))
        val slow = ObdSetupFailure("no serial line within 15 s", quick = false)
        assertNull(ObdQuickRetry.delayMs(slow, retries = 0, elapsedMs = 15_000L))
    }

    /**
     * The quick restart of 28 Oct 2026 18:40, as an open loop (attempt, quick retry, else the
     * connector's back-off): connectGatt answered 133, then 147, then connected; a connection takes
     * about 4 s from connectGatt to the line, as on fresh starts. Before FBBD-271's quick retry each
     * failure waited out the connector's 5 s, then 10 s; now the line is up 7.3 s after the first
     * connectGatt instead of 20.3 s.
     */
    @Test
    fun `the 18_40 quick restart, 133 then 147 then a line, in 7_3 s instead of 20_3 s`() {
        // Each attempt: how long until it ends, and whether with the line.
        val attempts = listOf(300L to false, 1_000L to false, 4_000L to true)

        fun timeToLine(quickRetry: Boolean): Long {
            var t = 0L
            var retries = 0
            var failures = 0
            var openStart = 0L
            for ((ms, line) in attempts) {
                t += ms
                if (line) return t
                val failure = ObdSetupFailure("status", quick = true)
                val quick = if (quickRetry) ObdQuickRetry.delayMs(failure, retries, t - openStart) else null
                if (quick != null) {
                    retries++
                    t += quick
                } else {
                    // open() threw: the connector waits its back-off, then opens again.
                    t += ObdConnector.RETRY_MS[failures++.coerceAtMost(ObdConnector.RETRY_MS.size - 1)]
                    retries = 0
                    openStart = t
                }
            }
            error("no line")
        }
        val before = timeToLine(quickRetry = false)
        val now = timeToLine(quickRetry = true)
        assertEquals(20_300L, before)
        assertEquals(7_300L, now)
    }
}
