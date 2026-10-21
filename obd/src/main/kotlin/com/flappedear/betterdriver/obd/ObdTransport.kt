package com.flappedear.betterdriver.obd

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What an open [ObdConnection] hands to its owner, on the transport's own thread. */
interface ObdBytes {
    /** Bytes from the adapter, in the order they arrived. */
    fun received(bytes: ByteArray)

    /** The connection is gone, with why; called at most once. */
    fun closed(reason: String)
}

/** One open serial connection to an ELM327 adapter (FBBD-121). */
interface ObdConnection : Closeable {
    /** Sends [bytes] to the adapter; throws an [IOException] when the connection is gone. */
    fun write(bytes: ByteArray)

    /** Ends the connection; [ObdBytes.closed] follows. Safe to call more than once. */
    override fun close()
}

/** How an adapter is reached, for the log and the session event. */
enum class ObdTransport(val label: String) {
    Wifi("Wi-Fi"),
    BluetoothClassic("Bluetooth"),
    BluetoothLe("Bluetooth LE"),
}

/**
 * One adapter to try (FBBD-15, FBBD-121): a Wi-Fi ELM327, a paired Bluetooth Classic one (SPP) or a
 * Bluetooth LE one with a serial characteristic. [open] connects, blocking until the line is ready,
 * and throws an [IOException] when it cannot; bytes then go to the [ObdBytes] on the transport's
 * thread. [where] names the adapter in the log, [name] on the OBD page (UI-106 A: the name Android
 * shows for a Bluetooth adapter), [transport] says how it is reached. [address] is a Bluetooth
 * adapter's, logged with its connection so the drive checks can tell which listed adapter it was
 * (FBBD-280); null for Wi-Fi.
 */
interface ObdTarget {
    val where: String
    val transport: ObdTransport
    val name: String get() = where
    val address: String? get() = null

    fun open(bytes: ObdBytes): ObdConnection
}

private const val ReadBufferBytes = 1_024

/**
 * An [ObdConnection] over a byte stream pair: a TCP socket (Wi-Fi) or an RFCOMM socket (Bluetooth
 * Classic SPP). A daemon thread named [threadName] reads [input] until it ends; [closeable] (the
 * socket) is closed on [close], which also ends a blocked read.
 */
class StreamObdConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeable: Closeable,
    private val bytes: ObdBytes,
    threadName: String,
) : ObdConnection {
    private val lock = Any()
    private var ended = false

    init {
        Thread({ read() }, threadName).apply {
            isDaemon = true
            start()
        }
    }

    override fun write(bytes: ByteArray) {
        synchronized(lock) { if (ended) throw IOException("connection closed") }
        output.write(bytes)
        output.flush()
    }

    override fun close() = end("closed")

    private fun read() {
        val buf = ByteArray(ReadBufferBytes)
        val reason = try {
            var n = input.read(buf)
            while (n >= 0) {
                if (n > 0) bytes.received(buf.copyOf(n))
                n = input.read(buf)
            }
            "stream ended"
        } catch (e: IOException) {
            "read failed: ${e.message}"
        }
        end(reason)
    }

    private fun end(reason: String) {
        synchronized(lock) {
            if (ended) return
            ended = true
        }
        try {
            closeable.close()
        } catch (_: IOException) {
            // Already closed.
        }
        bytes.closed(reason)
    }
}
