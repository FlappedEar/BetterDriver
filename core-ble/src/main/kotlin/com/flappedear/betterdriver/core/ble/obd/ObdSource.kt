package com.flappedear.betterdriver.core.ble.obd

import android.os.SystemClock
import com.flappedear.betterdriver.obd.CarProfile
import com.flappedear.betterdriver.obd.ObdSession
import com.flappedear.betterdriver.obd.ObdSessionEvents
import com.flappedear.betterdriver.obd.ObdStatus
import com.flappedear.betterdriver.obd.ObdTarget
import com.flappedear.betterdriver.obd.PidChannel
import com.flappedear.betterdriver.obd.PollSet
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink

/** Session events of the OBD-II reader (FBBD-15), shared with the iOS app ([ObdSessionEvents]). */
object ObdEvents {
    /**
     * Text "host=<adapter: host:port, or name and transport> transport=<Wi-Fi|Bluetooth|Bluetooth LE>
     * adapter=<ATI answer> protocol=<ATDPN answer> pids=<supported, hex>".
     */
    const val CONNECTED = ObdSessionEvents.CONNECTED

    /** Text "reason=<why the connection ended>". */
    const val DISCONNECTED = ObdSessionEvents.DISCONNECTED
}

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

    fun status(): ObdStatus? = link?.status()

    override fun start(sink: SampleSink) {
        val l = ObdLink(targets, Listener(sink), { profile }, fallback = fallback, watch = watch, pollSet = pollSet)
        link = l
        current = this
        l.start()
    }

    override fun stop() {
        link?.stop()
        link = null
        if (current === this) current = null
    }

    /** Records what the link reads, as the iOS app does ([ObdSession]). */
    private class Listener(sink: SampleSink) : ObdLinkListener {
        private val session = ObdSession(sink, SystemClock::elapsedRealtime)

        override fun ready(
            target: ObdTarget,
            adapter: String,
            protocol: String,
            supported: Set<Int>,
            channels: List<PidChannel>,
        ) = session.ready(target, adapter, protocol, supported, channels)

        override fun polling(target: ObdTarget, channels: List<PidChannel>) = session.polling(channels)

        override fun value(channel: PidChannel, value: Double, atMs: Long) = session.value(channel, value, atMs)

        override fun ended(target: ObdTarget, reason: String) = session.ended(reason)
    }

    companion object {
        /** The source of the session recording in this process, if it records OBD. */
        @Volatile
        var current: ObdSource? = null
            private set
    }
}
