package com.flappedear.betterdriver.coach

import android.app.Service
import android.content.Context
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
import java.io.File

private const val NanosPerMicro = 1_000L

/**
 * The live coach (FBBD-98) in its own process, `:coach`. The recorder binds to it for each recording
 * that has the coach on, so it lives exactly as long as the recording and shares its priority; a coach
 * crash, hang or kill ends only this process, and the recording carries on untouched (FBBD-69).
 *
 * Everything runs on one background thread: loading the track and its reference from earlier sessions
 * (FBBD-26), the cue engine, and asking the [Voice] to say each cue (it follows playback and renders
 * pre-rendered clips on a thread of its own). Cues and their results are sent
 * back to the recorder, which logs them in the session for the cue review (FBBD-28).
 */
class CoachService : Service() {
    private val thread = HandlerThread("coach").apply { start() }
    private val handler = Handler(thread.looper) { msg -> onMessage(msg) }
    private val messenger = Messenger(handler)
    private var voice: Voice? = null

    /** The recording being coached. Coach thread only. */
    private class Active(
        val id: String,
        val startNanos: Long,
        val recorder: Messenger?,
        val settings: CoachSettings,
        val delay: SessionDelay,
    ) {
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
        val set = coachSettings(this).read()
        DebugLog.session = id
        // The headset's delay (FBBD-49) is measured now, on the output the cues will play on and on
        // each path they take: live text-to-speech always (clips fall back to it), pre-rendered clips'
        // low-latency path too when they are on. The delay set in the settings is used only when
        // Android gives no audio clock.
        val live = OutputDelayProbe.measure()
        val clips = set.voice == VoiceMode.PreRendered
        val clip = if (clips) OutputDelayProbe.measure(lowLatency = true) else null
        val sessionDelay = SessionDelay(live, clip, set.audioDelayMillis)
        // Cues are timed for the path most of them take: the set delay when it was not measured.
        val path = if (clips) "clip" else "live"
        val delay = sessionDelay.on(path, (if (clips) clip else live)?.output.orEmpty())
        val settings = set.copy(audioDelayMillis = delay.millis)
        DebugLog.i(Tag, "coaching session $id: $settings, audio delay ${delay.source}")
        // Pre-rendered clips (FBBD-48) render now, meanwhile live text-to-speech says the cues.
        voice?.use(settings.voice, settings.speech)
        active = Active(id, data.getLong(CoachProtocol.START_NANOS), replyTo, settings, sessionDelay)
    }

