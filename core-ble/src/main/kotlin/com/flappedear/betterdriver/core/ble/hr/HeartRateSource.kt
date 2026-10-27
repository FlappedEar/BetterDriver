package com.flappedear.betterdriver.core.ble.hr

import android.content.Context
import com.flappedear.betterdriver.core.ble.FoundBleDevice
import com.flappedear.betterdriver.core.ble.GattNotifyLink
import com.flappedear.betterdriver.core.ble.GattNotifySpec
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.NotifyLink
import com.flappedear.betterdriver.core.ble.logLink
import com.flappedear.betterdriver.core.ble.scanBleDevices
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Monitors notify about once a second, so the default 5 s without a value means the link is gone. */
private val HeartRateSpec = GattNotifySpec(HeartRateGatt.SERVICE, HeartRateGatt.MEASUREMENT, "hr-link")

/** Heart rate monitors in range that advertise the Heart Rate Service, strongest first. */
fun scanHeartRateMonitors(context: Context): Flow<List<FoundBleDevice>> =
    scanBleDevices(context) { _, uuids -> isHeartRateAdvertisement(uuids) }

/**
 * A Bluetooth LE heart rate monitor (Heart Rate Service) as a recorder [DataSource] (FBBD-119).
 * The recorder service attaches it to a recording; it connects to the monitor at [address],
 * subscribes to Heart Rate Measurement, keeps reconnecting until stopped, and logs the driver's
 * heart rate and, when the monitor sends them, RR-Intervals and energy ([HeartRateChannels]).
 * Connects and drops are logged as session events. With no plausible heart rate in the first
 * [StartWindowMicros] of the session the monitor is switched off for the rest of it: a strap that
 * wakes later while not worn (lying on a cable) sends noise, not a heart rate (FBBD-285). What it
 * does to the recording is [HeartRateSession], shared with the iOS app (FBBD-333).
 *
 * [deviceName] is the advertised name saved at pairing, logged so a session says which monitor
 * it came from. [connect] makes the link from its two callbacks; tests pass a simulated monitor.
 */
class HeartRateSource internal constructor(
    private val address: String,
    private val deviceName: String,
    private val connect: (onData: (ByteArray) -> Unit, onState: (LinkState) -> Unit) -> NotifyLink,
) : DataSource {
    constructor(context: Context, address: String, deviceName: String) : this(
        address,
        deviceName,
        { onData, onState -> GattNotifyLink(context, address, HeartRateSpec, onData, onState) },
    )

    override val name = "heart_rate"

    @Volatile
    private var link: NotifyLink? = null

    @Volatile
    private var session: HeartRateSession? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    /** The monitor's state for the status strip, or null when stopped or switched off (no link). */
    fun status(): HeartRateStatus? = if (link == null) null else session?.status()

    override fun start(sink: SampleSink) {
        val session = HeartRateSession(sink, address, deviceName) {
            link?.stop()
            link = null
            if (current === this) current = null
        }
        this.session = session
        session.start()
        // onState runs on the link's one thread, so this needs no lock.
        var wasStreaming = false
        link = connect(session::feed) { s ->
            if (session.offIfNotFound()) return@connect
            logLink(address, s, wasStreaming)
            when (s) {
                is LinkState.Streaming -> session.streaming()
                is LinkState.Reconnecting -> session.reconnecting(s.reason)
                else -> session.connecting()
            }
            if (s is LinkState.Streaming || s is LinkState.Reconnecting) wasStreaming = s is LinkState.Streaming
        }
        current = this
        link?.start()
    }

    override fun stop() {
        link?.stop()
        link = null
        if (current === this) current = null
    }

    companion object {
        /** The monitor of the session recording in this process, if it records heart rate. */
        @Volatile
        var current: HeartRateSource? = null
            private set
    }
}
