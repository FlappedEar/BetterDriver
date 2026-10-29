@file:OptIn(ExperimentalForeignApi::class)

package com.flappedear.betterdriver.obd

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.posix.AF_INET
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_IP
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.if_nametoindex
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror

/** Where cheap Wi-Fi ELM327 adapters listen, as the Android app's WifiObdAddress (FBBD-15). */
const val WifiObdHost = "192.168.0.10"
const val WifiObdPort = 35000

private const val WifiConnectTimeoutMs = 3_000
private const val ReadBufferBytes = 1_024

// Darwin's SO_NOSIGPIPE: a write to a socket the adapter closed returns EPIPE instead of ending the app.
private const val SoNoSigPipe = 0x1022
private const val TcpNoDelay = 1

// Darwin's IP_BOUND_IF, and the iPhone's Wi-Fi interface.
private const val IpBoundIf = 25
private const val WifiInterface = "en0"
private const val NanosPerMilli = 1_000_000L

/**
 * A Wi-Fi ELM327 on iOS (FBBD-333), the iOS side of the Android app's WifiObdTarget: a TCP socket to
 * [host]:[port], given [WifiObdConnectTimeoutMs] to connect. The phone joins the adapter's Wi-Fi
 * network in iOS Settings; iOS asks once for local network access.
 */
class IosWifiObdTarget(
    val host: String = WifiObdHost,
    val port: Int = WifiObdPort,
    override val name: String = "Wi-Fi adapter",
) : ObdTarget {
    override val where = "$host:$port"
    override val transport = ObdTransport.Wifi

    override fun open(bytes: ObdBytes): ObdConnection = SocketObdConnection(connectTcp(host, port), bytes)
}

/** How long [IosWifiObdTarget] waits for the adapter to accept, as on Android. */
const val WifiObdConnectTimeoutMs = WifiConnectTimeoutMs

private fun lastError(): String = errorText(errno)

/** [port] in network byte order (the phone is little-endian). */
@Suppress("MagicNumber")
private fun networkOrder(port: Int): UShort = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()

/** [host] as dotted IPv4 in network byte order, or null when it is not one. */
@Suppress("MagicNumber")
internal fun ipv4(host: String): UInt? {
    val parts = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
    return if (parts.size != 4 || host.count { it == '.' } != 3) {
        null
    } else {
        parts.foldIndexed(0u) { i, acc, b -> acc or (b.toUInt() shl (8 * i)) }
    }
}

private fun option(fd: Int, level: Int, name: Int) = memScoped {
    val on = alloc<IntVar>()
    on.value = 1
    setsockopt(fd, level, name, on.ptr, sizeOf<IntVar>().convert())
}

/**
 * A connected, blocking TCP socket to [host]:[port] over the Wi-Fi interface (en0); an [IOException]
 * when it cannot be had in [timeoutMs]. A connect or poll interrupted by a signal (EINTR, which
 * Kotlin/Native's own threads cause, FBBD-351) goes on waiting for the time left.
 */
@Suppress("ThrowsCount")
internal fun connectTcp(host: String, port: Int, timeoutMs: Int = WifiConnectTimeoutMs): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    addr.sin_len = sizeOf<sockaddr_in>().convert()
    addr.sin_family = AF_INET.convert()
    addr.sin_port = networkOrder(port)
    addr.sin_addr.s_addr = ipv4(host) ?: throw IOException("not an IPv4 address: $host")
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) throw IOException("socket: ${lastError()}")
    try {
        option(fd, SOL_SOCKET, SoNoSigPipe)
        option(fd, IPPROTO_TCP, TcpNoDelay)
        // Not the phone's own loopback (the simulator tests' adapter).
        if (!host.startsWith("127.")) bindToWifi(fd)
        val flags = fcntl(fd, F_GETFL, 0)
        fcntl(fd, F_SETFL, flags or O_NONBLOCK)
        if (platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            val e = errno
            if (e != EINPROGRESS && e != EINTR) throw IOException("connect: ${errorText(e)}")
            awaitConnected(fd, timeoutMs)
        }
        fcntl(fd, F_SETFL, flags)
        fd
    } catch (e: IOException) {
        platform.posix.close(fd)
        throw e
    }
}

