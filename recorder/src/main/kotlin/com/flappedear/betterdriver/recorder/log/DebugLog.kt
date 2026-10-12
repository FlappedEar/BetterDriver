package com.flappedear.betterdriver.recorder.log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's debug log (FBBD-146): one line per event, written to a rotating file per process so
 * Arek can send the files from Settings › App › Debug logs. Every process installs its own
 * [LogFile]; until then, and while the log is off, calls only reach [echo] (logcat on Android).
 *
 * Thread-safe. Never throws: a log that cannot be written is skipped.
 */
object DebugLog {
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    @Volatile
    private var file: LogFile? = null

    /** Also called for every line, on or off: Android installs logcat here. */
    @Volatile
    var echo: ((level: Int, tag: String, message: String, error: Throwable?) -> Unit)? = null

    /**
     * Runs each file write when set; debug builds exempt the log's own writes from StrictMode here
     * (FBBD-41). Null, as in release builds, writes directly.
     */
    @Volatile
    var fileWrites: ((write: () -> Unit) -> Unit)? = null

    fun install(file: LogFile?) {
        this.file = file
    }

    fun d(tag: String, message: String) = log(DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = log(WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = log(ERROR, tag, message, error)

    @Suppress("TooGenericExceptionCaught") // Logging must never take the caller down.
    fun log(level: Int, tag: String, message: String, error: Throwable?) {
        try {
            echo?.invoke(level, tag, message, error)
        } catch (_: RuntimeException) {
            // logcat unavailable (JVM tests): the file still gets the line.
        }
        val f = file ?: return
        val around = fileWrites
        if (around == null) f.write(level, tag, message, error) else around { f.write(level, tag, message, error) }
    }
}

/**
 * A log file that rotates as [rotation] says: `<name>.log` is the newest, then `<name>.1.log` up to
 * `<name>.<keep>.log`. Each line is flushed at once, so a crash or a killed process keeps every
 * line before it. [enabled] is asked before each line; the app keeps it cheap.
 */
class LogFile(
    private val dir: File,
    private val name: String,
    private val rotation: Rotation = Rotation(),
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pid: Int = 0,
) {
    private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private var out: FileOutputStream? = null
    private var size = 0L

    val current: File get() = File(dir, "$name.log")

    fun write(level: Int, tag: String, message: String, error: Throwable?) {
        if (!enabled()) return
        val line = buildString {
            synchronized(format) { append(format.format(Date(clock()))) }
            append(' ').append(pid).append(' ').append(Thread.currentThread().name)
            append(' ').append(levelChar(level)).append(' ').append(tag).append(": ").append(message)
            if (error != null) append('\n').append(stackTrace(error).trimEnd())
            append('\n')
        }
        append(line.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    private fun append(bytes: ByteArray) {
        try {
            val o = out ?: open()
            o.write(bytes)
            o.flush()
            size += bytes.size
            if (size >= rotation.maxBytes) rotate()
        } catch (_: IOException) {
            close()
        }
    }

    private fun open(): FileOutputStream {
        dir.mkdirs()
        val f = current
        size = f.length()
        return FileOutputStream(f, true).also { out = it }
    }

    private fun rotate() {
        close()
        File(dir, "$name.${rotation.keep}.log").delete()
        for (i in rotation.keep - 1 downTo 1) File(dir, "$name.$i.log").renameTo(File(dir, "$name.${i + 1}.log"))
        current.renameTo(File(dir, "$name.1.log"))
        size = 0
    }

    @Synchronized
    fun close() {
        try {
            out?.close()
        } catch (_: IOException) {
            // Nothing left to save.
        }
        out = null
    }

    /** A file rotates at [maxBytes]; [keep] older files are kept beside the current one. */
    data class Rotation(val maxBytes: Long = DefaultMaxBytes, val keep: Int = DefaultKeep)

    companion object {
        const val DefaultMaxBytes = 1_000_000L
        const val DefaultKeep = 4

        private fun levelChar(level: Int) = when (level) {
            DebugLog.DEBUG -> 'D'
            DebugLog.INFO -> 'I'
            DebugLog.WARN -> 'W'
            else -> 'E'
        }

        private fun stackTrace(t: Throwable): String =
            StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
    }
}
