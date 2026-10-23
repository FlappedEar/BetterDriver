package com.flappedear.betterdriver.canreader

import java.io.Writer

/**
 * The reader's log (FBBD-319): one plain-text line per event, `<ms since start> <kind> <text>`, for
 * a later analysis thread to read. Kinds: `step` (what the reader is doing), `info`, `cmd` (a
 * command, its time and the adapter's raw answer), `pid` and `diag` (a request read repeatedly:
 * answers, rate and latency), `dtc`, `survey` and `listen` (CAN ids and their rates), `frame` (one
 * CAN frame: id and data bytes), `sweep` (a mode 21 answer), `value` and `summary`. Only `step`,
 * `info` and `summary` lines also go to [tail], which the screen shows.
 */
class ReaderLog(private val out: Writer, private val clock: () -> Long) {
    private val started = clock()
    private val lock = Any()
    private val recent = ArrayDeque<String>()

    fun step(text: String) = write("step", text, show = true)

    fun info(text: String) = write("info", text, show = true)

    fun summary(text: String) = write("summary", text, show = true)

    /** A line of [kind] for the file only. */
    fun data(kind: String, text: String) = write(kind, text, show = false)

    /** The last lines shown on the screen, oldest first. */
    fun tail(): List<String> = synchronized(lock) { recent.toList() }

    fun flush() = synchronized(lock) { out.flush() }

    private fun write(kind: String, text: String, show: Boolean) {
        val line = "${clock() - started} $kind $text"
        synchronized(lock) {
            out.write(line)
            out.write("\n")
            if (show) {
                recent.addLast(line)
                if (recent.size > TAIL_LINES) recent.removeFirst()
            }
        }
    }

    private companion object {
        const val TAIL_LINES = 200
    }
}