/** Waits up to [timeoutMs] for [fd]'s connect to finish, through interrupted polls; throws why not. */
@Suppress("ThrowsCount")
private fun awaitConnected(fd: Int, timeoutMs: Int) = memScoped {
    val end = monoNanos() / NanosPerMilli + timeoutMs
    val p = alloc<pollfd>()
    while (true) {
        val left = (end - monoNanos() / NanosPerMilli).coerceAtLeast(0).toInt()
        p.fd = fd
        p.events = POLLOUT.toShort()
        p.revents = 0
        val n = poll(p.ptr, 1.convert(), left)
        if (n > 0) break
        if (n == 0) throw IOException("connect timed out")
        val e = errno
        if (e != EINTR) throw IOException("connect: ${errorText(e)}")
        if (left == 0) throw IOException("connect timed out")
    }
    val err = alloc<IntVar>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<IntVar>().convert()
    getsockopt(fd, SOL_SOCKET, SO_ERROR, err.ptr, len.ptr)
    if (err.value != 0) throw IOException("connect: ${errorText(err.value)}")
}

/**
 * Sends [fd] out over the Wi-Fi interface (Darwin IP_BOUND_IF on en0), so iOS does not try the
 * adapter's address over the cellular network while the Wi-Fi has no internet. Without en0 (or when
 * iOS refuses) the socket stays unbound and iOS routes it.
 */
private fun bindToWifi(fd: Int) = memScoped {
    val index = if_nametoindex(WifiInterface)
    if (index != 0u) {
        val v = alloc<IntVar>()
        v.value = index.toInt()
        setsockopt(fd, IPPROTO_IP, IpBoundIf, v.ptr, sizeOf<IntVar>().convert())
    }
}

private fun errorText(e: Int) = "${strerror(e)?.toKString() ?: "error"} (errno $e)"

/**
 * An [ObdConnection] over the connected socket [fd], as the Android app's StreamObdConnection: a
 * thread named obd-wifi reads until the adapter closes or the link fails; [close] shuts the socket,
 * which ends a blocked read. Nothing it meets leaves its thread.
 */
internal class SocketObdConnection(private val fd: Int, private val bytes: ObdBytes) : ObdConnection {
    private val lock = NSRecursiveLock()
    private var ended = false

    init {
        val thread = NSThread { read() }
        thread.name = "obd-wifi"
        thread.start()
    }

    override fun write(bytes: ByteArray) {
        locked {
            if (ended) throw IOException("connection closed")
            if (bytes.isNotEmpty()) bytes.usePinned { sendAll(it, bytes.size) }
        }
    }

    override fun close() = end("closed")

    private fun sendAll(pin: Pinned<ByteArray>, size: Int) {
        var off = 0
        while (off < size) {
            val n = send(fd, pin.addressOf(off), (size - off).convert(), 0)
            if (n < 0) {
                if (errno == EINTR) continue
                throw IOException("write failed: ${lastError()}")
            }
            off += n.toInt()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever the reader meets ends the connection, not the app.
    private fun read() {
        val reason = try {
            val buf = ByteArray(ReadBufferBytes)
            buf.usePinned { readUntilEnd(buf, it) }
        } catch (e: Throwable) {
            "read failed: $e"
        }
        end(reason)
        locked { platform.posix.close(fd) }
    }

    private fun readUntilEnd(buf: ByteArray, pin: Pinned<ByteArray>): String {
        while (true) {
            val n = recv(fd, pin.addressOf(0), buf.size.convert(), 0)
            when {
                n > 0 -> bytes.received(buf.copyOf(n.toInt()))
                n == 0L -> return "stream ended"
                errno == EINTR -> Unit
                else -> return "read failed: ${lastError()}"
            }
        }
    }

    private fun end(reason: String) {
        locked {
            if (ended) return
            ended = true
            shutdown(fd, SHUT_RDWR)
        }
        bytes.closed(reason)
    }

    private inline fun <T> locked(body: () -> T): T {
        lock.lock()
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }
}
