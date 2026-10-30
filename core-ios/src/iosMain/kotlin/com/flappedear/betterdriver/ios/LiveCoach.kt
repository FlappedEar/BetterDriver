@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.CoachFix
import com.flappedear.betterdriver.coach.CoachFolders
import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CoachReference
import com.flappedear.betterdriver.coach.CoachReferenceSource
import com.flappedear.betterdriver.coach.CoachReferenceStore
import com.flappedear.betterdriver.coach.CoachRun
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.CoachStatus
import com.flappedear.betterdriver.coach.CoachVoiceOut
import com.flappedear.betterdriver.coach.CueSound
import com.flappedear.betterdriver.coach.CueSoundStart
import com.flappedear.betterdriver.coach.PlannedTargets
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.coachFixes
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
 * The live coach of one recording on the iPhone (FBBD-333): the shared [CoachRun], as the Android app's
 * coach process runs it (FBBD-357), on a serial queue of its own. At the first fix that knows the
 * recording's place it starts there: a saved track with laps gets its reference from earlier sessions
 * and the corners to work on (from the stores beside [linePlansDir], as on Android); every fix after
 * that goes to it, and its cues are said by [speaker]. Cues, results and their sounds are logged with
 * [log] as Android's recorder logs them, so the cue review reads them the same, and the debug log gets
 * the same CoachService lines.
 *
 * Fixes that arrive while the reference loads wait on the queue, and their late cues are dropped, as on
 * Android. A failure is logged as a coach problem and never reaches the recording (FBBD-69).
 * [settings] are as set; the cues are timed for [outputDelayMillis], the output latency iOS reports for
 * [output] at the start (0 when none: the set delay), and [audioDelay] times them for a new output
 * mid-session (FBBD-354).
 */
@Suppress("LongParameterList") // What one coached recording needs, one parameter each.
class LiveCoach internal constructor(
    sessionId: String,
    sessionsDir: String,
    referencesDir: String,
    linePlansDir: String,
    settings: CoachSettings,
    outputDelayMillis: Int,
    output: String,
    speaker: CoachSpeaker,
    private val now: () -> Long,
    log: (tMicros: Long, kind: String, text: String) -> Unit,
) {
    private val queue = dispatch_queue_create("coach", null)

    /** Whether the recording's place has been handed over; the caller's lock is held. */
    private var placed = false

    /**
     * Whether the place is a saved track, the only place the coach has anything to say; the caller's lock
     * is held. Elsewhere the fixes are not queued: 25 a second for a whole road drive did nothing but
     * load the queue (FBBD-390).
     */
    private var atSavedTrack = false

    private val run: CoachRun = CoachRun(
        sessionId, settings, CoachFolders.under(linePlansDir.trimEnd('/').substringBeforeLast('/'), sessionsDir),
        IosCoachLibrary(sessionsDir, referencesDir), Voice(speaker), now,
        { (NSDate().timeIntervalSince1970 * MillisPerSecond).toLong() }, { queued(it) },
        { t, kind, text -> log(t, kind, text.take(MaxEventChars)) },
    ) { status = it }

    /** The coach line, for the track screen. */
    @Volatile
    var status: CoachStatus = CoachStatus.None
        private set

    /** Whether the coach speaks in this session: off in the settings, the line is left out (UI-142 B). */
    val on: Boolean = settings.verbosity != Verbosity.Off

    init {
        queued {
            if (outputDelayMillis > 0) run.delays.measured(Path, output, clamped(outputDelayMillis))
            run.begin(Path, output)
        }
    }

    /**
     * A fix of the recording and the place it is at; called with the recording's live lock held, so
     * the fixes come in order. Never blocks: the work is queued.
     */
    internal fun onFix(fix: CoachFix?, detection: TrackDetection?) {
        if (!placed && detection != null) {
            placed = true
            val saved = (detection as? TrackDetection.Saved)?.track
            atSavedTrack = saved != null
            queued { run.start(saved) }
        }
        if (fix != null && atSavedTrack) queued { run.fix(fix) }
    }

    /**
     * The audio output changed to [output] (FBBD-354), as CoachService's route watch on Android
     * (FBBD-255): cues from now on allow for [millis], the delay iOS reports for it ([measured]), or else
     * the one set.
     */
    fun audioDelay(millis: Int, measured: Boolean, output: String) = queued {
        if (measured) run.delays.measured(Path, output, clamped(millis)) else run.delays.forget(output)
        run.outputChanged(Path, output)
    }

    private fun clamped(millis: Int) = millis.coerceIn(0, CoachSettings.MaxAudioDelayMillis)

    @Suppress("TooGenericExceptionCaught") // A coach failure is logged in the session and never ends coaching.
    private fun queued(body: () -> Unit): Unit = dispatch_async(queue) {
        try {
            body()
        } catch (t: Throwable) {
            run.failed(t)
        }
    }

    /** The iPhone's voice as the shared coach hears it. */
    private inner class Voice(private val speaker: CoachSpeaker) : CoachVoiceOut {
        override val speaking: Boolean get() = speaker.speaking

        override fun say(text: String, sound: CueSound): Boolean = speaker.say(text, Speech(sound, now()))
    }

    /** One phrase's sound, on the session clock; its start is logged with Android's Voice line. */
    private inner class Speech(private val sound: CueSound, private val askedMicros: Long) : CoachSpeech {
        override fun started(route: String, device: String) {
            val at = now()
            sound.started(CueSoundStart(at, Path, route, device))
            logInfo(VoiceTag, "$Path voice start ${(at - askedMicros) / MicrosPerMilli} ms")
        }

        override fun ended(end: String) = sound.ended(now(), end)
    }

    /** Waits until the work queued so far is done, for tests. */
    internal fun awaitIdle() = dispatch_sync(queue) {}

    private companion object {
        /** iOS says every cue with live text-to-speech. */
        const val Path = "live"

        /** Android's tag for the voice's lines. */
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
internal class IosCoachLibrary(sessionsDir: String, referencesDir: String) : CoachReferenceSource {
    private val sessions = SessionStore(sessionsDir)
    private val references = CoachReferenceStore(referencesDir)

    /**
     * The reference for [track], aimed at [plan] (FBBD-307), rebuilt first if needed; sessions still
     * recording, and [exclude], are left out.
     */
    override fun reference(track: Track, exclude: String, plan: PlannedTargets?): CoachReference? {
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
    override fun drivenSameDay(reference: CoachReference, nowUtcMillis: Long): Boolean {
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
