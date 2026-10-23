package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.track.Track

/**
 * What the track screen's coach line shows (FBBD-98): a corner label and a short text, or nothing.
 * [praise] when it shows a praise (FBBD-243) or a cue that worked (UI-173 A), drawn green; [dim] for
 * a cue not done yet (UI-173 A), drawn grey.
 */
data class CoachStatus(val corner: String?, val text: String?, val praise: Boolean = false, val dim: Boolean = false) {
    companion object {
        val None = CoachStatus(null, null)
    }
}

/** A session event the coach asks the recorder to log. */
data class CoachEvent(val tMicros: Long, val kind: String, val text: String)

/** What one fix led to: events to log, and the coach line. */
data class CoachOutput(val events: List<CoachEvent>, val status: CoachStatus)

/**
 * Whether the coach works at [track]: it needs laps, so a start/finish line. Point-to-point tracks
 * (FBBD-155) are left out for now: the coach learns corners from laps that close on themselves.
 */
fun coaches(track: Track): Boolean = track.timeable && !track.pointToPoint

/** A cue asked for more than this long before it could be spoken is dropped: it would land too late. */
const val MaxCueAgeMicros = 400_000L

/**
 * One recording's coach, as the app's coach process runs it (FBBD-98). [track] is the saved track the
 * recorder detected, null if none; without a start/finish line there are no laps and nothing to say.
 * Each fix comes with [onFix]'s `nowMicros`, the session clock when it is handled: a fix that waited
 * in a queue (the reference was still loading) can no longer time a cue, so its cues are dropped.
 * [onFix]'s `say` plays a phrase and returns false if the voice refused it; its `voiceFree` is false
 * while the voice is still saying a phrase, so no cue cuts one off (FBBD-253). A dropped or refused cue
 * is logged as a problem, never as a cue, and no result is measured against it. [picks] are the
 * corners the driver picked for this track in the lap analysis (FBBD-148). [sameDay]: the reference
 * has a session driven earlier the same day, so the coach only warms up (FBBD-190). [focus] is the
 * session focus the last analysed session at the track recommends, the primary first (FBBD-20), or
 * the line plan's focus (FBBD-306) with [focusSeconds], its time to find in each by CornerId.
 */
@Suppress("LongParameterList") // What the coach knows at the start of a session, one parameter each.
class CoachSession(
    settings: CoachSettings,
    track: Track?,
    reference: CoachReference?,
    picks: List<CornerPick> = emptyList(),
    sameDay: Boolean = false,
    focus: List<CornerPick> = emptyList(),
    focusSeconds: Map<String, Double> = emptyMap(),
) {
    private val engine = track?.takeIf { coaches(it) && settings.verbosity != Verbosity.Off }
        ?.let {
            CueEngine(
                it, settings, reference, picks = picks, sameDay = sameDay, focus = focus, focusSeconds = focusSeconds,
            )
        }

    /** The coach line follows the language the cues are said in (FBBD-245). */
    private val lines = CoachLines(PhraseBook.of(settings.language))

    /** The coach line since the first cue: the last cue, or what came of it (UI-173 A). */
    private var line: CoachStatus? = null

    /** Why the coach was quiet at the last fix (FBBD-202), so each new quiet spell is logged once. */
    private var quiet: String? = null

    /** The lap the coach went quiet on, so its end on that same lap is logged as a resume (FBBD-254). */
    private var quietLap = 0

    /** The last cue said; until there is one, whether the coach is learning or on. */
    val status: CoachStatus
        get() {
            val e = engine ?: return CoachStatus.None
            return line ?: when (val p = e.phase) {
                is CoachPhase.Learning -> CoachStatus(null, "Coach learning · ${p.done} of ${p.needed} laps")
                CoachPhase.Coaching -> CoachStatus(null, "Coach on")
                else -> CoachStatus.None
            }
        }

    /** The audio output changed (FBBD-255): cues from now on allow for its delay, [millis]. */
    fun audioDelay(millis: Int) {
        engine?.audioDelayMillis = millis
    }

    fun onFix(fix: CoachFix, nowMicros: Long, voiceFree: Boolean = true, say: (String) -> Boolean): CoachOutput {
        val e = engine ?: return CoachOutput(emptyList(), status)
        val u = e.update(fix, voiceFree)
        val events = mutableListOf<CoachEvent>()
        val reason = e.quietReason
        val was = quiet
        if (reason != null && reason != was) {
            events += CoachEvent(fix.tMicros, CoachEvents.QUIET, CoachEvents.quietText(e.lapNumber, reason))
            quietLap = e.lapNumber
        } else if (reason == null && was != null && e.lapNumber == quietLap) {
            // Quiet ended before the line: back on this lap, not the end of the lap clearing it.
            events += CoachEvent(fix.tMicros, CoachEvents.RESUMED, CoachEvents.resumedText(quietLap, was))
        }
        quiet = reason
        for (r in u.results) {
            events += CoachEvent(r.tMicros, CoachEvents.RESULT, CoachEvents.resultText(r))
            line = lines.result(r)
        }
        for (cue in u.cues) {
            val problem = when {
                nowMicros - cue.tMicros > MaxCueAgeMicros -> "late seq=${cue.seq} text=${cue.text}"
                !say(cue.text) -> "no voice ${CoachEvents.cueText(cue)}"
                else -> null
            }
            if (problem == null) {
                events += CoachEvent(cue.tMicros, CoachEvents.CUE, CoachEvents.cueText(cue))
                line = lines.cue(cue, line, e.plannedSeconds)
            } else {
                e.unsaid(cue)
                events += CoachEvent(cue.tMicros, CoachEvents.PROBLEM, problem)
            }
        }
        return CoachOutput(events, status)
    }
}

