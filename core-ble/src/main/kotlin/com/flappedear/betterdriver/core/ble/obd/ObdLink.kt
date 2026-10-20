package com.flappedear.betterdriver.core.ble.obd

import android.os.SystemClock
import com.flappedear.betterdriver.obd.CarProfile
import com.flappedear.betterdriver.obd.ObdClock
import com.flappedear.betterdriver.obd.ObdConnector
import com.flappedear.betterdriver.obd.ObdConnectorListener
import com.flappedear.betterdriver.obd.ObdStatus
import com.flappedear.betterdriver.obd.ObdStatusTracker
import com.flappedear.betterdriver.obd.ObdTarget
import com.flappedear.betterdriver.obd.PidChannel
import com.flappedear.betterdriver.recorder.log.DebugLog

/** What an [ObdLink] reports, on its own threads. */
interface ObdLinkListener {
    /** The adapter [target] set up and the car answered: [channels] are polled from now. */
    fun ready(target: ObdTarget, adapter: String, protocol: String, supported: Set<Int>, channels: List<PidChannel>) {}

    /** [channel] read [value] at [atMs] on SystemClock.elapsedRealtime(). */
    fun value(channel: PidChannel, value: Double, atMs: Long) {}

    /** A connection to [target] that opened has ended, with [reason]. */
    fun ended(target: ObdTarget, reason: String) {}
}

private const val Tag = "Obd"

/**
 * A connection to an OBD-II adapter that keeps itself up (FBBD-15, FBBD-121): while started an
 * [ObdConnector] tries [targets] in order (paired Bluetooth adapters, then the Wi-Fi one), then
 * [fallback] (a Bluetooth LE scan), runs an ElmPoller on the first that answers and reconnects
 * when it ends. It runs on its own thread, so a lost adapter or a car switched off never touches
 * its owner. [status] is the connection as the OBD page shows it (FBBD-261). Used by the recorder
 * ([ObdSource]) and, outside a session, by the OBD page; an adapter takes one connection at a time,
 * so only one runs at once. Log lines carry the tag "Obd" and the [owner].
 */
class ObdLink(
    private val targets: () -> List<ObdTarget>,
    private val listener: ObdLinkListener = object : ObdLinkListener {},
    private val profile: CarProfile = CarProfile.Generic,
    private val owner: String = "recorder",
    private val fallback: () -> List<ObdTarget> = { emptyList() },
) {
    private val tracker = ObdStatusTracker()

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var connector: ObdConnector? = null

    fun status(): ObdStatus = tracker.snapshot(SystemClock.elapsedRealtime())

    fun start() {
        running = true
        val c = ObdConnector(targets, Relay(), tracker, clock, profile, fallback)
        connector = c
        thread = Thread({
            c.run { running }
            DebugLog.i(Tag, "$owner: reader stopped")
        }, "obd-$owner").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        connector?.stop()
        thread?.interrupt()
        thread = null
    }

    private val clock = object : ObdClock {
        override fun nowMs() = SystemClock.elapsedRealtime()

        override fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                // Only stop() interrupts, and it has cleared [running].
            }
        }
    }

    private inner class Relay : ObdConnectorListener {
        override fun ready(
            target: ObdTarget,
            adapter: String,
            protocol: String,
            voltage: String?,
            supported: Set<Int>,
            channels: List<PidChannel>,
        ) = listener.ready(target, adapter, protocol, supported, channels)

        override fun value(channel: PidChannel, value: Double, atMs: Long) = listener.value(channel, value, atMs)

        override fun ended(target: ObdTarget, reason: String) = listener.ended(target, reason)

        override fun log(text: String) = DebugLog.i(Tag, "$owner: $text")
    }
}
