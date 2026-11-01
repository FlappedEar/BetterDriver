package com.flappedear.betterdriver.analysis.optimal

import com.flappedear.betterdriver.feature.analysis.lapsText

// The composite lap video under the lap times of the lap analysis (FBBD-163, FBBD-178), as both
// apps offer it (iOS FBBD-370): the button, the line beside it and the sheet's texts. Shared so the
// iPhone, the iPad and Android name and explain it the same way.

/**
 * The two composite laps of a session as the lap analysis offers them (FBBD-178): the [realistic]
 * optimal, filmed by default, and the [raw] theoretical best.
 */
data class CompositeOptions(val realistic: OptimalOption, val raw: OptimalOption) {
    /** Some composite lap can be filmed. */
    val enabled: Boolean get() = realistic.enabled || raw.enabled

    /** The one the sheet starts on: the realistic optimal, unless only the raw theoretical best can be made. */
    val preselected: OptimalOption get() = if (realistic.enabled || !raw.enabled) realistic else raw

    fun of(kind: OptimalKind): OptimalOption = if (kind == OptimalKind.RealisticOptimal) realistic else raw
}

/** The composite laps of [films], as the lap analysis offers them. */
fun compositeOptions(films: OptimalVideo): CompositeOptions = CompositeOptions(films.realistic.option, films.raw.option)

/** Both composite laps when the session's video could not be read: the screen still says why. */
fun unreadableCompositeOptions(): CompositeOptions {
    fun unreadable(kind: OptimalKind) = optimalOption(kind, null, null).copy(unavailable = UnreadableVideoText)
    return CompositeOptions(unreadable(OptimalKind.RealisticOptimal), unreadable(OptimalKind.RawTheoreticalBest))
}

/** The lap analysis's button for the composite video: the realistic optimal is the default. */
const val RealisticOptimalVideoLabel = "Realistic optimal video"

/** The sheet's title. */
const val CompositeLapVideoTitle = "Composite lap video"

/** What the sheet says the video is, over the parts. */
const val CompositeLapVideoAbout =
    "Each corner and straight from the lap shown, joined into one video. The overlay shows the " +
        "lap each part comes from, the running time, the gain on your best actual lap, speed, G " +
        "and the track map. It takes a while; you can leave the app meanwhile."

/** Why neither composite lap can be filmed when the session's video cannot be read. */
const val UnreadableVideoText = "The session's video could not be read."

/**
 * The line beside the button: while [options] is null the parts are being looked for on video;
 * then why the realistic optimal cannot be made, what it leaves out, or that every part is filmed.
 */
fun optimalVideoNote(options: CompositeOptions?): String {
    val o = options?.realistic ?: return "Finding the parts on video…"
    return when {
        !o.enabled -> o.unavailable.orEmpty()
        else -> missingText(o) ?: "Every part on video, from ${lapsText(o.laps)}"
    }
}
