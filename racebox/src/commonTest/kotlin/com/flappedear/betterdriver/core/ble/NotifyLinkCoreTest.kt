@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The notify link's life cycle shared by Android's GattNotifyLink and the iPhone's strap link
 * (FBBD-358), driven by a scripted device: a link that streams, one that goes silent, setups that
 * bring no data until the quiet wait, and a stop that cancels a pending reconnect.
 */
class NotifyLinkCoreTest {
    private class Device : NotifyLinkIo {
        var t = 0L
        val opened = mutableListOf<ConnectPlan>()
        val closed = mutableListOf<Boolean>()
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()

        override fun open(plan: ConnectPlan): String? {
            opened += plan
            return null
        }

        override fun close(setupFailed: Boolean) {
            closed += setupFailed
        }

        override fun later(delayMs: Long, action: () -> Unit) {
            pending += (t + delayMs) to action
        }

        /** Moves the clock by [ms], running what came due and ticking [core] once a second. */
        fun run(core: NotifyLinkCore, ms: Long, onSecond: () -> Unit = {}) {
            val end = t + ms
            while (t < end) {
                t += 1_000
                onSecond()
                val due = pending.filter { it.first <= t }
                pending.removeAll(due)
                due.forEach { it.second() }
                core.tick()
            }
        }
    }

    private fun link(device: Device, states: MutableList<LinkState>) =
        NotifyLinkCore("AA:BB", device, { device.t }, { states += it })

    @Test
    fun streamsAndRestartsAfterSilence() {
        val device = Device()
        val states = mutableListOf<LinkState>()
        val core = link(device, states)
        core.start()
        core.connected()
        core.reached(NotifyStep.FIRST_VALUE)
        device.run(core, 3_000) { core.data() }
        assertEquals(LinkState.Streaming(1), core.state)
        // Silent from now: restarted once the 5 s without data pass, and the setup did not fail.
        device.run(core, 7_000)
        assertTrue(states.any { it is LinkState.Reconnecting && it.reason.startsWith("no data for") })
        assertEquals(false, device.closed.first())
        assertEquals(0, core.failures)
    }

    @Test
    fun setupsWithoutDataNameTheStepThenWaitQuietly() {
        val device = Device()
        val states = mutableListOf<LinkState>()
        val core = link(device, states)
        core.start()
        core.connected()
        core.reached(NotifyStep.FIRST_VALUE)
        device.run(core, 16_000)
        val first = states.filterIsInstance<LinkState.Reconnecting>().first()
        assertTrue(first.reason.startsWith("no data after connecting: notifications on, no value sent"), first.reason)
        assertEquals(true, device.closed.first())
        // The strap answers each connection and never sends: after 6 setups one connection with no
        // limit, which it does not answer while nobody wears it.
        var answered = 1
        device.run(core, 600_000) {
            if (device.opened.size > answered && device.opened.size <= QuietAfterSetups) {
                answered = device.opened.size
                core.connected()
            }
        }
        assertEquals(QuietAfterSetups + 1, device.opened.size)
        assertEquals(Long.MAX_VALUE, device.opened.last().setupTimeoutMs)
        assertEquals(LinkState.Connecting, core.state)
        // A value at last: streaming, and the next drop starts from the first plan again.
        core.data()
        device.run(core, 1_000) { core.data() }
        assertTrue(core.state is LinkState.Streaming)
    }

    @Test
    fun stopCancelsTheReconnectAndCallbacksAfterItDoNothing() {
        val device = Device()
        val states = mutableListOf<LinkState>()
        val core = link(device, states)
        core.start()
        core.failed("connection state 0, status 133")
        core.stop()
        core.data()
        device.run(core, 10_000)
        assertEquals(1, device.opened.size)
        assertEquals(LinkState.Idle, core.state)
    }

    @Test
    fun aListenerThatStopsTheLinkOnConnectingOpensNothing() {
        val device = Device()
        lateinit var core: NotifyLinkCore
        core = NotifyLinkCore("AA:BB", device, { device.t }, { if (it == LinkState.Connecting) core.stop() })
        core.start()
        assertEquals(0, device.opened.size)
    }

    @Test
    fun aRaceBoxLinkNeverWaitsQuietly() {
        val device = Device()
        val core = NotifyLinkCore(
            "AA:BB", device, { device.t }, {}, 3_000, 10_000, 30_000, ReconnectPolicy(500, 3_000), Int.MAX_VALUE,
        )
        core.start()
        // A receiver that is off: setups go on, each with a time limit, with no quiet wait.
        device.run(core, 600_000)
        assertTrue(device.opened.size > QuietAfterSetups + 1)
        assertTrue(device.opened.all { it.setupTimeoutMs <= 30_000L })
    }
}
