package com.flappedear.betterdriver.core.ble.lns

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

/** Receivers notify at 1 to 10 Hz, so the default 5 s without a value means the link is gone. */
private val LnsSpec = GattNotifySpec(LnsGatt.SERVICE, LnsGatt.LOCATION_AND_SPEED, "lns-link")

/** BLE GNSS receivers in range that advertise the Location and Navigation Service, strongest first. */
fun scanLnsDevices(context: Context): Flow<List<FoundBleDevice>> = scanBleDevices(context, ::isLnsAdvertisement)

/**
 * A Bluetooth LE GNSS receiver with the standard Location and Navigation Service as a recorder
 * [DataSource] (FBBD-114). The recorder service attaches it to a recording; it connects to the
 * device at [address], subscribes to Location and Speed, keeps reconnecting until stopped, and
 * pushes one sample set per notification. Connects and drops are logged as session events, like
 * the RaceBox's and the NMEA receivers'.
 *
 * [deviceName] is the advertised name saved at pairing, logged so a session says which receiver
 * it came from.
 */
class LnsSource(
    private val context: Context,
    private val address: String,
    private val deviceName: String,
) : DataSource {
    override val name = "lns"

    @Volatile
    private var link: GattNotifyLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        val decoder = LnsDecoder(sink)
        decoder.defineChannels()
        sink.event(LnsEvents.DEVICE, "address=$address name=$deviceName")
        // onState runs on the link's one thread, so this needs no lock.
        var wasStreaming = false
        link = GattNotifyLink(context, address, LnsSpec, onData = decoder::feed, onState = { s ->
            val streaming = s is LinkState.Streaming
            if (streaming && !wasStreaming) sink.event(LnsEvents.CONNECTED, address)
            if (s is LinkState.Reconnecting) {
                if (wasStreaming) sink.event(LnsEvents.DISCONNECTED, s.reason)
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