/** The coach line's words for each cue and its result (FBBD-98, UI-173 A, UI-174 A), in [phrases]' language. */
class CoachLines(private val phrases: PhraseBook) {
    /**
     * The coach line for [cue], with [shown] on it now. A “that worked” praise keeps the result shown
     * for its corner (UI-173 A: the line shows what was done, the voice says it worked); the plan cue
     * shows its corners and the time to find there, [plannedSeconds] (UI-174 A).
     */
    fun cue(cue: Cue, shown: CoachStatus?, plannedSeconds: Double?): CoachStatus {
        val turn = cue.heardTurn?.let { "T$it" }
        return when {
            cue.kind == CueKind.Praise && cue.praise == Praise.Worked && shown != null && shown.praise &&
                shown.corner == turn -> shown
            cue.kind == CueKind.Plan -> CoachStatus(null, phrases.planLine(cue.turns, plannedSeconds))
            else -> CoachStatus(turn, lineText(cue), cue.kind == CueKind.Praise)
        }
    }

    /**
     * What came of a corner cue, right after its corner (UI-173 A): what the pass moved against the
     * pass before the cue and the time it gave, green when it did what was asked and gained, grey
     * when not done yet. Numbers only when the cue and the pass are both trusted (FBBD-177).
     */
    fun result(r: CueResult): CoachStatus {
        val cue = r.cue
        val before = cue.before
        val moved = cue.asked.map { a -> Moved(a, before?.let { movedBy(a, it, r.after) }) }
        val precise = cue.confidence.precise && r.confidence.precise
        val gain = (r.gainMicros / MicrosPerSecond).takeIf { precise }
        val text = phrases.result(moved, r.improved, gain)
        val praise = r.improved && r.gainMicros > 0
        return CoachStatus(cue.heardTurn?.let { "T$it" }, text, praise = praise, dim = !r.improved)
    }

    /**
     * The cue without "Turn N, ", since the corner is shown beside it: "Brake at 100 m · 10 later"
     * ([PhraseBook.line]), or the words said: "Brake a little later", "That worked". A cue of two
     * shows each part as its own line would (UI-170 B).
     */
    private fun lineText(cue: Cue): String {
        val single = cue.advice?.let(phrases::line)
        return when {
            cue.also != null -> cue.asked.joinToString(" \u00b7 ") { phrases.line(it) ?: capital(phrases.words(it)) }
            single != null -> single
            else -> capital(if (cue.corner != null) cue.text.substringAfter(", ") else cue.text)
        }
    }

    private fun capital(text: String) = text.replaceFirstChar(Char::uppercase)

    private companion object {
        const val MicrosPerSecond = 1_000_000.0
    }
}

/**
 * How far [after] moved the way [a] asks from [before]: metres tighter at the apex, metres earlier on
 * throttle, metres later braking, km/h more at the apex; null when not measured.
 */
fun movedBy(a: Advice, before: CornerPass, after: CornerPass): Double? = when (a) {
    is Advice.Line -> tighterBy(before, after, a.turn)
    is Advice.Throttle -> before.pickupAt?.let { b -> after.pickupAt?.let { b - it } }
    is Advice.BrakeLater, is Advice.BrakeEarlier -> after.brakeAt - before.brakeAt
    is Advice.CarrySpeed -> after.minKmh - before.minKmh
}
