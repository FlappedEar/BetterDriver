package com.flappedear.betterdriver.obd

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

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
