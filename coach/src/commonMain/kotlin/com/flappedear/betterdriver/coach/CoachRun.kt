package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.line.DayPlanStore
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.LinePlanStore
import com.flappedear.betterdriver.analysis.weather.Surface
import com.flappedear.betterdriver.analysis.weather.readWeather
import com.flappedear.betterdriver.analysis.weather.surfaceAt
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.recorder.log.CoreLog
import com.flappedear.betterdriver.recorder.log.logInfo
import com.flappedear.betterdriver.recorder.log.logLine
import com.flappedear.betterdriver.timing.track.Track

/** Where a coached session finds what the review keeps for the coach: one folder per store, as on Android. */
class CoachFolders(
    val sessions: String,
    /** The corners picked in the lap analysis (FBBD-148, CoachPicksStore). */
    val picks: String,
    /** Each analysed session's next-session focus (FBBD-20, SessionFocusStore). */
    val focus: String,
    /** Each track's line plan (FBBD-306, LinePlanStore). */
    val lines: String,
    /** Each track day's plan (FBBD-315, DayPlanStore). */
    val days: String,
) {
    companion object {
        /** The folders under the app's coach folder [coachDir], as both apps keep them. */
        fun under(coachDir: String, sessions: String) = CoachFolders(
            sessions,
            CoreFiles.join(coachDir, "picks"),
            CoreFiles.join(coachDir, "focus"),
            CoreFiles.join(coachDir, "lines"),
            CoreFiles.join(coachDir, "days"),
        )
    }
}

/** The coach references from earlier sessions (FBBD-26), as each app keeps them. */
interface CoachReferenceSource {
    /** The reference for [track], aimed at [plan] (FBBD-307), rebuilt first if needed; [exclude] is left out. */
    fun reference(track: Track, exclude: String, plan: PlannedTargets?): CoachReference?

    /** Whether a session [reference] was built from ended shortly before [nowUtcMillis] (FBBD-190). */
    fun drivenSameDay(reference: CoachReference, nowUtcMillis: Long): Boolean

    /**
     * Whether every session [reference] was built from ended within [SameDayMillis] before
     * [nowUtcMillis]: the driver's first day at the track (FBBD-415). False when unknown.
     */
    fun firstDay(reference: CoachReference, nowUtcMillis: Long): Boolean = false
}

/**
 * The coach's voice as the platform gives it: Android's Voice, the iPhone's AVSpeechSynthesizer.
 * [speaking] is true while a phrase still plays, so no cue cuts one off (FBBD-253). [say] starts
 * saying [text] and returns false when it was refused; [sound] then hears, from any thread, when its
 * sound started and ended.
 */
interface CoachVoiceOut {
    val speaking: Boolean

    fun say(text: String, sound: CueSound): Boolean
}

/** What became of one cue's phrase. */
interface CueSound {
    fun started(start: CueSoundStart)

    /** Its sound ended at session time [tMicros]: "done", "stopped" (cut off) or "error". */
    fun ended(tMicros: Long, end: String)
}

/**
 * A phrase's sound started at session time [tMicros], on [path] ("live" speech or a pre-rendered
 * "clip"), to [route] ("headset" or "speaker") on output type [device]; [readyMicros] when its clip
 * was ready, [focus] whether the voice had audio focus, where the platform knows them.
 */
class CueSoundStart(
    val tMicros: Long,
    val path: String,
    val route: String,
    val device: String,
    val readyMicros: Long? = null,
    val focus: Boolean? = null,
)

/**
 * The live coach of one recording (FBBD-98, FBBD-333), the same on Android and the iPhone (FBBD-357):
 * at the session's start, the output delay its cues are timed for (FBBD-49); once the recording knows
 * its place, a saved track's reference from earlier sessions and the corners to work on: those picked
 * in the review (FBBD-148), then the last track day's plan on a new day there (FBBD-315), the track's
 * line plan (FBBD-306) or the last session's focus (FBBD-20); then every fix, whose cues [voice]
 * says. Cues, results and their sounds go to [report] as session events, and the debug log gets the
 * same CoachService lines on both platforms.
 *
 * Not thread-safe: the platform calls it on one thread or queue of its own, and [post] runs a
 * voice's callbacks there, after the fix that asked for the cue has numbered it. A call that throws
 * is handed to [failed]; it never reaches the recording (FBBD-69).
 */
