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

/** What one fix led to: phrases to say now, events to log, and the coach line. */
data class CoachOutput(val say: List<String>, val events: List<CoachEvent>, val status: CoachStatus)

/** A cue asked for more than this long before it could be spoken is dropped: it would land too late. */
const val MaxCueAgeMicros = 400_000L

/**
 * One recording's coach, as the app's coach process runs it (FBBD-98). [track] is the saved track the
 * recorder detected, null if none; without a start/finish line there are no laps and nothing to say.
 * Each fix comes with [onFix]'s `nowMicros`, the session clock when it is handled: a fix that waited
 * in a queue (the reference was still loading) can no longer time a cue, so its cues are dropped.
 */
class CoachSession(settings: CoachSettings, track: Track?, reference: CoachReference?) {
    private val engine = track?.takeIf { it.timeable && settings.verbosity != Verbosity.Off }
        ?.let { CueEngine(it, settings, reference) }

    private var lastCue: Cue? = null

    /** The last cue said; until there is one, whether the coach is learning or on. */
    val status: CoachStatus
        get() {
            val e = engine ?: return CoachStatus.None
            val cue = lastCue
            return when {
                cue != null -> CoachStatus(cue.corner?.let { "T$it" }, lineText(cue))
                else -> when (val p = e.phase) {
                    is CoachPhase.Learning -> CoachStatus(null, "Coach learning · ${p.done} of ${p.needed} laps")
                    CoachPhase.Coaching -> CoachStatus(null, "Coach on")
                    else -> CoachStatus.None
                }
            }
        }

    fun onFix(fix: CoachFix, nowMicros: Long): CoachOutput {
        val e = engine ?: return CoachOutput(emptyList(), emptyList(), status)
        val u = e.update(fix)
        val say = mutableListOf<String>()
        val events = mutableListOf<CoachEvent>()
        for (cue in u.cues) {
            if (nowMicros - cue.tMicros > MaxCueAgeMicros) {
                events += CoachEvent(cue.tMicros, CoachEvents.PROBLEM, "late seq=${cue.seq} text=${cue.text}")
            } else {
                say += cue.text
                events += CoachEvent(cue.tMicros, CoachEvents.CUE, CoachEvents.cueText(cue))
                lastCue = cue
            }
        }
        for (r in u.results) events += CoachEvent(r.tMicros, CoachEvents.RESULT, CoachEvents.resultText(r))
        return CoachOutput(say, events, status)
    }

    /** The cue without "Turn N, ", since the corner is shown beside it: "Brake 15 metres later". */
    private fun lineText(cue: Cue): String {
        val t = if (cue.corner != null) cue.text.substringAfter(", ") else cue.text
        return t.replaceFirstChar { it.uppercase() }
    }
}
