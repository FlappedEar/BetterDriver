package com.flappedear.betterdriver.core.ble.nmea

import android.content.Context
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.linkEvents
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.coroutines.flow.StateFlow

/**
 * A Bluetooth NMEA 0183 receiver (Qstarz, Garmin GLO, XGPS, ...) as a recorder [DataSource]
 * (FBBD-108). The recorder service attaches it to a recording; it connects over SPP to the paired
 * receiver at [address], keeps reconnecting until stopped, and pushes one sample set per fix.
 * RaceChrono DIY $RC2/$RC3 lines in the stream are recorded too (FBBD-115, [RcLine]).
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
        val onState = linkEvents(sink, address, NmeaEvents.CONNECTED, NmeaEvents.DISCONNECTED, decoder::reset)
        link = SppLink(context, address, onData = decoder::feed, onState = onState).also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
