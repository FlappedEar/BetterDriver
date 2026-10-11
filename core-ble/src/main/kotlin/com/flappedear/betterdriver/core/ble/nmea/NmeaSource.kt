package com.flappedear.betterdriver.core.ble.nmea

import android.content.Context
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.coroutines.flow.StateFlow

/**
 * A Bluetooth NMEA 0183 receiver (Qstarz, Garmin GLO, XGPS, ...) as a recorder [DataSource]
 * (FBBD-108). The recorder service attaches it to a recording; it connects over SPP to the paired
 * receiver at [address], keeps reconnecting until stopped, and pushes one sample set per fix.
 * Connects and drops are logged as session events, like the RaceBox's.
 *
 * [deviceName] is the name from Android's paired devices, logged so a session says which
 * receiver it came from.
 */
class NmeaSource(
    private val context: Context,
    private val address: String,
    private val deviceName: String,
) : DataSource {
    override val name = "nmea"

    @Volatile
    private var link: SppLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        val decoder = NmeaDecoder(sink)
        decoder.defineChannels()
        sink.event(NmeaEvents.DEVICE, "address=$address name=$deviceName")
        // onState calls never overlap (SppLink), so this needs no lock.
        var wasStreaming = false
        link = SppLink(context, address, onData = decoder::feed, onState = { s ->
            val streaming = s is LinkState.Streaming
            if (streaming && !wasStreaming) sink.event(NmeaEvents.CONNECTED, address)
            if (s is LinkState.Reconnecting) {
                if (wasStreaming) sink.event(NmeaEvents.DISCONNECTED, s.reason)
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
