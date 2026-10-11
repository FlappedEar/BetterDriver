package com.flappedear.betterdriver.core.ble.nmea

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.ReconnectPolicy
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val ReadBufferBytes = 1_024
private const val WatchdogMs = 1_000L

/**
 * Receivers send at 1 to 10 Hz, so 5 s without a byte means the link is gone even if the socket
 * has not noticed yet (a receiver switched off mid-session can leave a read blocked for a long time).
 */
private const val SilenceTimeoutMs = 5_000L

/** A failed classic connect already takes seconds (page timeout), so retries need not be quicker. */
private const val FirstRetryMs = 1_000L
private const val MaxRetryMs = 5_000L
private const val LineFeed: Byte = 0x0A

/** Why a connection attempt or session ended, for the state and the session log. */
private class LinkFailure(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

/**
 * Keeps a Bluetooth Classic Serial Port Profile (RFCOMM) link to one paired NMEA receiver: connect,
 * read on a dedicated thread, and reconnect with back-off after a drop, a failed connect, or 5 s
 * without data, until [stop]. [onData] gets every chunk read, on the reader thread, and must return
 * quickly. [onState] is called with each new [LinkState], one call at a time; [LinkState.Streaming]
 * carries the number of NMEA sentences (line ends) in the last second.
 *
 * The receiver must be bonded (paired in Android settings). The secure socket is tried first; some
 * receivers only accept an unauthenticated one, so the insecure socket is the fallback. Discovery
 * is cancelled before connecting because it slows a classic connect down a lot.
 *
 * Bluetooth permissions are checked by the app before it starts a link. If BLUETOOTH_CONNECT is
 * missing the SecurityException is reported as the failure reason and retried, never thrown.
 */
@SuppressLint("MissingPermission")
class SppLink(
    context: Context,
    private val address: String,
    private val onData: (ByteArray) -> Unit,
    private val onState: (LinkState) -> Unit = {},
) {
    private val appContext = context.applicationContext
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
    private var socket: BluetoothSocket? = null

    /** elapsedRealtime of the last byte (or of connecting), 0 while not connected. */
    @Volatile
    private var lastDataAt = 0L

    @Volatile
    private var receiving = false

    /** Set by the watchdog when it closes a silent socket, so the session ends with that reason. */
    @Volatile
    private var closeReason: String? = null

    private val lines = AtomicInteger()

    // Touched only on the reader thread.
    private val policy = ReconnectPolicy(FirstRetryMs, MaxRetryMs)

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            running = true
            watchdog = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "nmea-spp-watchdog").apply { isDaemon = true }
            }.apply { scheduleWithFixedDelay(::tick, WatchdogMs, WatchdogMs, TimeUnit.MILLISECONDS) }
            reader = Thread(::loop, "nmea-spp-link").apply {
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
        socket?.let(::closeQuietly)
        thread?.interrupt()
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
            socket?.let(::closeQuietly)
            socket = null
        }
    }

    private fun open(): BluetoothSocket {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) throw LinkFailure("bluetooth off")
        val device = remoteDevice(adapter, address)
        cancelDiscovery(adapter)
        val secure = try {
            return connect(device.createRfcommSocketToServiceRecord(SppUuid))
        } catch (e: IOException) {
            e
        }
        return try {
            connect(device.createInsecureRfcommSocketToServiceRecord(SppUuid))
        } catch (insecure: IOException) {
            insecure.addSuppressed(secure)
            throw LinkFailure("connect failed: ${secure.message}; insecure: ${insecure.message}", insecure)
        }
    }

    /** Blocks until connected. [stop] closes [socket], which makes this throw. */
    private fun connect(s: BluetoothSocket): BluetoothSocket {
        socket = s
        if (!running) {
            closeQuietly(s)
            throw LinkFailure("stopped")
        }
        try {
            s.connect()
        } catch (e: IOException) {
            closeQuietly(s)
            throw e
        }
        return s
    }

    private fun read(s: BluetoothSocket): String {
        val input = s.inputStream
        val buf = ByteArray(ReadBufferBytes)
        lastDataAt = SystemClock.elapsedRealtime()
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
        lastDataAt = SystemClock.elapsedRealtime()
        var count = 0
        for (i in 0 until n) if (buf[i] == LineFeed) count++
        lines.addAndGet(count)
        onData(buf.copyOf(n))
    }

    /** Once a second: report the sentence rate, and close a socket that went silent. */
    private fun tick() {
        val count = lines.getAndSet(0)
        val last = lastDataAt
        if (last == 0L) return
        val silentMs = SystemClock.elapsedRealtime() - last
        if (silentMs > SilenceTimeoutMs) {
            closeReason = if (receiving) "no data for $silentMs ms" else "no data after connecting"
            socket?.let(::closeQuietly)
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

private fun remoteDevice(adapter: BluetoothAdapter, address: String): BluetoothDevice = try {
    adapter.getRemoteDevice(address)
} catch (e: IllegalArgumentException) {
    throw LinkFailure("bad address: ${e.message}", e)
}

/** Needs BLUETOOTH_SCAN on Android 12+. Only a speed-up, so going without it is fine. */
@SuppressLint("MissingPermission")
private fun cancelDiscovery(adapter: BluetoothAdapter) {
    try {
        adapter.cancelDiscovery()
    } catch (_: SecurityException) {
        // Connect anyway; it is only slower while another app is discovering.
    }
}

private fun closeQuietly(s: BluetoothSocket) {
    try {
        s.close()
    } catch (_: IOException) {
        // Already closed or never connected.
    }
}
