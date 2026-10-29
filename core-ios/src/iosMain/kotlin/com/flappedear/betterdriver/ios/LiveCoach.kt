@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.line.DayPlanStore
import com.flappedear.betterdriver.analysis.line.LinePlanStore
import com.flappedear.betterdriver.coach.AudioDelayUsed
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CoachFix
import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CoachReference
import com.flappedear.betterdriver.coach.CoachReferenceStore
import com.flappedear.betterdriver.coach.CoachSession
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.CoachStatus
import com.flappedear.betterdriver.coach.PlannedTargets
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.coachFixes
import com.flappedear.betterdriver.coach.coaches
import com.flappedear.betterdriver.coach.tracedLaps
import com.flappedear.betterdriver.recorder.log.logInfo
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.StoredSession
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackEvent
import kotlin.concurrent.Volatile
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync

/**
 * The phone's voice, as the app gives it to the coach (FBBD-333): Swift's AVSpeechSynthesizer. [speaking]
 * is true while a phrase still plays, so no cue cuts one off (FBBD-253). [say] starts saying [text] and
 * returns false when it was refused; [speech] then hears when its sound started and ended.
 */
interface CoachSpeaker {
    val speaking: Boolean

    fun say(text: String, speech: CoachSpeech): Boolean
}

/** What became of one phrase; called from any thread. */
interface CoachSpeech {
    /** Its sound started, to [route] ("headset" or "speaker") on output type [device]. */
    fun started(route: String, device: String)

    /** Its sound ended: "done", "stopped" (cut off) or "error". */
    fun ended(end: String)
}

/** The coach settings the iPhone uses (FBBD-333): the Android defaults, in [polish] or English. */
fun coachSettings(polish: Boolean, audioDelayMillis: Int): CoachSettings = CoachSettings(
    language = if (polish) CoachLanguage.Polish else CoachLanguage.English,
    audioDelayMillis = audioDelayMillis.coerceIn(0, CoachSettings.MaxAudioDelayMillis),
)

/**
 * The live coach of one recording on the iPhone (FBBD-333), as the Android app's coach process runs it
 * (CoachService): at the first fix, once the recording knows its place, a saved track with laps gets its
 * reference from earlier sessions there and a [CoachSession], aimed at the corners of the track's line
 * plan (FBBD-306, kept in [linePlansDir] by the review); every fix after that goes to it, and its
 * cues are said by [speaker]. Cues, results and their sounds are logged with [log] as Android's
 * recorder logs them, so the cue review reads them the same.
 *
 * Everything runs on one serial queue of its own: fixes that arrive while the reference loads wait
 * there, and their late cues are dropped, as on Android. A failure is logged as a coach problem and
 * never reaches the recording (FBBD-69). [audioDelayFromPhone]: the settings' delay is the output
 * latency iOS reports for the route at the start, so the cue's VOICE event says "measured"; [audioDelay]
 * changes it when the output changes mid-session (FBBD-354). The debug log gets Android's CoachService
 * and Voice lines, with the same text, so the same checks read both apps' logs (FBBD-355).
 */
