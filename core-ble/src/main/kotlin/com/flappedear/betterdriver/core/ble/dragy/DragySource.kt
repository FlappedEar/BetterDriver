package com.flappedear.betterdriver.core.ble.dragy

import android.content.Context
import com.flappedear.betterdriver.core.ble.GattLink
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.logLink
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.coroutines.flow.StateFlow

/**
 * A Dragy Pro / DRG70 as a recorder [DataSource] (FBBD-117). It connects over BLE to the Dragy at
 * [address] with the same link as the RaceBox ([GattLink] with [DragyGatt.PROFILE]: subscribe to
 * `FD02`, then answer the `FD03` challenge), keeps reconnecting until stopped, and pushes one sample
 * set per UBX-NAV-PVT (25 Hz per RaceChrono). Connects and drops are logged as session events.
 *
 * **Unverified on hardware.** The protocol is community reverse engineering of a Dragy Pro
 * (github.com/jremick/dragy-dash), not documented by Dragy; the Dragy Pro's IMU is not read.
 * [deviceName] is the advertised name saved at pairing, logged so a session says which unit it was.
 */
class DragySource(
    private val context: Context,
    private val address: String,
    private val deviceName: String,
) : DataSource {
    override val name = "dragy"

    @Volatile
    private var link: GattLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        val decoder = DragyDecoder(sink)
        decoder.defineChannels()
        sink.event(DragyEvents.DEVICE, "address=$address name=$deviceName")
        var wasStreaming = false
        link = GattLink(
            context,
            address,
            DragyGatt.PROFILE,
            onData = { _, bytes -> decoder.feed(bytes) },
            onState = { s ->
                logLink(address, s, wasStreaming)
                val streaming = s is LinkState.Streaming
                if (streaming && !wasStreaming) sink.event(DragyEvents.CONNECTED, address)
                if (s is LinkState.Reconnecting) {
                    if (wasStreaming) sink.event(DragyEvents.DISCONNECTED, s.reason)
                    decoder.reset()
                }
                if (streaming || s is LinkState.Reconnecting) wasStreaming = streaming
            },
        ).also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
