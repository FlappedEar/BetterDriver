package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.track.Track

/** What the track screen's coach line shows (FBBD-98): a corner label and a short text, or nothing. */
data class CoachStatus(val corner: String?, val text: String?) {
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
 * [onFix]'s `say` plays a phrase and returns false if the voice refused it. A dropped or refused cue
 * is logged as a problem, never as a cue, and no result is measured against it. [picks] are the
 * corners the driver picked for this track in the lap analysis (FBBD-148). [sameDay]: the reference
 * has a session driven earlier the same day, so the coach only warms up (FBBD-190). [focus] is the
 * session focus the last analysed session at the track recommends, the primary first (FBBD-20).
 */
class CoachSession(
    settings: CoachSettings,
    track: Track?,
    reference: CoachReference?,
    picks: List<CornerPick> = emptyList(),
    sameDay: Boolean = false,
    focus: List<CornerPick> = emptyList(),
) {
    private val engine = track?.takeIf { coaches(it) && settings.verbosity != Verbosity.Off }
        ?.let { CueEngine(it, settings, reference, picks = picks, sameDay = sameDay, focus = focus) }

    private var lastCue: Cue? = null

    /** The last cue said; until there is one, whether the coach is learning or on. */
    val status: CoachStatus
        get() {
            val e = engine ?: return CoachStatus.None
            val cue = lastCue
            return when {
                cue != null -> CoachStatus(cue.heardTurn?.let { "T$it" }, lineText(cue))
                else -> when (val p = e.phase) {
                    is CoachPhase.Learning -> CoachStatus(null, "Coach learning · ${p.done} of ${p.needed} laps")
                    CoachPhase.Coaching -> CoachStatus(null, "Coach on")
                    else -> CoachStatus.None
                }
            }
        }

    fun onFix(fix: CoachFix, nowMicros: Long, say: (String) -> Boolean): CoachOutput {
        val e = engine ?: return CoachOutput(emptyList(), status)
        val u = e.update(fix)
        val events = mutableListOf<CoachEvent>()
        for (r in u.results) events += CoachEvent(r.tMicros, CoachEvents.RESULT, CoachEvents.resultText(r))
        for (cue in u.cues) {
            val problem = when {
                nowMicros - cue.tMicros > MaxCueAgeMicros -> "late seq=${cue.seq} text=${cue.text}"
                !say(cue.text) -> "no voice ${CoachEvents.cueText(cue)}"
                else -> null
            }
            if (problem == null) {
                events += CoachEvent(cue.tMicros, CoachEvents.CUE, CoachEvents.cueText(cue))
                lastCue = cue
            } else {
                e.unsaid(cue)
                events += CoachEvent(cue.tMicros, CoachEvents.PROBLEM, problem)
            }
        }
        return CoachOutput(events, status)
    }

    /** The cue without "Turn N, ", since the corner is shown beside it: "Brake 15 metres later". */
    private fun lineText(cue: Cue): String {
        val t = if (cue.corner != null) cue.text.substringAfter(", ") else cue.text
        return t.replaceFirstChar { it.uppercase() }
    }
}
