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
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackStore

private const val NanosPerMicro = 1_000L

/**
 * The live coach (FBBD-98) in its own process, `:coach`. The recorder binds to it for each recording
 * that has the coach on, so it lives exactly as long as the recording and shares its priority; a coach
 * crash, hang or kill ends only this process, and the recording carries on untouched (FBBD-69).
 *
 * Everything runs on one background thread. The coaching itself is the shared [CoachRun], the same as
 * on the iPhone (FBBD-357): loading the track and its reference from earlier sessions (FBBD-26), the
 * cue engine, and its log lines. This service measures the output's delay (FBBD-49, FBBD-255), gives
 * the cues to the [Voice] (it follows playback and renders pre-rendered clips on a thread of its own),
 * and sends cues and their results back to the recorder, which logs them in the session for the cue
 * review (FBBD-28).
 */
class CoachService : Service() {
    private val thread = HandlerThread("coach").apply { start() }
    private val handler = Handler(thread.looper) { msg -> onMessage(msg) }
    private val messenger = Messenger(handler)
    private var voice: Voice? = null

    /** Measures the output again when it changes mid-session (FBBD-255). */
    private var routes: AudioRouteWatch? = null

    /** The recording being coached. Coach thread only. */
    private class Active(val id: String, val startNanos: Long, val recorder: Messenger?) {
        lateinit var run: CoachRun

        fun micros(nanoTime: Long) = (nanoTime - startNanos) / NanosPerMicro

        fun now() = micros(System.nanoTime())
    }

    private var active: Active? = null

    override fun onCreate() {
        super.onCreate()
        voice = Voice(this)
        routes = AudioRouteWatch(this, handler) { r ->
            val run = active?.run ?: return@AudioRouteWatch
            // A route change, not yet measured: what was measured on another device of its type is not its.
            if (r.live == null && r.clip == null) run.delays.forget(r.output)
            r.live?.let { run.delays.measured("live", it.output, it.millis) }
            r.clip?.let { run.delays.measured("clip", it.output, it.millis) }
            run.outputChanged(r.path, r.timing?.output ?: r.output, named = r.output)
        }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        routes?.shutdown()
        routes = null
        thread.quitSafely()
        voice?.shutdown()
        voice = null
        // The process may stay cached after the recording; its later lines, load ones too (FBBD-281),
        // are not the session's.
        DebugLog.session = null
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
            val run = active?.takeIf { it.isStarted() }?.run
            if (run != null) run.failed(e) else DebugLog.w(Tag, "coach failed", e)
        }
        return true
    }

    private fun Active.isStarted() = ::run.isInitialized

    private fun begin(data: Bundle, replyTo: Messenger?) {
        val id = data.getString(CoachProtocol.SESSION) ?: return
        val set = coachSettings(this).read()
        DebugLog.session = id
        // The headset's delay (FBBD-49) is measured now, on the output the cues will play on and on
        // each path they take: live text-to-speech always (clips fall back to it), pre-rendered clips'
        // low-latency path too when they are on. The delay set in the settings is used only when
        // Android gives no audio clock.
        val clips = set.voice == VoiceMode.PreRendered
        // The output as known before measuring: one that changes meanwhile is measured again (FBBD-255).
        val before = routes?.outputs?.device()?.id
        val live = OutputDelayProbe.probe()
        val clip = if (clips) OutputDelayProbe.probe(lowLatency = true) else null
        val a = Active(id, data.getLong(CoachProtocol.START_NANOS), replyTo)
        val library = coachLibrary(this)
        a.run = CoachRun(
            id, set, CoachFolders.under(coachDir(this).path, sessionsDir(this).path),
            object : CoachReferenceSource {
                // The library aims each track's reference at its line plan itself.
                override fun reference(track: Track, exclude: String, plan: PlannedTargets?) =
                    library.reference(track, exclude)

                override fun drivenSameDay(reference: CoachReference, nowUtcMillis: Long) =
                    library.drivenSameDay(reference, nowUtcMillis)
            },
            // A sound of an earlier recording, heard late, is not this one's.
            Speaker(a), a::now, System::currentTimeMillis, { r -> handler.post { if (active === a) r() } },
            { t, kind, text -> report(a, t, kind, text) },
        ) { status -> sendStatus(a, status) }
        live?.let { a.run.delays.measured("live", it.output, it.millis) }
        clip?.let { a.run.delays.measured("clip", it.output, it.millis) }
        // Cues are timed for the path most of them take: the set delay when it was not measured.
        val path = if (clips) "clip" else "live"
        val output = (if (clips) clip else live)?.output ?: routes?.outputs?.output().orEmpty()
        a.run.begin(path, output)
        // Pre-rendered clips (FBBD-48) render now, meanwhile live text-to-speech says the cues.
        voice?.use(set.voice, set.speech, set.language)
        active = a
        routes?.watch(clips, live, clip, before)
    }

    /** Loads the saved track and its reference; fixes that arrive meanwhile wait, and their late cues are dropped. */
    private fun track(data: Bundle) {
        val a = active ?: return
        a.run.start(data.getString(CoachProtocol.TRACK_ID)?.let { TrackStore(tracksDir(this)).get(it) })
    }

    private fun fix(data: Bundle) {
        val a = active ?: return
        val speed = data.getDouble(CoachProtocol.SPEED, Double.NaN)
        a.run.fix(
            CoachFix(
                data.getLong(CoachProtocol.T_US),
                data.getDouble(CoachProtocol.LAT),
                data.getDouble(CoachProtocol.LON),
                speed.takeUnless { it.isNaN() },
                data.getLong(CoachProtocol.FIX_AGE_US, 0L),
                data.getDouble(CoachProtocol.THROTTLE, Double.NaN).takeUnless { it.isNaN() },
                data.getDouble(CoachProtocol.STEERING, Double.NaN).takeUnless { it.isNaN() },
                data.getDouble(CoachProtocol.RPM, Double.NaN).takeUnless { it.isNaN() },
            ),
        )
    }

    /** The [Voice] as the shared coach hears it, on recording [a]'s clock. */
    private inner class Speaker(private val a: Active) : CoachVoiceOut {
        override val speaking: Boolean get() = voice?.speaking == true

        override fun say(text: String, sound: CueSound): Boolean = voice?.say(
            text,
            Ended { nanoTime, end -> sound.ended(a.micros(nanoTime), end) },
        ) { s ->
            sound.started(
                CueSoundStart(a.micros(s.nanoTime), s.path, s.route, s.device, s.readyNanos?.let(a::micros), s.focus),
            )
        } == true
    }

    private fun sendStatus(a: Active, status: CoachStatus) =
        send(a, RecorderProtocol.MSG_COACH_STATUS) {
            status.corner?.let { putString(CoachProtocol.STATUS_CORNER, it) }
            status.text?.let { putString(CoachProtocol.STATUS_TEXT, it) }
            putBoolean(CoachProtocol.STATUS_PRAISE, status.praise)
            putBoolean(CoachProtocol.STATUS_DIM, status.dim)
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
