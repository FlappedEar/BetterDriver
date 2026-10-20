package com.flappedear.betterdriver.core.ble.obd

import android.os.SystemClock
import com.flappedear.betterdriver.core.ble.nmea.TcpNetwork
import com.flappedear.betterdriver.obd.CarProfile
import com.flappedear.betterdriver.obd.ElmLine
import com.flappedear.betterdriver.obd.ElmPoller
import com.flappedear.betterdriver.obd.ObdClock
import com.flappedear.betterdriver.obd.ObdListener
import com.flappedear.betterdriver.obd.ObdStatus
import com.flappedear.betterdriver.obd.ObdStatusTracker
import com.flappedear.betterdriver.obd.PidChannel
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A Wi-Fi ELM327 to try: [host]:[port], connected through [network]. */
data class ObdTarget(val host: String, val port: Int, val network: TcpNetwork)

/** What an [ObdLink] reports, on its own threads. */
interface ObdLinkListener {
    /** The adapter at [where] set up and the car answered: [channels] are polled from now. */
    fun ready(where: String, adapter: String, protocol: String, supported: Set<Int>, channels: List<PidChannel>) {}

    /** [channel] read [value] at [atMs] on SystemClock.elapsedRealtime(). */
    fun value(channel: PidChannel, value: Double, atMs: Long) {}

    /** A connection that opened has ended, with [reason]. */
    fun ended(where: String, reason: String) {}
}

private const val Tag = "Obd"
private const val ConnectTimeoutMs = 3_000
private const val ReadBufferBytes = 1_024

/** Retry after an ended connection: 5 s; while no adapter answers: 5 s, 10 s, then every 20 s. */
private val RetryMs = listOf(5_000L, 10_000L, 20_000L)

/**
 * A connection to a Wi-Fi ELM327 OBD-II adapter that keeps itself up (FBBD-15): while started it
 * tries [targets] (the phone must be on the adapter's Wi-Fi network), runs an [ElmPoller] on the
 * first that answers and reconnects when it ends. It runs on its own threads, so a lost adapter or a
 * car switched off never touches its owner. [status] is the connection as the OBD page shows it
 * (FBBD-261). Used by the recorder ([ObdSource]) and, outside a session, by the OBD page; the
 * adapter takes one connection at a time, so only one runs at once.
 */
class ObdLink(
    private val targets: () -> List<ObdTarget>,
    private val listener: ObdLinkListener = object : ObdLinkListener {},
    private val profile: CarProfile = CarProfile.Generic,
    private val owner: String = "recorder",
) {
    private val tracker = ObdStatusTracker()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var line: ElmLine? = null

    private var lastNote: String? = null

    fun status(): ObdStatus = tracker.snapshot(SystemClock.elapsedRealtime())

    fun start() {
        running = true
        thread = Thread({ loop() }, "obd-$owner").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        line?.close("stopped")
        socket?.let(::closeQuietly)
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var failures = 0
        while (running) {
            val reason = try {
                connectOnce()
            } catch (e: IOException) {
                "connection lost: ${e.message}"
            } catch (e: SecurityException) {
                "no permission: ${e.message}"
            }
            if (!running) break
            // A connection that opened and ended is retried soon; no adapter at all backs off.
            failures = if (reason == null) failures + 1 else 0
            sleep(RetryMs[(failures - 1).coerceIn(0, RetryMs.size - 1)])
        }
        DebugLog.i(Tag, "$owner: reader stopped")
    }

    /** One connection; null when it never opened (already noted), else why it ended. */
    @Suppress("ReturnCount") // Each early return is one way no connection opened.
    private fun connectOnce(): String? {
        val all = targets()
        if (all.isEmpty()) {
            note("no Wi-Fi network: join the adapter's Wi-Fi to record OBD-II", "Phone not on the adapter's Wi-Fi")
            return null
        }
        val (target, s) = open(all) ?: return null
        socket = s
        val l = ElmLine(write = { bytes ->
            s.getOutputStream().apply {
                write(bytes)
                flush()
            }
        })
        line = l
        val reader = Thread({ read(s.getInputStream(), l) }, "obd-socket-$owner").apply {
            isDaemon = true
            start()
        }
        lastNote = null
        val where = "${target.host}:${target.port}"
        DebugLog.i(Tag, "$owner: connected to $where")
        tracker.connecting(where)
        val reason = try {
            ElmPoller(l, profile, Poll(where), { running }, clock).run()
        } catch (e: IOException) {
            // The socket dropped or the read failed: the usual way a connection ends.
            "connection lost: ${e.message}"
        } finally {
            line = null
            socket = null
            closeQuietly(s)
            reader.interrupt()
        }
        DebugLog.w(Tag, "$owner: connection to $where ended: $reason")
        tracker.ended(reason)
        listener.ended(where, reason)
        return reason
    }

    /** The first target that accepts a connection, or null (noted) when none does. */
    @Suppress("ReturnCount") // Connected, stopped, or none answered.
    private fun open(all: List<ObdTarget>): Pair<ObdTarget, Socket>? {
        val errors = ArrayList<String>()
        for (t in all) {
            val s = t.network.createSocket()
            socket = s
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(t.network.resolve(t.host), t.port), ConnectTimeoutMs)
                return t to s
            } catch (e: IOException) {
                closeQuietly(s)
                errors += "${t.host}:${t.port} ${e.message}"
            }
            if (!running) return null
        }
        note("no adapter answered: ${errors.joinToString("; ")}", "No adapter answered · trying again")
        return null
    }

    private fun read(input: InputStream, l: ElmLine) {
        val buf = ByteArray(ReadBufferBytes)
        val reason = try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) l.feed(buf.copyOf(n))
            }
            "stream ended"
        } catch (e: IOException) {
            "read failed: ${e.message}"
        }
        l.close(reason)
    }

    /** Logs [text] once until it changes, so a missing adapter is not logged every few seconds. */
    private fun note(text: String, shown: String) {
        tracker.searching(shown)
        if (text == lastNote) return
        lastNote = text
        DebugLog.i(Tag, "$owner: $text")
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // Only stop() interrupts, and it has cleared [running].
        }
    }

    private val clock = object : ObdClock {
        override fun nowMs() = SystemClock.elapsedRealtime()

        override fun sleep(ms: Long) = this@ObdLink.sleep(ms)
    }

    private inner class Poll(private val where: String) : ObdListener {
        override fun ready(
            adapter: String,
            protocol: String,
            voltage: String?,
            supported: Set<Int>,
            channels: List<PidChannel>,
        ) {
            val clean = adapter.replace(Regex("\\s+"), " ")
            val pids = supported.joinToString(",") { "%02X".format(it) }
            val polled = channels.joinToString(",") { it.id }
            DebugLog.i(Tag, "$owner: car answered: protocol $protocol, PIDs $pids, polling $polled")
            tracker.ready(clean, protocol, voltage, channels, SystemClock.elapsedRealtime())
            listener.ready(where, clean, protocol, supported, channels)
        }

        override fun value(channel: PidChannel, value: Double, atMs: Long) {
            tracker.value(channel, value, atMs)
            listener.value(channel, value, atMs)
        }

        override fun log(text: String) = DebugLog.i(Tag, "$owner: $text")
    }
}

private fun closeQuietly(s: Socket) {
    try {
        s.close()
    } catch (_: IOException) {
        // Already closed.
    }
}