    /** Loads the saved track and its reference; fixes that arrive meanwhile wait, and their late cues are dropped. */
    private fun track(data: Bundle) {
        val a = active ?: return
        val track = data.getString(CoachProtocol.TRACK_ID)?.let { TrackStore(tracksDir(this)).get(it) }
        val library = coachLibrary(this)
        val reference = track?.takeIf { coaches(it) && a.settings.verbosity >= Verbosity.Focus }?.let {
            library.reference(it, exclude = a.id)
        }
        // Back out after a break the same day (FBBD-190): the coach only warms up before cueing.
        val sameDay = reference != null && library.drivenSameDay(reference, System.currentTimeMillis())
        // The corners picked in the lap analysis (FBBD-148), read once per session like the settings.
        val picks = track?.takeIf { coaches(it) && a.settings.verbosity >= Verbosity.Focus }
            ?.let { coachPicks(this).get(it.id) }.orEmpty()
        if (picks.isNotEmpty()) DebugLog.i(Tag, "picked corners: ${picks.joinToString { it.name }}")
        // What the last analysed session at the track recommends working on (FBBD-20), primary first.
        val focus = track?.takeIf { coaches(it) && a.settings.verbosity >= Verbosity.Focus }
            ?.let { sessionFocusOf(this, it, a.id) }
        a.coach = CoachSession(a.settings, track, reference, picks, sameDay, focus?.picks.orEmpty())
        // Which recommendation this session works on, for the coaching history (FBBD-180).
        if (focus != null && focus.picks.isNotEmpty()) {
            val corners = focus.record.targets.sortedBy { !it.primary }.mapNotNull { it.cornerId }
            report(a, a.now(), CoachEvents.FOCUS, CoachEvents.focusText(focus.record.sessionId, corners))
        }
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
            data.getLong(CoachProtocol.FIX_AGE_US, 0L),
        )
        var asked: Asked? = null
        val out = coach.onFix(fix, a.now()) { text ->
            val ask = Asked(a.now()).also { asked = it }
            fun micros(nanoTime: Long) = (nanoTime - a.startNanos) / NanosPerMicro
            // Both posted, so they run after this fix: by then the cue's number is known.
            val ended = Ended { nanoTime, end ->
                handler.post {
                    val seq = ask.seq
                    if (seq != null && active === a) {
                        report(a, micros(nanoTime), CoachEvents.VOICE_END, CoachEvents.voiceEndText(seq, end))
                    }
                }
            }
            voice?.say(text, ended) { start ->
                handler.post {
                    val ready = start.readyNanos?.let(::micros)
                    val used = a.delay.on(start.path, start.device)
                    val voiced = ask.voiceText(start, ready, used.millis, used.source)
                    if (voiced != null && active === a) report(a, micros(start.nanoTime), CoachEvents.VOICE, voiced)
                }
            } == true
        }
        for (e in out.events) report(a, e.tMicros, e.kind, e.text)
        // At most one cue per fix; its sound start is logged against its number (FBBD-183).
        asked?.seq = out.events.firstOrNull { it.kind == CoachEvents.CUE }
            ?.let { CoachEvents.parseCue(it.tMicros, it.text)?.seq }
        updateStatus(a, out.status)
    }

    /** A cue asked of the voice at session time [askedMicros]; [seq] is set once the coach has logged it. */
    private class Asked(val askedMicros: Long) {
        var seq: Int? = null

        /** The event text of its sound starting, how it was said and where it went; null if never logged. */
        fun voiceText(start: VoiceStart, readyMicros: Long?, outDelayMillis: Int, outDelaySource: String) = seq?.let {
            CoachEvents.voiceText(
                it, start.path, askedMicros, start.route, readyMicros, start.device, start.focus, outDelayMillis,
                outDelaySource,
            )
        }
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

/**
 * The audio delays measured at a session's start (FBBD-49): on the [live] text-to-speech path and, with
 * pre-rendered clips on, the [clip] path; else the [setMillis] from the settings.
 */
private class SessionDelay(val live: MeasuredDelay?, val clip: MeasuredDelay?, val setMillis: Int) {
    /**
     * The delay of a cue said on [path] ("live" or "clip") to output [device]: the one measured on that
     * path and output, else the set one (a headset connected or gone since was not measured).
     */
    fun on(path: String, device: String): AudioDelayUsed {
        val m = (if (path == "clip") clip else live)?.takeIf { it.output == device }
        return OutputDelay.effective(setMillis, m?.millis)
    }
}

/** A session's next-session focus (FBBD-20) and its corners as coach picks, the primary first. */
private class CoachFocus(val record: SessionFocusRecord, val picks: List<CornerPick>)

/**
 * What the last analysed session at [track] recommends working on (FBBD-20); null when it cannot be
 * read. Never from session [current], nor from a session deleted since (its record goes with it,
 * but a failed delete may leave it), and never the reason the coach does not start.
 */
private fun sessionFocusOf(context: Context, track: Track, current: String): CoachFocus? = try {
    val sessions = sessionsDir(context)
    val f = sessionFocusStore(context).latest(track.id, exclude = current) { File(sessions, it).isDirectory }
    f?.let { r ->
        DebugLog.i("CoachService", "session focus from ${r.sessionId}: ${r.targets.joinToString { it.name }}")
    }
    f?.let { CoachFocus(it, it.picks()) }
} catch (e: IllegalArgumentException) {
    DebugLog.w("CoachService", "session focus at ${track.id} not read", e)
    null
}
