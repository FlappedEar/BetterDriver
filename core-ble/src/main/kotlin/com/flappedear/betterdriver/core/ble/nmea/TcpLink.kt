package com.flappedear.betterdriver.core.ble.nmea

import com.flappedear.betterdriver.core.ble.LinkState
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import javax.net.SocketFactory

/** A Wi-Fi connect to a device on the local network answers or is refused well within this. */
private const val ConnectTimeoutMs = 5_000
private const val MaxPort = 65_535

/**
 * Keeps a TCP connection to an NMEA 0183 or RaceChrono DIY ($RC2/$RC3) stream over Wi-Fi
 * (FBBD-115): connect to [host]:[port], read on a dedicated thread, and reconnect with the same
 * back-off and silence watchdog as [SppLink] (see [StreamLink]). The device is the server; this end
 * only reads. RaceChrono offers the same "TCP/IP (Wi-Fi)" connection for its DIY devices
 * (https://racechrono.com/article/2572) and for Wi-Fi GPS receivers; neither has a standard port,
 * so the user enters it.
 *
 * [socketFactory] defaults to the system one. A device that runs its own access point has no
 * internet, and Android may then route sockets over mobile data; the app can pass the Wi-Fi
 * network's factory (android.net.Network.getSocketFactory) to keep the connection on Wi-Fi.
 * Needs the INTERNET permission, which the app has.
 */
class TcpLink(
    private val host: String,
    private val port: Int,
    onData: (ByteArray) -> Unit,
    onState: (LinkState) -> Unit = {},
    timing: StreamTiming = StreamTiming(),
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) : StreamLink("nmea-tcp-link", onData, onState, timing) {
    init {
        require(port in 1..MaxPort) { "port out of range: $port" }
    }

    override fun open(): InputStream {
        val socket = attach(socketFactory.createSocket())
        try {
            socket.tcpNoDelay = true
            // Resolves the host here, on the reader thread; an unknown host fails like a refused connect.
            socket.connect(InetSocketAddress(host, port), ConnectTimeoutMs)
        } catch (e: IOException) {
            closeQuietly(socket)
            throw LinkFailure("connect to $host:$port failed: ${e.message}", e)
        }
        return socket.getInputStream()
    }
}
