package com.flappedear.betterdriver.core.ble.hr

import android.content.Context
import com.flappedear.betterdriver.core.ble.FoundBleDevice
import com.flappedear.betterdriver.core.ble.GattNotifyLink
import com.flappedear.betterdriver.core.ble.GattNotifySpec
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.NotifyLink
import com.flappedear.betterdriver.core.ble.logLink
import com.flappedear.betterdriver.core.ble.scanBleDevices
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A heart rate older than this is not shown as current: the same 5 s the link allows without a value. */
private const val FreshMicros = 5_000_000L

/**
 * A strap that is worn streams within 2 to 7 s of the session start (drives of 20 Oct 2026). One
 * with no plausible heart rate by this time is not worn, and is off until the session ends (Arek,
 * 21 Oct 2026: "jak nie wykryjesz na starcie sesji - nie włączasz po prostu", FBBD-285).
 */
private const val StartWindowMicros = 45_000_000L
private const val MicrosPerSecond = 1_000_000L

/** Where a heart-rate monitor is, for the track screen's status strip (FBBD-268). */
enum class HeartRateLink { CONNECTING, STREAMING, NO_CONTACT, LOST }

/** A monitor's [link] and its heart rate; [bpm] only while streaming and at most 5 s old. */
data class HeartRateStatus(val link: HeartRateLink, val bpm: Int?)

/** Monitors notify about once a second, so the default 5 s without a value means the link is gone. */
private val HeartRateSpec = GattNotifySpec(HeartRateGatt.SERVICE, HeartRateGatt.MEASUREMENT, "hr-link")

/** Heart rate monitors in range that advertise the Heart Rate Service, strongest first. */
fun scanHeartRateMonitors(context: Context): Flow<List<FoundBleDevice>> =
    scanBleDevices(context) { _, uuids -> isHeartRateAdvertisement(uuids) }

/**
 * A Bluetooth LE heart rate monitor (Heart Rate Service) as a recorder [DataSource] (FBBD-119).
 * The recorder service attaches it to a recording; it connects to the monitor at [address],
 * subscribes to Heart Rate Measurement, keeps reconnecting until stopped, and logs the driver's
 * heart rate and, when the monitor sends them, RR-Intervals and energy ([HeartRateChannels]).
 * Connects and drops are logged as session events. With no plausible heart rate in the first
 * [StartWindowMicros] of the session the monitor is switched off for the rest of it: a strap that
 * wakes later while not worn (lying on a cable) sends noise, not a heart rate (FBBD-285).
 *
 * [deviceName] is the advertised name saved at pairing, logged so a session says which monitor
 * it came from. [connect] makes the link from its two callbacks; tests pass a simulated monitor.
 */
class HeartRateSource internal constructor(
    private val address: String,
    private val deviceName: String,
    private val connect: (onData: (ByteArray) -> Unit, onState: (LinkState) -> Unit) -> NotifyLink,
) : DataSource {
    constructor(context: Context, address: String, deviceName: String) : this(
        address,
        deviceName,
        { onData, onState -> GattNotifyLink(context, address, HeartRateSpec, onData, onState) },
    )

    override val name = "heart_rate"

    @Volatile
    private var link: NotifyLink? = null

    @Volatile
    private var decoder: HeartRateDecoder? = null

    @Volatile
    private var sink: SampleSink? = null

    @Volatile
    private var startedMicros = 0L

    /** Switched off for the session: no plausible heart rate early on. */
    @Volatile
    private var off = false

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    /** The monitor's state for the status strip, or null when stopped or switched off (no link). */
    fun status(): HeartRateStatus? {
        val l = link
        val d = decoder
        val s = sink
        if (l == null || d == null || s == null) return null
        val state = when (l.state.value) {
            is LinkState.Streaming -> if (d.noContact) HeartRateLink.NO_CONTACT else HeartRateLink.STREAMING
            is LinkState.Reconnecting -> HeartRateLink.LOST
            else -> HeartRateLink.CONNECTING
        }
        val fresh = s.nowMicros() - d.lastBpmMicros <= FreshMicros
        val bpm = d.lastBpm?.takeIf { state == HeartRateLink.STREAMING && fresh }
        return HeartRateStatus(state, bpm)
    }

    override fun start(sink: SampleSink) {
        val decoder = HeartRateDecoder(sink)
        this.decoder = decoder
        this.sink = sink
        startedMicros = sink.nowMicros()
        decoder.defineChannels()
        sink.event(HeartRateEvents.DEVICE, "address=$address name=$deviceName")
        // onState runs on the link's one thread, so this needs no lock.
        var wasStreaming = false
        val onData = { bytes: ByteArray -> if (!offIfNotFound()) decoder.feed(bytes) }
        link = connect(onData) { s ->
            if (offIfNotFound()) return@connect
            logLink(address, s, wasStreaming)
            val streaming = s is LinkState.Streaming
            if (streaming && !wasStreaming) sink.event(HeartRateEvents.CONNECTED, address)
            if (s is LinkState.Reconnecting) {
                if (wasStreaming) sink.event(HeartRateEvents.DISCONNECTED, s.reason)
                decoder.reset()
            }
            if (streaming || s is LinkState.Reconnecting) wasStreaming = streaming
        }
        current = this
        link?.start()
    }

    /**
     * Switches the monitor off for the session once [StartWindowMicros] passed without a plausible
     * heart rate; true when it is off. Called from the link's thread and the data callback.
     */
    @Synchronized
    private fun offIfNotFound(): Boolean {
        val d = decoder
        val s = sink
        val searching = !off && d != null && !d.everMeasured
        if (searching && s != null && s.nowMicros() - startedMicros > StartWindowMicros) {
            off = true
            s.event(HeartRateEvents.OFF, offReason(checkNotNull(d)))
            link?.stop()
            link = null
            if (current === this) current = null
        }
        return off
    }

    private fun offReason(d: HeartRateDecoder): String {
        val sent = if (d.decoded == 0L) "sent nothing" else "sent no plausible heart rate (${d.implausible} left out)"
        return "$sent in the first ${StartWindowMicros / MicrosPerSecond} s: off for this session"
    }

    override fun stop() {
        link?.stop()
        link = null
        if (current === this) current = null
    }

    companion object {
        /** The monitor of the session recording in this process, if it records heart rate. */
        @Volatile
        var current: HeartRateSource? = null
            private set
    }
}
