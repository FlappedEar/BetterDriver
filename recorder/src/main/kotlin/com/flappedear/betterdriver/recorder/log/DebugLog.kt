package com.flappedear.betterdriver.recorder.log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZoneId
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The app's log (FBBD-146, FBBD-183): one continuous file that every process (UI, recorder, camera,
 * coach) appends to, across sessions and app restarts, so Arek sends one file and every hardware
 * check can be worked out from it. Every process installs its own [LogFile] on the same file; until
 * then calls only reach [echo] (logcat on Android).
 *
 * Thread-safe. Never throws: a line that cannot be written is skipped.
 */
object DebugLog {
    const val DEBUG = CoreLog.LevelDebug
    const val INFO = CoreLog.LevelInfo
    const val WARN = CoreLog.LevelWarn
    const val ERROR = CoreLog.LevelError

    @Volatile
    private var file: LogFile? = null

    /** The recording this process works for, written on each line; null outside one. */
    @Volatile
    var session: String? = null

    /** Also called for every line: Android installs logcat here. */
    @Volatile
    var echo: ((level: Int, tag: String, message: String, error: Throwable?) -> Unit)? = null

    /**
     * Runs each write made on the caller's thread ([flush]) when set; debug builds exempt the log's
     * own writes from StrictMode here (FBBD-41). Lines are otherwise written on the log's own thread.
     */
    @Volatile
    var fileWrites: ((write: () -> Unit) -> Unit)? = null

    fun install(file: LogFile?) {
        this.file?.close()
        this.file = file
    }

    fun d(tag: String, message: String) = log(DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = log(WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = log(ERROR, tag, message, error)

    /** A line for recording [session], whichever recording this process is following. */
    fun forSession(session: String, tag: String, message: String) = log(INFO, tag, message, null, session)

    @Suppress("TooGenericExceptionCaught") // Logging must never take the caller down.
    fun log(level: Int, tag: String, message: String, error: Throwable?, session: String? = this.session) {
        try {
            echo?.invoke(level, tag, message, error)
        } catch (_: RuntimeException) {
            // logcat unavailable (JVM tests): the file still gets the line.
        }
        file?.write(level, tag, message, error, session)
    }

    /** Writes every queued line now, on the caller's thread: before the process dies of a crash. */
    fun flush() {
        val f = file ?: return
        val around = fileWrites
        if (around == null) f.flush() else around { f.flush() }
    }
}

/**
 * The continuous log [file], as one process writes it. Lines are queued and appended by a thread of
 * the log's own ([background]), never on the caller's; each batch is one append to a file opened in
 * append mode, so lines of different processes never mix within a line. The file is opened for each
 * batch, so a cleared log is simply started again. Nothing here rotates or deletes it: only the
 * user's "Clear log" does (FBBD-183).
 *
 * [uptimeMicros] is the phone's boot clock (Android's elapsedRealtime), [session] the recording a
 * line belongs to by default.
 */
@Suppress("LongParameterList") // Clocks and names are test hooks.
class LogFile(
    val file: File,
    private val process: String,
    private val pid: Int = 0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uptimeMicros: () -> Long = { System.nanoTime() / NanosPerMicro },
    background: Boolean = true,
    /** The phone's time zone at each line, so a change of zone shows from the next line on. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val queue = LinkedBlockingQueue<String>(MaxQueued)
    private val dropped = AtomicLong()
    private val lock = Any()
    private val signal = ReentrantLock()
    private val queued = signal.newCondition()

    @Volatile
    private var closed = false
    private val writer: Thread? = if (background) {
        Thread(::drainLoop, "log-writer").apply {
            isDaemon = true
            start()
        }
    } else {
        null
    }

    fun write(level: Int, tag: String, message: String, error: Throwable?, session: String?) {
        if (closed) return
        val line = LogLine(
            clock(), uptimeMicros(), process, pid, Thread.currentThread().name, levelChar(level), tag, session, message,
        ).format(zone())
        val text = if (error == null) line + "\n" else line + "\n" + stackTrace(error).trimEnd() + "\n"
        if (writer == null) {
            append(listOf(text))
        } else if (queue.offer(text)) {
            signal.withLock { queued.signalAll() }
        } else {
            dropped.incrementAndGet()
        }
    }

    /**
     * Appends every queued line now. Lines leave the queue only under [lock], so a batch the writer
     * thread has taken is on file by the time this returns too.
     */
    fun flush() {
        synchronized(lock) { appendQueued() }
    }

    /** Writes what is queued and stops the writer. */
    fun close() {
        closed = true
        writer?.interrupt()
        flush()
    }

    private fun drainLoop() {
        while (!closed) {
            try {
                signal.withLock {
                    if (queue.isEmpty() && !closed) queued.await(PollMillis, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                return
            }
            synchronized(lock) { appendQueued() }
        }
    }

    private fun appendQueued() {
        val batch = mutableListOf<String>()
        queue.drainTo(batch)
        if (batch.isNotEmpty()) append(batch)
    }

    private fun append(lines: List<String>) {
        val lost = dropped.getAndSet(0)
        val bytes = buildString {
            if (lost > 0) {
                val text = "$lost lines dropped: the log was too far behind"
                val line = LogLine(clock(), uptimeMicros(), process, pid, "log-writer", 'W', Tag, null, text)
                append(line.format(zone()))
                append('\n')
            }
            lines.forEach(::append)
        }.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { it.write(bytes) }
            } catch (_: IOException) {
                // Full or unreadable storage: these lines are lost, the app carries on.
            }
        }
    }

    companion object {
        private const val Tag = "Log"
        private const val NanosPerMicro = 1_000L
        private const val PollMillis = 1_000L

        /** Lines waiting at the most; more are counted as dropped and the count is written. */
        private const val MaxQueued = 20_000

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
