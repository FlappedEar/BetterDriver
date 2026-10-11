package com.flappedear.betterdriver.core.ble.nmea

import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.linkEvents
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import javax.net.SocketFactory
import kotlinx.coroutines.flow.StateFlow

/**
 * An NMEA 0183 or RaceChrono DIY ($RC2/$RC3) stream over Wi-Fi TCP as a recorder [DataSource]
 * (FBBD-115): a Wi-Fi GPS receiver, or a DIY device that RaceChrono would connect to as "RaceChrono
 * DIY" over TCP/IP (https://racechrono.com/article/2572). Same decoding, channels and events as
 * [NmeaSource]; the device text is "address=<host>:<port> name=<deviceName>". [socketFactory] is
 * passed to [TcpLink], so the app can keep the socket on Wi-Fi.
 */
class TcpNmeaSource(
    private val host: String,
    private val port: Int,
    private val deviceName: String,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) : DataSource {
    override val name = "nmea_tcp"

    @Volatile
    private var link: TcpLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        val decoder = NmeaDecoder(sink)
        decoder.defineChannels()
        val address = "$host:$port"
        sink.event(NmeaEvents.DEVICE, "address=$address name=$deviceName")
        val onState = linkEvents(sink, address, NmeaEvents.CONNECTED, NmeaEvents.DISCONNECTED, decoder::reset)
        link = TcpLink(host, port, decoder::feed, onState, socketFactory = socketFactory).also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
