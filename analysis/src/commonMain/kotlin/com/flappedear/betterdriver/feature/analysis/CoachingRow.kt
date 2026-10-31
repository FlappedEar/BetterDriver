package com.flappedear.betterdriver.feature.analysis

/** Where a recommendation stands in the coaching loop (FBBD-180). */
enum class CoachingStatus(val label: String) {
    /** The improvement held in a later session. */
    Learned("Learned"),

    /** It improved the corner; a later session tells whether it held. */
    Improved("Improved"),

    /** Not enough to tell yet. */
    Pending("Pending"),

    /** Driven enough, no gain yet. */
    NoGain("No gain yet"),

    /** Some of the improvement held. */
    Partly("Partly held"),

    /** The improvement did not hold. */
    Lost("Lost"),

    /** Measured through the line plans (FBBD-310): its fastest pass got faster, with the coach speaking there. */
    Faster("Faster"),

    /** Faster, and the coach said nothing there: not the coach's doing. */
    FasterNoCue("Faster, no cue"),

    /** Within GPS timing of the session before. */
    Same("Same"),

    /** Slower than the session before. */
    Slower("Slower"),

    /** No pass through it this session. */
    NotDriven("Not driven"),
    ;

    /** Whether it reads as a gain (shown green on both apps). */
    val gain: Boolean get() = this == Learned || this == Improved || this == Faster || this == FasterNoCue

    /** Whether it reads as a loss (shown red); neither: dim. */
    val loss: Boolean get() = this == Lost || this == Slower
}

/**
 * One line of the coaching history: [name] the corner, [text] what came of the recommendation in
 * words (numbers only where precise), [status] where it stands. [doubtful] when its corner time
 * was not precise (shown dim, as the focus shows a time below High). [cornerId] is its CornerId.
 */
data class CoachingRow(
    val name: String,
    val status: CoachingStatus,
    val text: String,
    val doubtful: Boolean,
    val cornerId: String,
    /** True for the focus this session worked on; false for an earlier gain checked in it. */
    val attempt: Boolean = true,
    /** Measured through the line plans, fastest pass against fastest pass (FBBD-310). */
    val measured: Boolean = false,
)
