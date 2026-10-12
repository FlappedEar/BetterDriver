package com.flappedear.betterdriver.core.ble.nmea

import com.flappedear.betterdriver.core.ble.LinkState
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** A Wi-Fi connect to a device on the local network answers or is refused well within this. */
private const val ConnectTimeoutMs = 5_000
private const val MaxPort = 65_535

/** The network a [TcpLink] connects through. */
interface TcpNetwork {
    /** A socket that is not connected yet. */
    fun createSocket(): Socket

    /** Looks up [host] with this network's DNS; throws UnknownHostException. */
    fun resolve(host: String): InetAddress

    /** The system's default network. */
    object Default : TcpNetwork {
        override fun createSocket(): Socket = SocketFactory.getDefault().createSocket()

        override fun resolve(host: String): InetAddress = InetAddress.getByName(host)
    }
}

/**
 * Keeps a TCP connection to an NMEA 0183 or RaceChrono DIY ($RC2/$RC3) stream over Wi-Fi
 * (FBBD-115): connect to [host]:[port], read on a dedicated thread, and reconnect with the same
 * back-off and silence watchdog as [SppLink] (see [StreamLink]). The device is the server; this end
 * only reads. RaceChrono offers the same "TCP/IP (Wi-Fi)" connection for its DIY devices
 * (https://racechrono.com/article/2572) and for Wi-Fi GPS receivers; neither has a standard port,
 * so the user enters it.
 *
 * [network] is asked for the network to connect through before every connect, and defaults to the
 * system's default network. A device that runs its own access point has no internet, and Android may
 * then route sockets over mobile data; the app can pass the Wi-Fi network (android.net.Network) to
 * keep the connection on Wi-Fi. Asking again each time matters: after Wi-Fi drops and comes back
 * Android may give it a new Network, and the old one's sockets no longer work.
 * Needs the INTERNET permission, which the app has.
 */
class TcpLink(
    private val host: String,
    private val port: Int,
    onData: (ByteArray) -> Unit,
    onState: (LinkState) -> Unit = {},
    timing: StreamTiming = StreamTiming(),
    private val network: () -> TcpNetwork = { TcpNetwork.Default },
) : StreamLink("nmea-tcp-link", onData, onState, timing) {
    init {
        require(port in 1..MaxPort) { "port out of range: $port" }
    }

    override fun open(): InputStream {
        val net = network()
        val socket = attach(net.createSocket())
        try {
            socket.tcpNoDelay = true
            // Resolves the host here, on the reader thread and through the same network as the socket
            // (the receiver's access point may be the only one that knows its name); an unknown host
            // fails like a refused connect.
            socket.connect(InetSocketAddress(net.resolve(host), port), ConnectTimeoutMs)
        } catch (e: IOException) {
            closeQuietly(socket)
            throw LinkFailure("connect to $host:$port failed: ${e.message}", e)
        }
        return socket.getInputStream()
    }
}
