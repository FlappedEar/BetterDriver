package com.flappedear.betterdriver.core.ble.diy

import android.content.Context
import com.flappedear.betterdriver.core.ble.GattLink
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.linkEvents
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.coroutines.flow.StateFlow

/**
 * A RaceChrono DIY BLE GPS device (service 0x1FF8, https://github.com/aollin/racechrono-ble-diy-device)
 * as a recorder [DataSource] (FBBD-118). It connects over BLE to the device at [address] with a
 * [GattLink], subscribes to GPS main (and GPS time when it notifies), reads GPS time when the
 * decoder needs it, keeps reconnecting until stopped, and pushes one sample set per GPS main
 * value. The CAN-Bus characteristics of the same service are left alone until CAN logging
 * (Phase 5). Connects and drops are logged as session events.
 *
 * [deviceName] is the advertised name saved when it was added, logged so a session says which
 * device it came from.
 */
class DiyBleSource(
    private val context: Context,
    private val address: String,
    private val deviceName: String,
) : DataSource {
    override val name = "racechrono_diy"

    @Volatile
    private var link: GattLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        val decoder = DiyGpsDecoder(sink, requestTime = { link?.read(DiyGatt.GPS_TIME) })
        decoder.defineChannels()
        sink.event(DiyEvents.DEVICE, "address=$address name=$deviceName")
        val onState = linkEvents(sink, address, DiyEvents.CONNECTED, DiyEvents.DISCONNECTED, decoder::reset)
        link = GattLink(context, address, DiyGatt.PROFILE, onData = decoder::feed, onState = onState)
            .also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
