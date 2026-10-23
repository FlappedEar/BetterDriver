package com.flappedear.betterdriver.canreader

/** One line the adapter printed, stamped with when its last byte arrived. */
data class TimedLine(val atMs: Long, val text: String)

/**
 * Stamps what the adapter prints while it monitors the bus (FBBD-319). An ELM327 has no timestamps
 * of its own and [com.flappedear.betterdriver.obd.ElmLine.monitor] hands back the whole text at
 * the end, so the transport's bytes also come here, and each line is stamped on arrival with
 * [clock]. Lines are kept only between [start] and [stop].
 */
class FrameTap(private val clock: () -> Long) {
    private val lock = Any()
    private val partial = StringBuilder()
    private var lines: MutableList<TimedLine>? = null

    /** Bytes from the adapter, as they came. */
    fun feed(bytes: ByteArray) {
        synchronized(lock) {
            for (b in bytes) {
                val c = (b.toInt() and BYTE_MASK).toChar()
                if (c == '\r' || c == '\n' || c == '>') {
                    endLine()
                } else if (c != '\u0000') {
                    partial.append(c)
                }
            }
        }
    }

    /** Starts keeping lines; a half line from before is dropped. */
    fun start() {
        synchronized(lock) {
            partial.setLength(0)
            lines = ArrayList()
        }
    }

    /** The lines kept since [start], and stops keeping them. */
    fun stop(): List<TimedLine> = synchronized(lock) {
        endLine()
        lines.orEmpty().also { lines = null }
    }

    private fun endLine() {
        val text = partial.toString().trim()
        partial.setLength(0)
        if (text.isNotEmpty()) lines?.add(TimedLine(clock(), text))
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