@Suppress("LongParameterList") // What one coached recording needs, one parameter each.
class LiveCoach internal constructor(
    private val sessionId: String,
    private val sessionsDir: String,
    private val referencesDir: String,
    private val linePlansDir: String,
    private val settings: CoachSettings,
    private val audioDelayFromPhone: Boolean,
    private val speaker: CoachSpeaker,
    private val now: () -> Long,
    private val log: (tMicros: Long, kind: String, text: String) -> Unit,
) {
    private val queue = dispatch_queue_create("coach", null)

    /** Whether the recording's place has been handed over; the caller's lock is held. */
    private var placed = false
    private var atSavedTrack = false

    /** Coach queue only. */
    private var coach: CoachSession? = null

    /** The output delay cues are timed for now, and whether iOS reported it; coach queue only. */
    private var delayMillis = settings.audioDelayMillis
    private var delayMeasured = audioDelayFromPhone

    /** The coach line, for the track screen. */
    @Volatile
    var status: CoachStatus = CoachStatus.None
        private set

    /** Whether the coach speaks in this session: off in the settings, the line is left out (UI-142 B). */
    val on: Boolean get() = settings.verbosity != Verbosity.Off

    /**
     * A fix of the recording and the place it is at; called with the recording's live lock held, so
     * the fixes come in order. Never blocks: the work is queued.
     */
    internal fun onFix(fix: CoachFix?, detection: TrackDetection?) {
        if (!placed && detection != null) {
            placed = true
            val saved = (detection as? TrackDetection.Saved)?.track
            atSavedTrack = saved != null
            if (saved != null) {
                queued { start(saved) }
            } else if (on) {
                logInfo(Tag, "not at a saved track: no cues this session")
            }
        }
        if (fix != null && atSavedTrack) queued { fix(fix) }
    }

    /**
     * The audio output changed to [output] (FBBD-354), as CoachService's route watch on Android
     * (FBBD-255): cues from now on allow for [millis], the delay iOS reports for it ([measured]) or else
     * the one set.
     */
    fun audioDelay(millis: Int, measured: Boolean, output: String) = queued {
        delayMillis = millis.coerceIn(0, CoachSettings.MaxAudioDelayMillis)
        delayMeasured = measured
        coach?.audioDelay(delayMillis)
        logInfo(Tag, "cues now timed for an audio delay of $delayMillis ms (${source()}) on $output")
    }

    private fun source() = if (delayMeasured) AudioDelayUsed.Measured else AudioDelayUsed.Set

    @Suppress("TooGenericExceptionCaught") // A coach failure is logged in the session and never ends coaching.
    private fun queued(body: () -> Unit) = dispatch_async(queue) {
        try {
            body()
        } catch (t: Throwable) {
            report(now(), CoachEvents.PROBLEM, "error $t")
        }
    }

    /**
     * Loads [track]'s reference and starts the coach there, as CoachService.track: it works on the
     * corners with the most time to find in the track's line plan (FBBD-306, UI-174 A).
     */
    private fun start(track: Track) {
        val focus = coaches(track) && settings.verbosity >= Verbosity.Focus
        val line = if (focus) LinePlanStore(linePlansDir).get(track.id) else null
        val library = IosCoachLibrary(sessionsDir, referencesDir)
        val reference = if (focus) library.reference(track, exclude = sessionId, line?.let(::plannedTargets)) else null
        // Back out after a break the same day (FBBD-190): the coach only warms up before cueing.
        val nowUtc = (NSDate().timeIntervalSince1970 * MillisPerSecond).toLong()
        val sameDay = reference != null && library.drivenSameDay(reference, nowUtc)
        // The first session of a new day there works on the last track day's focus (FBBD-315, UI-214 B),
        // while no session since that day made a newer line plan, as CoachService.planFocusOf.
        val days = DayPlanStore(dayPlansBeside(linePlansDir))
        val day = if (focus && !sameDay) days.days(track.id).lastOrNull() else null
        val fresh = day?.plan?.takeIf { line == null || it.sessionStartUtcMillis >= line.sessionStartUtcMillis }
        val plan = fresh?.let { planFocus(it, sessionId) } ?: line?.let { planFocus(it, sessionId) }
        // Timed for the output the cues play on now: it may have changed since the start (FBBD-354).
        val c = CoachSession(
            settings.copy(audioDelayMillis = delayMillis), track, reference, sameDay = sameDay,
            focus = plan?.picks.orEmpty(), focusSeconds = plan?.seconds.orEmpty(),
        )
        coach = c
        status = c.status
        logStart(track, reference, plan?.picks?.size ?: 0)
        // Which recommendation this session works on, for the coaching history (FBBD-180).
        if (plan != null) {
            val text = CoachEvents.planText(plan.sessionId, plan.cornerIds, plan.seconds.values.sum())
            report(now(), CoachEvents.PLAN, text)
        }
    }

    private fun logStart(track: Track, reference: CoachReference?, corners: Int) {
        val from = reference?.let { "a reference of ${it.sessions.size} sessions" } ?: "no reference yet"
        logInfo(Tag, "coaching at ${track.name}: $from, $corners focus corners, delay $delayMillis ms (${source()})")
    }

    private fun fix(fix: CoachFix) {
        val c = coach ?: return
        var asked: Asked? = null
        // Nothing is said over a phrase still playing (FBBD-253): the cue waits while it still fits.
        val out = c.onFix(fix, now(), voiceFree = !speaker.speaking) { text ->
            val ask = Asked(now(), source()).also { asked = it }
            speaker.say(text, Speech(ask))
        }
        for (e in out.events) report(e.tMicros, e.kind, e.text)
        // At most one cue per fix; its sound start is logged against its number (FBBD-183).
        asked?.seq = out.events.firstOrNull { it.kind == CoachEvents.CUE }
            ?.let { CoachEvents.parseCue(it.tMicros, it.text)?.seq }
        status = out.status
    }

    /**
     * A cue asked of the voice at session time [askedMicros], timed for a delay from [timedSource];
     * [seq] is set once the coach has logged it.
     */
    private class Asked(val askedMicros: Long, val timedSource: String) {
        @Volatile
        var seq: Int? = null
    }

    /** Logs the sound of [ask] on the coach queue, after the fix that asked for it has numbered it. */
    private inner class Speech(private val ask: Asked) : CoachSpeech {
        override fun started(route: String, device: String) {
            val at = now()
            queued {
                val seq = ask.seq ?: return@queued
                // The output's delay now, and what the cue was timed for when it was asked: an output that
                // changed in between does not make the cue's timing "measured".
                val source = source()
                val text = CoachEvents.voiceText(
                    seq, Path, ask.askedMicros, route, device = device, outDelayMillis = delayMillis,
                    outDelaySource = source, timedSource = ask.timedSource,
                )
                report(at, CoachEvents.VOICE, text)
                logInfo(VoiceTag, "$Path voice start ${(at - ask.askedMicros) / MicrosPerMilli} ms")
            }
        }

        override fun ended(end: String) {
            val at = now()
            queued { ask.seq?.let { report(at, CoachEvents.VOICE_END, CoachEvents.voiceEndText(it, end)) } }
        }
    }

    private fun report(tMicros: Long, kind: String, text: String) = log(tMicros, kind, text.take(MaxEventChars))

    /** Waits until the work queued so far is done, for tests. */
    internal fun awaitIdle() = dispatch_sync(queue) {}

    private companion object {
        /** iOS says every cue with live text-to-speech. */
        const val Path = "live"
        /** Android's tags for these lines (CoachService, Voice). */
        const val Tag = "CoachService"
        const val VoiceTag = "Voice"
        const val MicrosPerMilli = 1_000L

        /** The longest coach event text the Android recorder logs. */
        const val MaxEventChars = 1_000
        const val MillisPerSecond = 1_000.0
    }
}

