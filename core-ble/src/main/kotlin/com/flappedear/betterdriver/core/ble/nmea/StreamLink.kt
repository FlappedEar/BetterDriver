package com.flappedear.betterdriver.core.ble.nmea

import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.ReconnectPolicy
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val ReadBufferBytes = 1_024
private const val WatchdogMs = 1_000L
private const val LineFeed: Byte = 0x0A
private const val NanosPerMs = 1_000_000L

/**
 * Reconnect and silence timing of a [StreamLink]. The defaults suit NMEA receivers: they send at 1
 * to 10 Hz, so 5 s without a byte means the link is gone even if the socket has not noticed yet (a
 * receiver switched off mid-session can leave a read blocked for a long time); and a failed classic
 * Bluetooth connect already takes seconds (page timeout), so retries need not be quicker than 1 s,
 * 2 s, 4 s, then every 5 s.
 */
data class StreamTiming(
    val firstRetryMs: Long = 1_000L,
    val maxRetryMs: Long = 5_000L,
    val silenceTimeoutMs: Long = 5_000L,
)

/** Why a connection attempt or session ended, for the state and the session log. */
internal class LinkFailure(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

/**
 * A byte stream from a device that is kept open until [stop]: connect ([open]), read on a dedicated
 * thread, and reconnect with back-off after a drop, a failed connect, or [StreamTiming.silenceTimeoutMs]
 * without data. Behind [SppLink] (Bluetooth SPP) and [TcpLink] (Wi-Fi), so both behave the same.
 *
 * [onData] gets every chunk read, on the reader thread, and must return quickly. [onState] is called
 * with each new [LinkState], one call at a time; [LinkState.Streaming] carries the number of lines
 * (line feeds, i.e. NMEA sentences) in the last second, or, for a binary stream (the VBOX Sport,
 * FBBD-116), how far [messageCount] (the decoder's running count) moved in it. [clockMs] is a
 * monotonic clock in ms.
 */
abstract class StreamLink(
    private val threadName: String,
    private val onData: (ByteArray) -> Unit,
    private val onState: (LinkState) -> Unit,
    private val timing: StreamTiming,
    private val clockMs: () -> Long = { System.nanoTime() / NanosPerMs },
    private val messageCount: (() -> Long)? = null,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    // Written under [lock].
    @Volatile
    private var running = false
    private var started = false
    private var reader: Thread? = null
    private var watchdog: ScheduledExecutorService? = null

    /** The socket connecting or connected; closing it from another thread ends a blocked connect or read. */
    @Volatile
    private var connection: Closeable? = null

    /** [clockMs] at the last byte (or at connecting), 0 while not connected. */
    @Volatile
    private var lastDataAt = 0L

    @Volatile
    private var receiving = false

    /** Set by the watchdog when it closes a silent socket, so the session ends with that reason. */
    @Volatile
    private var closeReason: String? = null

    private val lines = AtomicInteger()

    // Touched only on the watchdog thread.
    private var lastMessageCount = 0L

    // Touched only on the reader thread.
    private val policy = ReconnectPolicy(timing.firstRetryMs, timing.maxRetryMs)

    /**
     * Connects and returns the stream to read; called on the reader thread. Each socket must go
     * through [attach] before its blocking connect, so [stop] and the watchdog can close it. Throws
     * [LinkFailure] with a reason for the log, or an IOException or SecurityException.
     */
    protected abstract fun open(): InputStream

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            running = true
            watchdog = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "$threadName-watchdog").apply { isDaemon = true }
            }.apply { scheduleWithFixedDelay(::tick, WatchdogMs, WatchdogMs, TimeUnit.MILLISECONDS) }
            reader = Thread(::loop, threadName).apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Disconnects and ends the link's threads. The link cannot be started again. */
    fun stop() {
        val thread = synchronized(lock) {
            if (!running) return
            running = false
            watchdog?.shutdownNow()
            setState(LinkState.Idle)
            reader
        }
        connection?.let(::closeQuietly)
        thread?.interrupt()
    }

    /** Makes [c] the connection that [stop] and the watchdog close. Throws if the link was stopped. */
    protected fun <T : Closeable> attach(c: T): T {
        connection = c
        if (!running) {
            closeQuietly(c)
            throw LinkFailure("stopped")
        }
        return c
    }

    private fun loop() {
        while (running) {
            val reason = session()
            if (!running) break
            val delay = policy.nextDelayMs()
            setState(LinkState.Reconnecting(policy.attempts, delay, reason))
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                // Only stop() interrupts, and it has cleared [running], which ends the loop.
            }
        }
    }

    /** One connection: connect, then read until it ends. Returns why it ended. */
    private fun session(): String {
        setState(LinkState.Connecting)
        closeReason = null
        return try {
            read(open())
        } catch (e: LinkFailure) {
            e.reason
        } catch (e: IOException) {
            closeReason ?: "connection lost: ${e.message}"
        } catch (e: SecurityException) {
            "no permission: ${e.message}"
        } finally {
            // Before the next state change, so the watchdog cannot report Streaming after it.
            lastDataAt = 0L
            receiving = false
            connection?.let(::closeQuietly)
            connection = null
        }
    }

    private fun read(input: InputStream): String {
        val buf = ByteArray(ReadBufferBytes)
        lastDataAt = clockMs()
        while (running) {
            val n = input.read(buf)
            if (n < 0) return closeReason ?: "stream ended"
            if (n > 0) received(buf, n)
        }
        return "stopped"
    }

    private fun received(buf: ByteArray, n: Int) {
        if (!receiving) {
            receiving = true
            policy.reset()
        }
        lastDataAt = clockMs()
        var count = 0
        for (i in 0 until n) if (buf[i] == LineFeed) count++
        lines.addAndGet(count)
        onData(buf.copyOf(n))
    }

    /** Once a second: report the line (or message) rate, and close a connection that went silent. */
    private fun tick() {
        val lineCount = lines.getAndSet(0)
        val count = messageCount?.let {
            val total = it()
            (total - lastMessageCount).toInt().also { lastMessageCount = total }
        } ?: lineCount
        val last = lastDataAt
        if (last == 0L) return
        val silentMs = clockMs() - last
        if (silentMs > timing.silenceTimeoutMs) {
            closeReason = if (receiving) "no data for $silentMs ms" else "no data after connecting"
            connection?.let(::closeQuietly)
        } else if (receiving) {
            synchronized(lock) {
                if (lastDataAt != 0L) setState(LinkState.Streaming(count))
            }
        }
    }

    /**
     * Under [lock], so [onState] calls never overlap and the watchdog cannot report Streaming after
     * the reader moved on. Once stopped, only [LinkState.Idle] gets through.
     */
    private fun setState(s: LinkState) {
        synchronized(lock) {
            if ((running || s == LinkState.Idle) && _state.value != s) {
                _state.value = s
                onState(s)
            }
        }
    }
}

internal fun closeQuietly(c: Closeable) {
    try {
        c.close()
    } catch (_: IOException) {
        // Already closed or never connected.
    }
}
