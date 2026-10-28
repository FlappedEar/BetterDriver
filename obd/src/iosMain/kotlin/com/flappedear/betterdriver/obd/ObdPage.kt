package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.setup.CarScanRecord
import com.flappedear.betterdriver.setup.Setup
import kotlin.concurrent.Volatile

/** Where the car data page's own connection puts its values: nowhere, it only shows them. */
object NoSamples : SampleSink {
    override fun defineChannel(def: ChannelDef) = Unit

    override fun submit(set: SampleSet) = Unit

    override fun event(kind: String, text: String) = Unit

    override fun nowMicros(): Long = 0L
}

/** A scan takes about 15 s once connected; past this the adapter or the car is not answering. */
private const val ScanGiveUpMs = 60_000L

/**
 * One scan as the page runs it (UI-303 B), as the Android app's ScanRun: asked for, running since
 * when (monotonic ms), why the last one failed.
 */
internal class ObdScanRun {
    @Volatile
    var asked = false
        private set

    @Volatile
    var since: Long? = null
        private set

    @Volatile
    var note: String? = null
        private set

    private var stored: CarScan? = null

    fun start(nowMs: Long) {
        asked = true
        since = nowMs
        note = null
    }

    /** A new scan in [status], once; one running past [ScanGiveUpMs] gives up and says why. */
    fun check(status: ObdStatus, nowMs: Long): CarScan? {
        val scan = status.scan
        val started = since
        return when {
            scan != null && scan !== stored -> {
                stored = scan
                finish(null)
                scan
            }
            started != null && nowMs - started > ScanGiveUpMs -> {
                finish("The scan did not finish: ${status.detail}. Switch the ignition on and try again.")
                null
            }
            else -> null
        }
    }

    private fun finish(why: String?) {
        asked = false
        since = null
        note = why
    }
}

/** [setup] with [record] stored as car [carId]'s last scan (FBBD-344); unchanged without that car. */
fun withCarScan(setup: Setup, carId: String, record: CarScanRecord): Setup =
    setup.car(carId)?.let { setup.updateCar(it.copy(scan = record)) } ?: setup

/**
 * The car data page's texts (FBBD-261, UI-104 B, UI-106 A), the same as the Android app's ObdScreen:
 * the header's title and line, and the note under the values. [recording]: a session records, and
 * [status] is its connection; [adapter] names the listed adapter, [wifi] whether it is a Wi-Fi one.
 */
object ObdPageText {
    fun title(recording: Boolean, status: ObdStatus): String = when (status.state) {
        ObdLinkState.Connected -> {
            val n = status.readings.size
            "${if (recording) "Recording" else "Connected"} · $n ${if (n == 1) "value" else "values"}"
        }
        ObdLinkState.Connecting -> "Connecting"
        ObdLinkState.Searching -> "No adapter"
    }

    fun detail(status: ObdStatus, adapter: String): String =
        if (status.state == ObdLinkState.Searching) "$adapter not answering · trying again" else status.detail

    fun note(recording: Boolean, status: ObdStatus, adapter: String, wifi: Boolean): String {
        val reach = if (wifi) {
            "join the adapter's Wi-Fi (for example “WiFi_OBDII”) and switch the ignition on"
        } else {
            "switch the ignition on and keep the $adapter in range"
        }
        return when {
            recording && status.state == ObdLinkState.Connected ->
                "Values as the recorder receives them. Only what the car answers is listed."
            recording -> "Recording carries on without OBD. To read the car, $reach; the $adapter is tried " +
                "again every 20 s."
            status.state == ObdLinkState.Connected ->
                "No session is recording, so this page connects to the adapter itself. It lets go when a " +
                    "session starts, and the recorder takes over."
            else -> "${reach.replaceFirstChar { it.uppercase() }}. No session is recording, so this page " +
                "connects to the adapter itself."
        }
    }

    /** A value as the page lists it ("2850 rpm"). */
    fun value(reading: ObdReading): String = reading.valueText()

    /** Its rate ("9.1 Hz"). */
    fun rate(reading: ObdReading): String {
        val tenths = kotlin.math.round(reading.hz * Tenths).toLong()
        return "${tenths / Tenths.toLong()}.${tenths % Tenths.toLong()} Hz"
    }

    private const val Tenths = 10.0
}
