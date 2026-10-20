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
 * Watches the coach voice's output during a session (FBBD-255): when it changes, such as a headset
 * connected after the session's start, the new output's delay is measured and handed to [onMeasured]
 * on [handler]'s thread. Measuring blocks for a second or more, so it runs on a thread of its own.
 * Each change goes in the continuous log.
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

    /** The session watched and the output last measured for it. [handler]'s thread only. */
    private class Watched(val clips: Boolean, var output: String)

    private var watched: Watched? = null

    private val changed = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = settle()

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = settle()
    }
    private val check = Runnable { check() }

    init {
        audio?.registerAudioDeviceCallback(changed, handler)
    }

    /** Watches a session whose delays were measured on [output], with pre-rendered [clips] on or off. */
    fun watch(clips: Boolean, output: String) {
        watched = Watched(clips, output)
    }

    fun shutdown() {
        watched = null
        handler.removeCallbacks(check)
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
        val now = outputs.output()
        if (now == w.output) return
        w.output = now
        DebugLog.i(Tag, "audio output now $now${outputs.headset()?.let { " ($it)" }.orEmpty()}, measuring its delay")
        probeHandler.post {
            val live = OutputDelayProbe.probe()
            val clip = if (w.clips) OutputDelayProbe.probe(lowLatency = true) else null
            handler.post { if (watched === w) onMeasured(Remeasured(now, w.clips, live, clip)) }
        }
    }

    private companion object {
        const val Tag = "AudioRoute"
        const val SettleMillis = 1_500L
    }
}
