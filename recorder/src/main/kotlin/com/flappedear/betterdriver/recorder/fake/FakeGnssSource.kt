@file:Suppress("MagicNumber") // Made-up track geometry.

package com.flappedear.betterdriver.recorder.fake

import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/** Events of [FakeGnssSource] while its receiver is switched off and on ([FakeGnssSource] receiverOn). */
object FakeGnssEvents {
    /** The receiver came back and fixes flow again. Text is "demo". */
    const val CONNECTED = "demo_gnss_connected"

    /** Fixes stopped because the receiver was switched off. Text is the reason. */
    const val DISCONNECTED = "demo_gnss_disconnected"

    /** The reason [DISCONNECTED] gives. */
    const val REASON_SWITCHED_OFF = "receiver switched off"
}

/**
 * Pushes [FakeGnss] fixes, or another [GnssScript]'s, into the sink in real time on its own thread.
 * While [receiverOn] is false the receiver is "switched off", as a RaceBox out of range: no fixes
 * are sent, fix numbers keep counting with time, and the drop and the return are logged as
 * [FakeGnssEvents], as the Bluetooth sources log theirs. Emulator tests use it to drop GNSS on
 * purpose (FBBD-43, FBBD-64).
 */
class FakeGnssSource(
    private val gnss: GnssScript = FakeGnss(),
    /** Read before each fix; false drops the fix. */
    private val receiverOn: () -> Boolean = { true },
    /** Called after each fix is handed to the sink, with its index. Used by the fault-injection test. */
    private val onEmitted: (Long) -> Unit = {},
) : DataSource {
    override val name = "fake-gnss"

    @Volatile
    private var running = false
    private var thread: Thread? = null

    override fun start(sink: SampleSink) {
        Channels.forEach(sink::defineChannel)
        running = true
        thread = Thread({
            val periodNanos = 1_000_000_000L / gnss.rateHz
            val t0 = System.nanoTime()
            var n = 0L
            var wasOn = true
            while (running) {
                val due = t0 + n * periodNanos
                val wait = due - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                if (!running) break
                val on = receiverOn()
                if (on != wasOn) logLink(sink, on)
                wasOn = on
                if (on) {
                    sink.submit(SampleSet(sink.nowMicros(), gnss.fix(n)))
                    onEmitted(n)
                }
                n++
            }
        }, "fake-gnss").apply {
            isDaemon = true
            start()
        }
    }

    private fun logLink(sink: SampleSink, on: Boolean) = if (on) {
        sink.event(FakeGnssEvents.CONNECTED, "demo")
    } else {
        sink.event(FakeGnssEvents.DISCONNECTED, FakeGnssEvents.REASON_SWITCHED_OFF)
    }

    override fun stop() {
        running = false
        thread?.join(1_000)
    }

    companion object {
        /** The GNSS channels, marked as made up (FBBD-174): Demo GNSS never measures anything. */
        val Channels = SimulatedGnssChannels
    }
}