/**
 * The coach references on the iPhone (FBBD-333), one file per track in [referencesDir], kept by the
 * CoachReferenceStore Android uses (`<track id>.coach.json`), built from the sessions in
 * [sessionsDir] that recorded the track. A reference is rebuilt only when those sessions or the
 * track's lines changed.
 */
internal class IosCoachLibrary(sessionsDir: String, referencesDir: String) {
    private val sessions = SessionStore(sessionsDir)
    private val references = CoachReferenceStore(referencesDir)

    /**
     * The reference for [track], aimed at [plan] (FBBD-307), rebuilt first if needed; sessions still
     * recording, and [exclude], are left out.
     */
    fun reference(track: Track, exclude: String, plan: PlannedTargets? = null): CoachReference? {
        val here = sessions.list()
            .filter { it.state != SessionState.RECORDING && it.id != exclude && recordedAt(it) == track.id }
            .map { it.id }
        val current = references.get(track.id)
        val upToDate = current != null && current.fits(track) && current.sessions.toSet() == here.toSet() &&
            current.planKey == plan?.key
        if (upToDate) return current
        val laps = here.mapNotNull { id -> readOrNull(id)?.let { SessionLaps(id, tracedLaps(track, it.coachFixes())) } }
        val built = buildReference(track, laps, plan)
        keep(track.id, built)
        return built
    }

    /** Keeps [built] as [trackId]'s reference; one that cannot be written still coaches this session. */
    @Suppress("TooGenericExceptionCaught")
    private fun keep(trackId: String, built: CoachReference?) {
        try {
            if (built == null) references.delete(trackId) else references.save(built)
        } catch (_: Exception) {
        }
    }

    /** Whether a session [reference] was built from ended within 12 hours before [nowUtcMillis] (FBBD-190). */
    fun drivenSameDay(reference: CoachReference, nowUtcMillis: Long): Boolean {
        val ids = reference.sessions.toSet()
        val ended = sessions.list().filter { it.id in ids }
            .maxOfOrNull { it.info.startUtcMillis + (it.durationMicros ?: 0L) / MicrosPerMilli }
        return ended != null && nowUtcMillis - ended in 0..SameDayMillis
    }

    /** The saved track session [s] recorded it was at, from its events; null if none or unreadable. */
    // A damaged session is skipped, never a reason to stop coaching.
    @Suppress("TooGenericExceptionCaught")
    private fun recordedAt(s: StoredSession): String? = try {
        TrackEvent.savedTrackId(sessions.read(s.id, samples = false).events)
    } catch (_: Exception) {
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readOrNull(id: String) = try {
        sessions.read(id)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MicrosPerMilli = 1_000L
        const val SameDayMillis = 12L * 60 * 60 * 1_000
    }
}
