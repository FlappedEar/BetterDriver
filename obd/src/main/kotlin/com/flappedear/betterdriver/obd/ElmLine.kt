package com.flappedear.betterdriver.obd

import java.io.IOException

/**
 * The serial line to an ELM327 (FBBD-15), whatever carries it: Bluetooth Classic SPP or a BLE
 * serial characteristic. The transport passes every byte it receives to [feed], on its own thread,
 * and [write] sends bytes to the adapter. [command] sends one command and waits for the `>` prompt
 * that ends the adapter's answer.
 *
 * One command at a time: an ELM327 answers one request before it reads the next.
 */
class ElmLine(private val write: (ByteArray) -> Unit) {
    private val lock = Object()
    private val received = StringBuilder()
    private var closed: String? = null

    /** Bytes from the adapter. NUL bytes, which some clones send before the prompt, are dropped. */
    fun feed(bytes: ByteArray) {
        synchronized(lock) {
            for (b in bytes) if (b.toInt() != 0) received.append((b.toInt() and ByteMask).toChar())
            lock.notifyAll()
        }
    }

    /** The transport is gone: a waiting [command] throws, with [reason]. */
    fun close(reason: String) {
        synchronized(lock) {
            closed = reason
            lock.notifyAll()
        }
    }

    /**
     * Sends [text] and a carriage return and returns what the adapter printed before its prompt, or
     * null when no prompt came within [timeoutMs]. Anything left over from an earlier command that
     * timed out is thrown away first. Throws an [IOException] when the line is [close]d.
     */
    fun command(text: String, timeoutMs: Long): String? {
        synchronized(lock) {
            closed?.let { throw IOException(it) }
            received.setLength(0)
        }
        write((text + "\r").toByteArray(Charsets.US_ASCII))
        val deadline = System.nanoTime() + timeoutMs * NanosPerMs
        synchronized(lock) {
            while (true) {
                val prompt = received.indexOf(">")
                if (prompt >= 0) {
                    val answer = received.substring(0, prompt)
                    received.delete(0, prompt + 1)
                    return answer
                }
                closed?.let { throw IOException(it) }
                val leftMs = (deadline - System.nanoTime()) / NanosPerMs
                if (leftMs <= 0) return null
                lock.wait(leftMs)
            }
        }
    }

    private companion object {
        const val NanosPerMs = 1_000_000L
    }
}
