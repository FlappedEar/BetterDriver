package com.flappedear.betterdriver.coach

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recording.RecorderProtocol
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.timing.track.TrackStore

private const val NanosPerMicro = 1_000L

/**
 * The live coach (FBBD-98) in its own process, `:coach`. The recorder binds to it for each recording
 * that has the coach on, so it lives exactly as long as the recording and shares its priority; a coach
 * crash, hang or kill ends only this process, and the recording carries on untouched (FBBD-69).
 *
 * Everything runs on one background thread: loading the track and its reference from earlier sessions
 * (FBBD-26), the cue engine, and asking text-to-speech to say each cue. Cues and their results are sent
 * back to the recorder, which logs them in the session for the cue review (FBBD-28).
 */
class CoachService : Service() {
    private val thread = HandlerThread("coach").apply { start() }
    private val handler = Handler(thread.looper) { msg -> onMessage(msg) }
    private val messenger = Messenger(handler)
    private var voice: Voice? = null

    /** The recording being coached. Coach thread only. */
    private class Active(val id: String, val startNanos: Long, val recorder: Messenger?, val settings: CoachSettings) {
        var coach: CoachSession? = null
        var status: CoachStatus = CoachStatus.None

        fun now() = (System.nanoTime() - startNanos) / NanosPerMicro
    }

    private var active: Active? = null

    override fun onCreate() {
        super.onCreate()
        voice = Voice(this)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        voice?.shutdown()
        voice = null
        super.onDestroy()
    }

    @Suppress("TooGenericExceptionCaught") // A coach failure is logged in the session and never ends coaching.
    private fun onMessage(msg: Message): Boolean {
        try {
            when (msg.what) {
                CoachProtocol.MSG_SESSION -> begin(msg.data, msg.replyTo)
                CoachProtocol.MSG_TRACK -> track(msg.data)
                CoachProtocol.MSG_FIX -> fix(msg.data)
                else -> return false
            }
        } catch (e: Exception) {
            DebugLog.w(Tag, "coach failed", e)
            active?.let { report(it, it.now(), CoachEvents.PROBLEM, "error $e") }
        }
        return true
    }

    private fun begin(data: Bundle, replyTo: Messenger?) {
        val id = data.getString(CoachProtocol.SESSION) ?: return
        val settings = coachSettings(this).read()
        DebugLog.i(Tag, "coaching session $id: $settings")
        active = Active(id, data.getLong(CoachProtocol.START_NANOS), replyTo, settings)
    }

    /** Loads the saved track and its reference; fixes that arrive meanwhile wait, and their late cues are dropped. */
    private fun track(data: Bundle) {
        val a = active ?: return
        val track = data.getString(CoachProtocol.TRACK_ID)?.let { TrackStore(tracksDir(this)).get(it) }
        val reference = track?.takeIf { coaches(it) && a.settings.verbosity >= Verbosity.Focus }?.let {
            coachLibrary(this).reference(it, exclude = a.id)
        }
        // The corners picked in the lap analysis (FBBD-148), read once per session like the settings.
        val picks = track?.takeIf { coaches(it) && a.settings.verbosity >= Verbosity.Focus }
            ?.let { coachPicks(this).get(it.id) }.orEmpty()
        if (picks.isNotEmpty()) DebugLog.i(Tag, "picked corners: ${picks.joinToString { it.name }}")
        a.coach = CoachSession(a.settings, track, reference, picks)
        updateStatus(a, a.coach?.status ?: CoachStatus.None)
    }

    private fun fix(data: Bundle) {
        val a = active ?: return
        val coach = a.coach ?: return
        val speed = data.getDouble(CoachProtocol.SPEED, Double.NaN)
        val fix = CoachFix(
            data.getLong(CoachProtocol.T_US),
            data.getDouble(CoachProtocol.LAT),
            data.getDouble(CoachProtocol.LON),
            speed.takeUnless { it.isNaN() },
        )
        val out = coach.onFix(fix, a.now()) { text -> voice?.say(text) == true }
        for (e in out.events) report(a, e.tMicros, e.kind, e.text)
        updateStatus(a, out.status)
    }

    private fun updateStatus(a: Active, status: CoachStatus) {
        if (status == a.status) return
        a.status = status
        send(a, RecorderProtocol.MSG_COACH_STATUS) {
            status.corner?.let { putString(CoachProtocol.STATUS_CORNER, it) }
            status.text?.let { putString(CoachProtocol.STATUS_TEXT, it) }
        }
    }

    private fun report(a: Active, tMicros: Long, kind: String, text: String) =
        send(a, RecorderProtocol.MSG_COACH_EVENT) {
            putLong(CoachProtocol.T_US, tMicros)
            putString(CoachProtocol.EVENT_KIND, kind)
            putString(CoachProtocol.EVENT_TEXT, text)
        }

    private fun send(a: Active, what: Int, fill: Bundle.() -> Unit) {
        val to = a.recorder ?: return
        val data = Bundle().apply {
            putString(CoachProtocol.SESSION, a.id)
            fill()
        }
        try {
            to.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            // The recorder process is gone; it unbinds or dies, and this service ends with it.
        }
    }

    private companion object {
        const val Tag = "CoachService"
    }
}