@Suppress("LongParameterList") // What one coached recording needs, one parameter each.
class CoachRun(
    private val sessionId: String,
    /** The coach settings, with the audio delay as set: the fallback for an output never measured. */
    private val settings: CoachSettings,
    private val folders: CoachFolders,
    private val references: CoachReferenceSource,
    private val voice: CoachVoiceOut,
    /** The session clock, in microseconds. */
    private val now: () -> Long,
    private val nowUtcMillis: () -> Long,
    private val post: (() -> Unit) -> Unit,
    private val report: (tMicros: Long, kind: String, text: String) -> Unit,
    /** Hears the coach line each time it changes. */
    private val onStatus: (CoachStatus) -> Unit = {},
) {
    /** The output delays measured in this session: the platform adds what it measures (FBBD-255). */
    val delays = SessionDelays(settings.audioDelayMillis)

    /** The delay cues are timed for, of the output and path ("live" or "clip") they play on now. */
    private var timing = AudioDelayUsed(settings.audioDelayMillis, AudioDelayUsed.Set)
    private var timingOutput = ""
    private var timingPath = ""

    private var coach: CoachSession? = null
    private val stores = CoachStores(folders, sessionId)

    /** The coach line, for the track screen. */
    var status: CoachStatus = CoachStatus.None
        private set

    /** Whether the coach speaks in this session: off in the settings, the line is left out (UI-142 B). */
    val on: Boolean get() = settings.verbosity != Verbosity.Off

    /** The session starts with cues timed for [output] on [path], as measured into [delays] so far. */
    fun begin(path: String, output: String) {
        timedFor(path, output)
        val used = settings.copy(audioDelayMillis = timing.millis)
        logInfo(Tag, "coaching session $sessionId: $used, audio delay ${timing.source} on $output")
    }

    /**
     * The output changed mid-session (FBBD-255, FBBD-354): cues from now on are timed for [output] on
     * [path], with what [delays] has for it; [named] is the output as the log names it.
     */
    fun outputChanged(path: String, output: String, named: String = output) {
        timedFor(path, output)
        coach?.audioDelay(timing.millis)
        logInfo(Tag, "cues now timed for an audio delay of ${timing.millis} ms (${timing.source}) on $named")
    }

    private fun timedFor(path: String, output: String) {
        timing = delays.on(path, output)
        timingOutput = output
        timingPath = path
    }

    /**
     * The recording is at [track], a saved one, or at none: the coach starts there with its reference
     * and the corners to work on. Fixes before it are not coached.
     */
    fun start(track: Track?) {
        val coached = track?.takeIf { coaches(it) && settings.verbosity >= Verbosity.Focus }
        val line = coached?.let(stores::linePlan)
        val planned = line?.let(::plannedTargets)
        val reference = coached?.let { drivenOnFirstDay(it, planned, references.reference(it, sessionId, planned)) }
        // Back out after a break the same day (FBBD-190): the coach only warms up before cueing.
        val sameDay = reference != null && references.drivenSameDay(reference, nowUtcMillis())
        // The corners picked in the lap analysis (FBBD-148), read once per session like the settings.
        val picks = coached?.let(stores::picks).orEmpty()
        // The corners with the most time to find in the track's line plan (FBBD-306, UI-174 A), else what
        // the last analysed session at the track recommends working on (FBBD-20), primary first.
        val plan = coached?.let { stores.focus(it, line, sameDay) }
        val focus = coached?.takeIf { plan == null }?.let(stores::sessionFocus)
        val focusPicks = plan?.picks ?: focus?.picks().orEmpty()
        // Timed for the output the cues play on now: it may have changed since the start (FBBD-255).
        val c = CoachSession(
            settings.copy(audioDelayMillis = timing.millis), track, reference, picks, sameDay, focusPicks,
            plan?.seconds.orEmpty(),
        )
        coach = c
        if (on) logStart(track, reference, focusPicks.size)
        // Which recommendation this session works on, for the coaching history (FBBD-180).
        if (plan != null) {
            val text = CoachEvents.planText(plan.sessionId, plan.cornerIds, plan.seconds.values.sum())
            report(now(), CoachEvents.PLAN, text)
        } else if (focus != null && focusPicks.isNotEmpty()) {
            val corners = focus.targets.sortedBy { !it.primary }.mapNotNull { it.cornerId }
            report(now(), CoachEvents.FOCUS, CoachEvents.focusText(focus.sessionId, corners))
        }
        updateStatus(c.status)
    }

    /**
     * [reference], or on a first day at [track] (FBBD-415) the reference aimed at [planned]'s driven
     * targets only: no braking point beyond his best is asked for there.
     */
    private fun drivenOnFirstDay(track: Track, planned: PlannedTargets?, reference: CoachReference?): CoachReference? {
        if (reference == null || planned?.beyondBest != true || !references.firstDay(reference, nowUtcMillis())) {
            return reference
        }
        logInfo(Tag, "first day at ${track.name}: no braking points beyond his best this session")
        return references.reference(track, sessionId, planned.drivenOnly())
    }

    private fun logStart(track: Track?, reference: CoachReference?, focusCorners: Int) {
        if (track == null) {
            logInfo(Tag, "not at a saved track: no cues this session")
            return
        }
        val from = reference?.let { "a reference of ${it.sessions.size} sessions" } ?: "no reference yet"
        logInfo(
            Tag,
            "coaching at ${track.name}: $from, $focusCorners focus corners, " +
                "delay ${timing.millis} ms (${timing.source})",
        )
    }

    /** When the session's weather was last read, on the session clock; null before the first read. */
    private var weatherReadMicros: Long? = null

    /** The surface the coach went by at the last read (FBBD-403); null while the weather is not known. */
    private var surface: Surface? = null

    /**
     * The weather the recording keeps in the session's folder (FBBD-403), read once a minute: on a damp
     * or wet track the coach stops its corner cues, which come from dry laps, and says lap times only.
     */
    @Suppress("ReturnCount") // Each early return leaves the coach as it is: not due, no weather, no change.
    private fun checkWeather(c: CoachSession) {
        val t = now()
        val last = weatherReadMicros
        if (last != null && t - last < WeatherReadMicros) return
        weatherReadMicros = t
        val weather = readWeather(CoreFiles.join(folders.sessions, sessionId)) ?: return
        val utc = nowUtcMillis()
        val now = weather.surfaceAt(utc) ?: return
        if (now == surface) return
        surface = now
        c.conditions(wet = now != Surface.Dry)
        val air = weather.at(utc)?.airC?.let { fixed(it, 1) } ?: "na"
        report(t, CoachEvents.CONDITIONS, "surface=${now.text} air_c=$air")
        val cues = if (now == Surface.Dry) "corner cues on" else "lap times only"
        logInfo(Tag, "track ${now.text} by the weather: $cues")
    }

    /** A fix of the recording; its cue, if any, is said now. */
    fun fix(fix: CoachFix) {
        val c = coach ?: return
        if (on) checkWeather(c)
        var asked: Asked? = null
        // Nothing is said over a phrase still playing (FBBD-253): the cue waits while it still fits.
        val out = c.onFix(fix, now(), voiceFree = !voice.speaking) { text ->
            val ask = Asked(now(), timing.source, timingOutput, timingPath).also { asked = it }
            voice.say(text, Sound(ask))
        }
        for (e in out.events) report(e.tMicros, e.kind, e.text)
        // At most one cue per fix; its sound start is logged against its number (FBBD-183).
        asked?.seq = out.events.firstOrNull { it.kind == CoachEvents.CUE }
            ?.let { CoachEvents.parseCue(it.tMicros, it.text)?.seq }
        updateStatus(out.status)
    }

    /** A call to the coach threw [e]: logged in the session as a coach problem, and coaching goes on. */
    fun failed(e: Throwable) {
        logLine(CoreLog.LevelWarn, Tag, "coach failed", e)
        report(now(), CoachEvents.PROBLEM, "error $e")
    }

    private fun updateStatus(s: CoachStatus) {
        if (s == status) return
        status = s
        onStatus(s)
    }

    /**
     * A cue asked of the voice at session time [askedMicros], timed for a delay of [output] on [path]
     * that was [source] ("measured" or "set"); [seq] is set once the coach has logged it.
     */
    private class Asked(val askedMicros: Long, val source: String, val output: String, val path: String) {
        var seq: Int? = null
    }

    /** Logs the sound of [ask] on the coach's thread, after the fix that asked for it has numbered it. */
    private inner class Sound(private val ask: Asked) : CueSound {
        override fun started(start: CueSoundStart) = post {
            val seq = ask.seq ?: return@post
            // The output's delay now; and what the cue was timed for, unless its sound went another way
            // than it was timed for: the route changed meanwhile, or a clip was not ready and live speech said it.
            val used = delays.on(start.path, start.device)
            val timed = when {
                start.device != ask.output -> OtherOutput
                start.path != ask.path -> OtherPath
                else -> ask.source
            }
            val text = CoachEvents.voiceText(
                seq, start.path, ask.askedMicros, start.route, start.readyMicros, start.device, start.focus,
                used.millis, used.source, timed,
            )
            report(start.tMicros, CoachEvents.VOICE, text)
        }

        override fun ended(tMicros: Long, end: String) = post {
            val seq = ask.seq ?: return@post
            report(tMicros, CoachEvents.VOICE_END, CoachEvents.voiceEndText(seq, end))
            // Cut off or failed (FBBD-416): not heard, so nothing it asked for is judged.
            if (end != CoachEvents.VoiceDone) {
                coach?.unheard(seq)
                report(tMicros, CoachEvents.PROBLEM, CoachEvents.cutOffText(seq, end))
            }
        }
    }

    private companion object {
        /** Android's tag for these lines, kept on both platforms so one set of checks reads both logs. */
        const val Tag = "CoachService"

        /** timed_src of a cue whose sound went to another output than it was timed for (FBBD-255). */
        const val OtherOutput = "other_output"

        /** timed_src of a cue said on another path (live, clip) than it was timed for (FBBD-255). */
        const val OtherPath = "other_path"

        /** How often the session's weather is read: it changes every 15 minutes at most. */
        const val WeatherReadMicros = 60_000_000L
    }
}

