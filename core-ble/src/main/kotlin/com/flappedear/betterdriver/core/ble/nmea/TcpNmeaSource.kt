package com.flappedear.betterdriver.core.ble.nmea

import com.flappedear.betterdriver.core.ble.GnssReceiverSession
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.coroutines.flow.StateFlow

/**
 * An NMEA 0183 or RaceChrono DIY ($RC2/$RC3) stream over Wi-Fi TCP as a recorder [DataSource]
 * (FBBD-115): a Wi-Fi GPS receiver, or a DIY device that RaceChrono would connect to as "RaceChrono
 * DIY" over TCP/IP (https://racechrono.com/article/2572). Same decoding, channels and events as
 * [NmeaSource]; the device text is "address=<host>:<port> name=<deviceName>". [network] is
 * passed to [TcpLink], so the app can keep the socket on Wi-Fi.
 */
class TcpNmeaSource(
    private val host: String,
    private val port: Int,
    private val deviceName: String,
    private val network: () -> TcpNetwork = { TcpNetwork.Default },
) : DataSource {
    override val name = "nmea_tcp"

    @Volatile
    private var link: TcpLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        // The same session as the iPhone's Wi-Fi GPS (FBBD-374).
        val session = GnssReceiverSession.nmea(sink, "$host:$port", deviceName)
        session.start()
        link = TcpLink(host, port, session::feed, session::linkChanged, network = network).also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
