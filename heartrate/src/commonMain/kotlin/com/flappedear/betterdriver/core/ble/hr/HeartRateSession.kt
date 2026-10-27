package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.core.JvmSynchronized
import com.flappedear.betterdriver.recorder.SampleSink
import kotlin.concurrent.Volatile

/** A heart rate older than this is not shown as current: the same 5 s the link allows without a value. */
private const val FreshMicros = 5_000_000L

/**
 * A strap that is worn streams within 2 to 7 s of the session start (drives of 20 Oct 2026). One
 * without [WornAfterValues] plausible heart rates in a row by this time is not worn, and is off
 * until the session ends (Arek, 21 Oct 2026: "jak nie wykryjesz na starcie sesji - nie włączasz
 * po prostu", FBBD-285).
 */
const val StartWindowMicros = 45_000_000L
private const val MicrosPerSecond = 1_000_000L

/** Where a heart-rate monitor is, for the track screen's status strip (FBBD-268). */
enum class HeartRateLink { CONNECTING, STREAMING, NO_CONTACT, LOST }

/** A monitor's [link] and its heart rate; [bpm] only while streaming and at most 5 s old. */
data class HeartRateStatus(val link: HeartRateLink, val bpm: Int?)

/** The Heart Rate Service and its Heart Rate Measurement, as 16-bit Bluetooth SIG numbers (FBBD-119). */
object HeartRateIds {
    const val SERVICE = "180D"

    /** Notified, about once a second. */
    const val MEASUREMENT = "2A37"
}

/**
 * What a heart-rate strap link does to a recording, the same on Android (HeartRateSource over its
 * GATT link) and iOS (CoreBluetooth), FBBD-119 and FBBD-333: define the channels, log the strap,
 * decode each notification, log connects and drops, and switch the strap off for the session when
 * no plausible heart rate came in the first [StartWindowMicros] (FBBD-285): a strap that wakes
 * later while not worn (lying on a cable) sends noise, not a heart rate. Then [onOff] runs, once, for
 * the platform to drop its link. The platform link reports [connecting], [streaming] and
 * [reconnecting] as its connection changes, and passes every notification to [feed].
 *
 * [address] names the strap in the session (Android's Bluetooth address, the iOS identifier) and
 * [deviceName] is its advertised name, so a session says which monitor it came from.
 */
class HeartRateSession(
    private val sink: SampleSink,
    private val address: String,
    private val deviceName: String,
    private val onOff: () -> Unit,
) {
    private val decoder = HeartRateDecoder(sink)
    private var startedMicros = 0L

    /** Switched off for the session: no plausible heart rate early on. */
    @Volatile
    var off = false
        private set

    @Volatile
    private var link = HeartRateLink.CONNECTING

    /** Whether data flowed since the last drop; a drop before any data is not a disconnect. */
    private var wasStreaming = false

    fun start() {
        startedMicros = sink.nowMicros()
        decoder.defineChannels()
        sink.event(HeartRateEvents.DEVICE, "address=$address name=$deviceName")
    }

    /**
     * A Heart Rate Measurement notification. One lock with the off check on the JVM, so no value is
     * stored after the monitor was switched off.
     */
    @JvmSynchronized
    fun feed(bytes: ByteArray) {
        if (!offIfNotFound()) decoder.feed(bytes)
    }

    /** The link is connecting, before any data. */
    fun connecting() {
        link = HeartRateLink.CONNECTING
        offIfNotFound()
    }

    /** Data flows from the strap; false when it is off for the session. */
    fun streaming(): Boolean {
        link = HeartRateLink.STREAMING
        if (offIfNotFound()) return false
        if (!wasStreaming) sink.event(HeartRateEvents.CONNECTED, address)
        wasStreaming = true
        return true
    }

    /** The link dropped for [reason]; the link asks again on its own. False when the strap is off. */
    fun reconnecting(reason: String): Boolean {
        link = HeartRateLink.LOST
        if (offIfNotFound()) return false
        if (wasStreaming) sink.event(HeartRateEvents.DISCONNECTED, reason)
        decoder.reset()
        wasStreaming = false
        return true
    }

    /** The strap for the status strip, or null once it is off for the session. */
    fun status(): HeartRateStatus? {
        if (off) return null
        val state = if (link == HeartRateLink.STREAMING && decoder.noContact) HeartRateLink.NO_CONTACT else link
        val fresh = sink.nowMicros() - decoder.lastBpmMicros <= FreshMicros
        val bpm = decoder.lastBpm?.takeIf { state == HeartRateLink.STREAMING && fresh }
        return HeartRateStatus(state, bpm)
    }

    /**
     * Switches the monitor off for the session once [StartWindowMicros] passed without a plausible
     * heart rate; true when it is off. Called from the link and the data callback.
     */
    @JvmSynchronized
    fun offIfNotFound(): Boolean {
        val searching = !off && !decoder.everMeasured
        if (searching && sink.nowMicros() - startedMicros > StartWindowMicros) {
            off = true
            sink.event(HeartRateEvents.OFF, offReason())
            onOff()
        }
        return off
    }

    private fun offReason(): String {
        val sent = if (decoder.decoded == 0L) {
            "sent nothing"
        } else {
            "sent no plausible heart rate (${decoder.implausible} left out)"
        }
        return "$sent in the first ${StartWindowMicros / MicrosPerSecond} s: off for this session"
    }
}
