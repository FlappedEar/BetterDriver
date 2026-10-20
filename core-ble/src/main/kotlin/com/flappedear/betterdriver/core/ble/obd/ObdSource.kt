package com.flappedear.betterdriver.core.ble.obd

import android.os.SystemClock
import com.flappedear.betterdriver.obd.CarProfile
import com.flappedear.betterdriver.obd.ObdStatus
import com.flappedear.betterdriver.obd.PidChannel
import com.flappedear.betterdriver.obd.StandardPids
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/** Session events of the OBD-II reader (FBBD-15). */
object ObdEvents {
    /** Text "host=<host:port> adapter=<ATI answer> protocol=<ATDPN answer> pids=<supported, hex>". */
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
 * A Wi-Fi ELM327 OBD-II adapter as a recorder [DataSource] (FBBD-15, first transport for Arek's
 * Wi-Fi adapter). While the session runs an [ObdLink] keeps trying [targets], sets the adapter up,
 * discovers the car's mode 01 PIDs and records every one it answers as a channel "obd_<id>" with
 * source "obd" (so its provenance is measured OBD). A lost adapter or a car switched off never
 * touches the rest of the session.
 *
 * Connects, drops and the per-channel rates go to the session events and the app log (tag "Obd");
 * the running source's [status] is what the OBD page shows during a session (FBBD-261).
 */
class ObdSource(
    private val targets: () -> List<ObdTarget>,
    private val profile: CarProfile = CarProfile.Generic,
) : DataSource {
    override val name = "obd"

    private var link: ObdLink? = null
    private val defined = HashMap<String, ChannelDef>()

    fun status(): ObdStatus? = link?.status()

    override fun start(sink: SampleSink) {
        val l = ObdLink(targets, Listener(sink), profile)
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
            where: String,
            adapter: String,
            protocol: String,
            supported: Set<Int>,
            channels: List<PidChannel>,
        ) {
            val pids = supported.joinToString(",") { "%02X".format(it) }
            sink.event(ObdEvents.CONNECTED, "host=$where adapter=$adapter protocol=$protocol pids=$pids")
            for (c in channels) defineOnce(c)
        }

        override fun value(channel: PidChannel, value: Double, atMs: Long) {
            val def = defined[channel.id] ?: return
            val ageMicros = (SystemClock.elapsedRealtime() - atMs) * MicrosPerMs
            sink.submit(SampleSet(sink.nowMicros() - ageMicros, listOf(ChannelValue(def.id, value))))
        }

        override fun ended(where: String, reason: String) {
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
