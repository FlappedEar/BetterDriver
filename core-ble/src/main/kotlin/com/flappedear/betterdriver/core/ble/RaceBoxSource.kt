package com.flappedear.betterdriver.core.ble

import android.content.Context
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.coroutines.flow.StateFlow

/**
 * The RaceBox as a recorder [DataSource]. The recorder service attaches it to a recording; it
 * connects to the paired RaceBox at [address], keeps reconnecting until stopped, and pushes one
 * sample set per RaceBox Data Message. Connects and drops are logged as session events.
 *
 * [deviceName] is the advertised name saved at pairing; it tells a Micro (input voltage) from a Mini
 * (battery level).
 */
class RaceBoxSource(
    private val context: Context,
    private val address: String,
    deviceName: String,
) : DataSource {
    override val name = "racebox"
    private val isMicro = deviceName.startsWith(RaceBoxGatt.MICRO_PREFIX)

    @Volatile
    private var link: RaceBoxLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        val decoder = RaceBoxDecoder(sink, isMicro)
        decoder.defineChannels()
        var wasStreaming = false
        link = RaceBoxLink(context, address, onData = decoder::feed) { s ->
            val streaming = s is LinkState.Streaming
            if (streaming && !wasStreaming) sink.event(RaceBoxEvents.CONNECTED, address)
            if (s is LinkState.Reconnecting) {
                if (wasStreaming) sink.event(RaceBoxEvents.DISCONNECTED, s.reason)
                decoder.reset()
            }
            if (streaming || s is LinkState.Reconnecting) wasStreaming = streaming
        }.also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
