package com.flappedear.betterdriver.core.ble.hr

import android.content.Context
import com.flappedear.betterdriver.core.ble.FoundBleDevice
import com.flappedear.betterdriver.core.ble.GattNotifyLink
import com.flappedear.betterdriver.core.ble.GattNotifySpec
import com.flappedear.betterdriver.core.ble.LinkState
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
 * Connects and drops are logged as session events.
 *
 * [deviceName] is the advertised name saved at pairing, logged so a session says which monitor
 * it came from.
 */
class HeartRateSource(
    private val context: Context,
    private val address: String,
    private val deviceName: String,
) : DataSource {
    override val name = "heart_rate"

    @Volatile
    private var link: GattNotifyLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        val decoder = HeartRateDecoder(sink)
        decoder.defineChannels()
        sink.event(HeartRateEvents.DEVICE, "address=$address name=$deviceName")
        // onState runs on the link's one thread, so this needs no lock.
        var wasStreaming = false
        link = GattNotifyLink(context, address, HeartRateSpec, onData = decoder::feed, onState = { s ->
            val streaming = s is LinkState.Streaming
            if (streaming && !wasStreaming) sink.event(HeartRateEvents.CONNECTED, address)
            if (s is LinkState.Reconnecting) {
                if (wasStreaming) sink.event(HeartRateEvents.DISCONNECTED, s.reason)
                decoder.reset()
            }
            if (streaming || s is LinkState.Reconnecting) wasStreaming = streaming
        }).also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
