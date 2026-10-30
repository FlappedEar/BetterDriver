package com.flappedear.betterdriver.coach

/** The colour of a cue's verdict in the review: plain (dim), gain (green) or loss (red). */
enum class CueVerdictTone { Plain, Gain, Loss }

/**
 * One cue card of the coach review (FBBD-28, FBBD-99), in words, so Android and the iPhone show the
 * same: the [place] it was said ("Lap 5 · Turn 2"), the [verdict] on the right in [tone], the cue's words
 * in quotes, how far it could be trusted and what the next pass did, each null when there is none.
 */
data class CueCard(
    val place: String,
    val verdict: String,
    val tone: CueVerdictTone,
    val quote: String,
    val confidence: String?,
    val outcome: String?,
)

/** This cue as its review card shows it: a corner cue is Better or Not yet once the corner was driven again. */
fun ReviewedCue.card(): CueCard {
    val (verdict, tone) = when {
        cue.kind == CueKind.LapTime -> "Lap time" to CueVerdictTone.Plain
        cue.kind == CueKind.Praise -> "Praise" to CueVerdictTone.Gain
        result == null -> "No pass after" to CueVerdictTone.Plain
        result.improved -> "Better" to CueVerdictTone.Gain
        else -> "Not yet" to CueVerdictTone.Loss
    }
    val place = listOfNotNull("Lap ${cue.lap}", cue.heardTurn?.let { "Turn $it" }).joinToString(" · ")
    return CueCard(place, verdict, tone, "“${cue.text}”", cue.confidenceText(), outcome())
}

/** "1 cue not said (too late or no voice)", or for more "3 cues not said …"; null when every cue was said. */
val CueReview.notSaid: String?
    get() = when (problems) {
        0 -> null
        1 -> "1 cue not said (too late or no voice)"
        else -> "$problems cues not said (too late or no voice)"
    }
