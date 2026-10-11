package com.flappedear.betterdriver.core.ble.vbox

import android.content.Context
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.nmea.SppLink
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.coroutines.flow.StateFlow

/**
 * A RACELOGIC VBOX Sport as a recorder [DataSource] (FBBD-116). It connects over the Serial Port
 * Profile ([SppLink]) to the paired VBOX Sport at [address], keeps reconnecting until stopped, and
 * pushes one sample set per RACELOGIC Serial Stream message (10 Hz per RACELOGIC's development
 * portal). Connects and drops are logged as session events, like the NMEA receivers'.
 *
 * Unverified on hardware: SPP as the transport, and the byte order and CRC range of the stream
 * (see [VboxSportFormat]). If the stream does not decode, [VboxSportEvents.NO_VALID_MESSAGES] is
 * logged. A VBOX Sport updated to output NMEA instead can be used with the NMEA source.
 * [deviceName] is the paired name, logged so a session says which unit it came from.
 */
class VboxSportSource(
    private val context: Context,
    private val address: String,
    private val deviceName: String,
) : DataSource {
    override val name = "vbox_sport"

    @Volatile
    private var link: SppLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        val decoder = VboxSportDecoder(sink)
        decoder.defineChannels()
        sink.event(VboxSportEvents.DEVICE, "address=$address name=$deviceName")
        // onState calls never overlap (SppLink), so this needs no lock.
        var wasStreaming = false
        link = SppLink(
            context,
            address,
            onData = decoder::feed,
            onState = { s ->
                val streaming = s is LinkState.Streaming
                if (streaming && !wasStreaming) sink.event(VboxSportEvents.CONNECTED, address)
                if (s is LinkState.Reconnecting) {
                    if (wasStreaming) sink.event(VboxSportEvents.DISCONNECTED, s.reason)
                    decoder.reset()
                }
                if (streaming || s is LinkState.Reconnecting) wasStreaming = streaming
            },
            messageCount = decoder::messages,
        ).also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