/** What the review keeps for the coach of session [sessionId], read from [folders] (FBBD-357). */
private class CoachStores(private val folders: CoachFolders, private val sessionId: String) {
    fun linePlan(track: Track): LinePlan? =
        readOrNull("line plan", track) { LinePlanStore(folders.lines).get(track.id) }

    fun picks(track: Track): List<CornerPick> {
        val picks = readOrNull("coach picks", track) { CoachPicksStore(folders.picks).get(track.id) }.orEmpty()
        if (picks.isNotEmpty()) logInfo(Tag, "picked corners: ${picks.joinToString { it.name }}")
        return picks
    }

    /**
     * The focus of [track]'s line plan [line] (FBBD-306, UI-174 A): the corners with the most time to
     * find over every session there, as the Map tab shows them. Out on a new day there (not [sameDay]),
     * the last track day's plan comes first (FBBD-315, UI-214 B) while no session since that day made a
     * newer line plan: the first session of the day works on the day's corners, and the sessions after
     * it on what the session before them found (UI-190 B).
     */
    fun focus(track: Track, line: LinePlan?, sameDay: Boolean): PlanFocus? {
        if (!sameDay) {
            val day = readOrNull("day plan", track) { DayPlanStore(folders.days).days(track.id).lastOrNull()?.plan }
            val fresh = day?.takeIf { line == null || it.sessionStartUtcMillis >= line.sessionStartUtcMillis }
            fresh?.let { focusOf(it, "day plan") }?.let { return it }
        }
        return line?.let { focusOf(it, "line plan") }
    }

    private fun focusOf(plan: LinePlan, what: String): PlanFocus? = planFocus(plan, sessionId)?.also { f ->
        logInfo(Tag, "$what focus from ${plan.sessionId}: ${f.picks.joinToString { it.name }}")
    }

    /**
     * What the last analysed session at [track] recommends working on (FBBD-20); never from this
     * session, nor from a session deleted since (its record goes with it, but a failed delete may
     * leave it).
     */
    fun sessionFocus(track: Track): SessionFocusRecord? = readOrNull("session focus", track) {
        SessionFocusStore(folders.focus).latest(track.id, exclude = sessionId) {
            CoreFiles.isDirectory(CoreFiles.join(folders.sessions, it))
        }
    }?.also { r -> logInfo(Tag, "session focus from ${r.sessionId}: ${r.targets.joinToString { it.name }}") }

    /** What [read] gives; a store that cannot be read counts as empty, never the reason the coach does not start. */
    private fun <T> readOrNull(what: String, track: Track, read: () -> T?): T? = try {
        read()
    } catch (e: IllegalArgumentException) {
        logLine(CoreLog.LevelWarn, Tag, "$what at ${track.id} not read", e)
        null
    }

    private companion object {
        const val Tag = "CoachService"
    }
}
