package com.flappedear.betterdriver.core.ble.obd

import android.os.SystemClock
import com.flappedear.betterdriver.core.ble.nmea.TcpNetwork
import com.flappedear.betterdriver.obd.CarProfile
import com.flappedear.betterdriver.obd.ElmLine
import com.flappedear.betterdriver.obd.ElmPoller
import com.flappedear.betterdriver.obd.ObdClock
import com.flappedear.betterdriver.obd.ObdListener
import com.flappedear.betterdriver.obd.PidChannel
import com.flappedear.betterdriver.obd.StandardPids
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.log.DebugLog
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Session events of the OBD-II reader (FBBD-15). */
object ObdEvents {
    /** Text "host=<host:port> adapter=<ATI answer> protocol=<ATDPN answer> pids=<supported, hex>". */
    const val CONNECTED = "obd_connected"

    /** Text "reason=<why the connection ended>". */
    const val DISCONNECTED = "obd_disconnected"
}

/** A Wi-Fi ELM327 to try: [host]:[port], connected through [network]. */
data class ObdTarget(val host: String, val port: Int, val network: TcpNetwork)

private const val Tag = "Obd"
private const val ConnectTimeoutMs = 3_000
private const val ReadBufferBytes = 1_024
private const val MicrosPerMs = 1_000L

/** Retry after an ended connection: 5 s; while no adapter answers: 5 s, 10 s, then every 20 s. */
private val RetryMs = listOf(5_000L, 10_000L, 20_000L)

/** Channel ids of OBD channels: [ObdChannelBase] + the PID's place in [StandardPids.all]. */
private const val ObdChannelBase = 800
private const val ObdChannelOther = 850
private const val ObdDecimals = 2

/**
 * A Wi-Fi ELM327 OBD-II adapter as a recorder [DataSource] (FBBD-15, first transport for Arek's
 * Wi-Fi adapter). While the session runs it keeps trying [targets] (the phone must be on the
 * adapter's Wi-Fi network), sets the adapter up, discovers the car's mode 01 PIDs and records
 * every one it answers as a channel "obd_<id>" with source "obd" (so its provenance is
 * measured OBD). A lost adapter or a car switched off never touches the rest of the session: this
 * runs on its own thread and reconnects on its own.
 *
 * Nothing is shown in the UI yet; connects, drops and the per-channel rates go to the session
 * events and the app log (tag "Obd").
 */
class ObdSource(
    private val targets: () -> List<ObdTarget>,
    private val profile: CarProfile = CarProfile.Generic,
) : DataSource {
    override val name = "obd"

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var line: ElmLine? = null

    private val defined = HashMap<String, ChannelDef>()
    private var lastNote: String? = null

    override fun start(sink: SampleSink) {
        running = true
        thread = Thread({ loop(sink) }, "obd-reader").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        line?.close("stopped")
        socket?.let(::closeQuietly)
        thread?.interrupt()
        thread = null
    }

    private fun loop(sink: SampleSink) {
        var failures = 0
        while (running) {
            val reason = try {
                connectOnce(sink)
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
        DebugLog.i(Tag, "reader stopped")
    }

    /** One connection; null when it never opened (already noted), else why it ended. */
    @Suppress("ReturnCount") // Each early return is one way no connection opened.
    private fun connectOnce(sink: SampleSink): String? {
        val all = targets()
        if (all.isEmpty()) {
            note("no Wi-Fi network: join the adapter's Wi-Fi to record OBD-II")
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
        val reader = Thread({ read(s.getInputStream(), l) }, "obd-socket").apply {
            isDaemon = true
            start()
        }
        lastNote = null
        val where = "${target.host}:${target.port}"
        DebugLog.i(Tag, "connected to $where")
        val reason = try {
            ElmPoller(l, profile, Listener(sink, where), { running }, clock).run()
        } catch (e: IOException) {
            // The socket dropped or the read failed: the usual way a connection ends.
            "connection lost: ${e.message}"
        } finally {
            line = null
            socket = null
            closeQuietly(s)
            reader.interrupt()
        }
        DebugLog.w(Tag, "connection to $where ended: $reason")
        sink.event(ObdEvents.DISCONNECTED, "reason=$reason")
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
        note("no adapter answered: ${errors.joinToString("; ")}")
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
    private fun note(text: String) {
        if (text == lastNote) return
        lastNote = text
        DebugLog.i(Tag, text)
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

        override fun sleep(ms: Long) = this@ObdSource.sleep(ms)
    }

    private inner class Listener(private val sink: SampleSink, private val where: String) : ObdListener {
        override fun ready(adapter: String, protocol: String, supported: Set<Int>, channels: List<PidChannel>) {
            val pids = supported.joinToString(",") { "%02X".format(it) }
            val clean = adapter.replace(Regex("\\s+"), " ")
            sink.event(ObdEvents.CONNECTED, "host=$where adapter=$clean protocol=$protocol pids=$pids")
            val polled = channels.joinToString(",") { it.id }
            DebugLog.i(Tag, "car answered: protocol $protocol, PIDs $pids, polling $polled")
            for (c in channels) defineOnce(c)
        }

        override fun value(channel: PidChannel, value: Double, atMs: Long) {
            val def = defined[channel.id] ?: return
            val ageMicros = (SystemClock.elapsedRealtime() - atMs) * MicrosPerMs
            sink.submit(SampleSet(sink.nowMicros() - ageMicros, listOf(ChannelValue(def.id, value))))
        }

        override fun log(text: String) = DebugLog.i(Tag, text)

        private fun defineOnce(c: PidChannel) {
            if (c.id in defined) return
            val standard = StandardPids.all.indexOfFirst { it.id == c.id }
            val id = if (standard >= 0) ObdChannelBase + standard else ObdChannelOther + defined.size
            val def = ChannelDef(id, "obd_${c.id}", c.unit, "obd", ObdDecimals)
            defined[c.id] = def
            sink.defineChannel(def)
        }
    }
}

private fun closeQuietly(s: Socket) {
    try {
        s.close()
    } catch (_: IOException) {
        // Already closed.
    }
}
