package com.flappedear.betterdriver.recording

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.metrics.performance.JankStats
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.FrameStretch
import java.util.WeakHashMap

/**
 * What the UI process puts in the drive's log for the hardware checks (FBBD-183), on the session
 * clock it shares with the recorder (System.nanoTime from the session's start): the GNSS banner's
 * changes (FBBD-64) and the screen's frames (FBBD-41, FBBD-62). Lines go through the log's own
 * writer thread, so nothing here touches the disk on the main thread.
 */
object UiChecks {
    private class Live(val session: String, val startNanos: Long)

    @Volatile
    private var live: Live? = null
    private var bannerShown = false
    private val frames = FrameStretch()

    /** One JankStats per activity window. Main thread only. */
    private val trackers = WeakHashMap<ComponentActivity, JankStats>()

    /** Called with each recorder state, on the main thread. */
    fun follow(snapshot: LiveSnapshot?, startNanos: Long?) {
        val running = snapshot?.takeIf { it.stoppedReason == null }
        val now = if (running != null && startNanos != null) Live(running.sessionId, startNanos) else null
        if (now?.session != live?.session) {
            frames.flush()?.let { event(EventKinds.UI_FRAMES, it.text, it.endNanos) }
            bannerShown = false
        }
        live = now
        val shown = running?.gnss == GnssState.Lost
        if (running != null && shown != bannerShown) {
            bannerShown = shown
            val state = if (shown) "shown" else "cleared"
            event(EventKinds.GNSS_BANNER, "state=$state fix_age_us=${running.lastFixAgeMicros ?: -1}")
        }
    }

    /**
     * Counts [activity]'s frames while [recording], logging a stretch every 30 s; a new activity
     * (the track screen turns) carries on the same stretch. Main thread.
     */
    fun trackFrames(activity: ComponentActivity, recording: Boolean) {
        val stats = trackers[activity] ?: if (recording) startTracking(activity) else null
        stats?.isTrackingEnabled = recording
        if (!recording) frames.flush()?.let { event(EventKinds.UI_FRAMES, it.text, it.endNanos) }
    }

    private fun startTracking(activity: ComponentActivity): JankStats {
        val stats = JankStats.createAndTrack(activity.window) { frame ->
            frames.add(frame.frameStartNanos, frame.frameDurationUiNanos, frame.isJank)
                ?.let { event(EventKinds.UI_FRAMES, it.text, it.endNanos) }
        }
        trackers[activity] = stats
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_DESTROY) {
                    stats.isTrackingEnabled = false
                    trackers.remove(activity)
                }
            },
        )
        return stats
    }

    /**
     * Sends an event to the recorder, to record in its session (RecorderClient sets it while bound).
     * Any thread.
     */
    @Volatile
    var recorder: ((Bundle) -> Unit)? = null

    /** Logs an event of the running recording at [atNanos] (System.nanoTime); nothing outside a recording. */
    private fun event(kind: String, text: String, atNanos: Long = System.nanoTime()) {
        val l = live ?: return
        report(l.session, (atNanos - l.startNanos) / NanosPerMicro, kind, text)
    }

    /**
     * Logs an event of recording [session] at its session time [tMicros] in the drive's log, and sends
     * it to the recorder for the session's own report; the recorder keeps it only while that session
     * runs. Any thread.
     */
    fun report(session: String, tMicros: Long, kind: String, text: String) {
        CheckLog.event(session, tMicros, kind, text)
        recorder?.invoke(
            Bundle().apply {
                putString(RecorderProtocol.UI_SESSION, session)
                putLong(RecorderProtocol.UI_T_US, tMicros)
                putString(RecorderProtocol.UI_KIND, kind)
                putString(RecorderProtocol.UI_TEXT, text)
            },
        )
    }

    private const val NanosPerMicro = 1_000L
}
