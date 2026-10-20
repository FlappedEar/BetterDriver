package com.flappedear.betterdriver.coach

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import com.flappedear.betterdriver.recorder.log.DebugLog

/** A new output's delay, measured on the live path and, with pre-rendered [clips] on, the clip path. */
internal class Remeasured(val output: String, val clips: Boolean, val live: Probed?, val clip: Probed?) {
    /** The path most cues take, which they are timed for. */
    val path: String get() = if (clips) "clip" else "live"

    /** The measurement on [path]. */
    val timing: Probed? get() = if (clips) clip else live
}

/**
 * Watches the coach voice's output during a session (FBBD-255): when the device it is routed to
 * changes, such as a headset connected after the session's start or the route switched with every
 * device still connected, the new output's delay is measured and handed to [onMeasured] on
 * [handler]'s thread. Checked when devices connect or go, and every few seconds besides. Measuring
 * blocks for a second or more, so it runs on a thread of its own. Each change goes in the continuous log.
 */
internal class AudioRouteWatch(
    context: Context,
    private val handler: Handler,
    private val onMeasured: (Remeasured) -> Unit,
) {
    private val audio: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)

    /** The outputs, as the coach voice logs them. */
    val outputs = AudioOutputs(audio)

    private val probeThread = HandlerThread("coach-probe").apply { start() }
    private val probeHandler = Handler(probeThread.looper)

    /**
     * The session watched and the output it was last measured on, by the id [AudioOutputs.device]
     * gives it. That is compared only with itself: before Android 13 it is a guess (the first headset
     * connected), and the device a probe was routed to may differ. [handler]'s thread only.
     */
    private class Watched(val clips: Boolean, var deviceId: Int?)

    private var watched: Watched? = null

    private val changed = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = settle()

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = settle()
    }
    private val check = Runnable { check() }
    private val poll = object : Runnable {
        override fun run() {
            if (watched == null) return
            check()
            handler.postDelayed(this, PollMillis)
        }
    }

    init {
        audio?.registerAudioDeviceCallback(changed, handler)
    }

    /**
     * Watches a session with pre-rendered [clips] on or off, whose start [live] and [clip] probes
     * measured one device. If they did not, the output is measured again; after that, when it changes.
     */
    fun watch(clips: Boolean, live: Probed?, clip: Probed?) {
        val measured = isRouted(measuredId(clips, live, clip))
        watched = Watched(clips, if (measured) outputs.device()?.id else NotMeasured)
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, PollMillis)
        if (!measured) settle()
    }

    /**
     * Whether [measured] is the device the voice goes to now: as Android's audio policy says from
     * Android 13 on (else the route changed after the probe); before, which cannot say, any device.
     */
    private fun isRouted(measured: Int?): Boolean {
        val routed = outputs.policyRoute()
        return measured != null && (routed == null || routed.id == measured)
    }

    /** The device both paths the session uses measured, or null if either was not, or they differ. */
    private fun measuredId(clips: Boolean, live: Probed?, clip: Probed?): Int? =
        live?.deviceId?.takeIf { !clips || clip?.deviceId == it }

    fun shutdown() {
        watched = null
        handler.removeCallbacks(check)
        handler.removeCallbacks(poll)
        audio?.unregisterAudioDeviceCallback(changed)
        probeThread.quitSafely()
    }

    /** A Bluetooth headset connects in steps (A2DP, then SCO): the route is measured once it settles. */
    private fun settle() {
        if (watched == null) return
        handler.removeCallbacks(check)
        handler.postDelayed(check, SettleMillis)
    }

    private fun check() {
        val w = watched ?: return
        val device = outputs.device()
        if (device?.id == w.deviceId) return
        val id = device?.id
        w.deviceId = id
        val now = AudioOutputs.typeOf(device)
        DebugLog.i(Tag, "audio output now $now${outputs.headset()?.let { " ($it)" }.orEmpty()}, measuring its delay")
        // Until it is measured, cues are timed for what is known of the new output, as their log says.
        onMeasured(Remeasured(now, w.clips, null, null))
        probeHandler.post {
            val live = OutputDelayProbe.probe()
            val clip = if (w.clips) OutputDelayProbe.probe(lowLatency = true) else null
            handler.post {
                // A result for a route already left behind (it changed again meanwhile) is dropped.
                if (watched === w && w.deviceId == id) {
                    // A path not measured (still settling, no track), the paths measured different devices,
                    // or not the one routed now: the next check measures it again.
                    if (!isRouted(measuredId(w.clips, live, clip))) w.deviceId = NotMeasured
                    onMeasured(Remeasured(now, w.clips, live, clip))
                }
            }
        }
    }

    private companion object {
        const val Tag = "AudioRoute"
        const val SettleMillis = 1_500L

        /** No device has this id: the output routed at the start was not measured. */
        const val NotMeasured = -1

        /** How often the route is checked besides device events: a switch between connected devices has none. */
        const val PollMillis = 5_000L
    }
}
