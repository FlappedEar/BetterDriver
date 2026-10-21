package com.flappedear.betterdriver.core.ble.obd

import android.os.SystemClock
import com.flappedear.betterdriver.obd.CarProfile
import com.flappedear.betterdriver.obd.ObdStatus
import com.flappedear.betterdriver.obd.ObdTarget
import com.flappedear.betterdriver.obd.PidChannel
import com.flappedear.betterdriver.obd.PollSet
import com.flappedear.betterdriver.obd.StandardPids
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/** Session events of the OBD-II reader (FBBD-15). */
object ObdEvents {
    /**
     * Text "host=<adapter: host:port, or name and transport> transport=<Wi-Fi|Bluetooth|Bluetooth LE>
     * adapter=<ATI answer> protocol=<ATDPN answer> pids=<supported, hex>".
     */
    const val CONNECTED = "obd_connected"

    /** Text "reason=<why the connection ended>". */
    const val DISCONNECTED = "obd_disconnected"
}

private const val MicrosPerMs = 1_000L

/** Channel ids of OBD channels: [ObdChannelBase] + the PID's place in [StandardPids.all]. */
private const val ObdChannelBase = 800
private const val ObdChannelOther = 850
private const val ObdDecimals = 2

/**
 * An ELM327 OBD-II adapter as a recorder [DataSource] (FBBD-15 Wi-Fi, FBBD-121 Bluetooth Classic and
 * LE). While the session runs an [ObdLink] keeps trying [targets] (then [fallback]), sets the adapter up,
 * discovers the car's mode 01 PIDs and records the ones in [pollSet] it answers (FBBD-283; asked
 * again while polling, so a tile chosen during the session is read) as a channel "obd_<id>" with
 * source "obd" (so its provenance is measured OBD). A lost adapter or a car switched off never
 * touches the rest of the session.
 *
 * Connects, drops and the per-channel rates go to the session events and the app log (tag "Obd");
 * the running source's [status] is what the OBD page shows during a session (FBBD-261). [watch]
 * reports Wi-Fi and Bluetooth coming back, so a lost adapter is tried again at once (FBBD-271).
 */
class ObdSource(
    private val targets: () -> List<ObdTarget>,
    private val profile: CarProfile = CarProfile.Generic,
    private val fallback: () -> List<ObdTarget> = { emptyList() },
    private val watch: (onChange: (String) -> Unit) -> AutoCloseable = { AutoCloseable {} },
    private val pollSet: () -> PollSet = { PollSet.Driver },
) : DataSource {
    override val name = "obd"

    private var link: ObdLink? = null
    private val defined = HashMap<String, ChannelDef>()

    fun status(): ObdStatus? = link?.status()

    override fun start(sink: SampleSink) {
        val l = ObdLink(targets, Listener(sink), profile, fallback = fallback, watch = watch, pollSet = pollSet)
        link = l
        current = this
        l.start()
    }

    override fun stop() {
        link?.stop()
        link = null
        if (current === this) current = null
    }

    private inner class Listener(private val sink: SampleSink) : ObdLinkListener {
        override fun ready(
            target: ObdTarget,
            adapter: String,
            protocol: String,
            supported: Set<Int>,
            channels: List<PidChannel>,
        ) {
            val pids = supported.joinToString(",") { "%02X".format(it) }
            val how = target.transport.label
            val text = "host=${target.where} transport=$how adapter=$adapter protocol=$protocol pids=$pids"
            sink.event(ObdEvents.CONNECTED, text)
            for (c in channels) defineOnce(c)
        }

        override fun polling(target: ObdTarget, channels: List<PidChannel>) {
            for (c in channels) defineOnce(c)
        }

        override fun value(channel: PidChannel, value: Double, atMs: Long) {
            val def = defined[channel.id] ?: return
            val ageMicros = (SystemClock.elapsedRealtime() - atMs) * MicrosPerMs
            sink.submit(SampleSet(sink.nowMicros() - ageMicros, listOf(ChannelValue(def.id, value))))
        }

        override fun ended(target: ObdTarget, reason: String) {
            sink.event(ObdEvents.DISCONNECTED, "reason=$reason")
        }

        private fun defineOnce(c: PidChannel) {
            if (c.id in defined) return
            val standard = StandardPids.all.indexOfFirst { it.id == c.id }
            val id = if (standard >= 0) ObdChannelBase + standard else ObdChannelOther + defined.size
            val def = ChannelDef(id, "obd_${c.id}", c.unit, "obd", ObdDecimals)
            defined[c.id] = def
            sink.defineChannel(def)
        }
    }

    companion object {
        /** The source of the session recording in this process, if it records OBD. */
        @Volatile
        var current: ObdSource? = null
            private set
    }
}
