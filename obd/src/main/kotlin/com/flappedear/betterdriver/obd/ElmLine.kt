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
class ElmLine(private val write: (ByteArray) -> Unit, private val resyncMs: Long = DEFAULT_RESYNC_MS) {
    private val lock = Object()
    private val received = StringBuilder()
    private var closed: String? = null

    /** The last command timed out: the adapter may still print its answer and prompt. */
    private var outstanding = false

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
     * null when no prompt came within [timeoutMs]. After a timeout the adapter may still be working
     * on that command, and a byte sent now would abort it and be lost, or its late answer would be
     * read as the next one's; so the next command first waits up to [resyncMs] for that late
     * prompt and throws the late answer away. Throws an [IOException] when the line is [close]d.
     */
    fun command(text: String, timeoutMs: Long): String? {
        synchronized(lock) {
            if (outstanding) awaitPrompt(resyncMs)
            outstanding = false
            closed?.let { throw IOException(it) }
            received.setLength(0)
        }
        write((text + "\r").toByteArray(Charsets.US_ASCII))
        synchronized(lock) {
            val answer = awaitPrompt(timeoutMs)
            if (answer == null) outstanding = true
            return answer
        }
    }

    /** Under [lock]: what came before the next prompt, consumed with it, or null after [timeoutMs]. */
    private fun awaitPrompt(timeoutMs: Long): String? {
        val deadline = System.nanoTime() + timeoutMs * NanosPerMs
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

    companion object {
        /** How long a command waits for the late prompt of one that timed out. */
        const val DEFAULT_RESYNC_MS = 2_000L
        private const val NanosPerMs = 1_000_000L
    }
}
