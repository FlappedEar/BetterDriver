package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/** Session events of the OBD-II reader (FBBD-15). */
object ObdSessionEvents {
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
 * What an OBD-II connection does to a recording, the same on Android (ObdSource) and iOS (FBBD-333):
 * each channel the car answers is defined once as "obd_<id>" with source "obd" (so its provenance is
 * measured OBD), each value is stamped at the time it was read, and connects and drops are session
 * events. [nowMs] is the clock the connector stamps values with (its ObdClock).
 */
class ObdSession(private val sink: SampleSink, private val nowMs: () -> Long) {
    private val defined = HashMap<String, ChannelDef>()

    /** The adapter [target] set up and the car answered: [channels] are recorded from now. */
    fun ready(target: ObdTarget, adapter: String, protocol: String, supported: Set<Int>, channels: List<PidChannel>) {
        val pids = supported.joinToString(",") { hex(it, 2) }
        val how = target.transport.label
        val text = "host=${target.where} transport=$how adapter=$adapter protocol=$protocol pids=$pids"
        sink.event(ObdSessionEvents.CONNECTED, text)
        for (c in channels) defineOnce(c)
    }

    /** Polling switched to [channels] (FBBD-283). */
    fun polling(channels: List<PidChannel>) {
        for (c in channels) defineOnce(c)
    }

    /** [channel] read [value] at [atMs] on [nowMs]'s clock. */
    fun value(channel: PidChannel, value: Double, atMs: Long) {
        val def = defined[channel.id] ?: return
        val ageMicros = (nowMs() - atMs) * MicrosPerMs
        sink.submit(SampleSet(sink.nowMicros() - ageMicros, listOf(ChannelValue(def.id, value))))
    }

    /** A connection that opened has ended, with [reason]. */
    fun ended(reason: String) {
        sink.event(ObdSessionEvents.DISCONNECTED, "reason=$reason")
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
