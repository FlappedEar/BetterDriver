package com.flappedear.betterdriver.recorder

/**
 * How far a conclusion drawn from the data can be trusted (FBBD-177): a corner's time loss, a
 * braking point, a minimum speed, a focus area, a coach cue. It follows from the evidence behind the
 * conclusion: where the channels come from ([Provenance]), how often and how accurately positions
 * were recorded, how many laps agree, and how the gain compares with the lap-to-lap spread.
 *
 * The rule that follows from it, "no precise advice from weak evidence": advice carries numbers
 * (metres, km/h, tenths) only at [HIGH]; at [MEDIUM] it is qualitative ("brake a little later");
 * at [LOW] there is none. Ordered from the weakest, so the lowest of several is their [minOf].
 */
enum class Confidence {
    LOW,
    MEDIUM,
    HIGH,
    ;

    /** Advice at this confidence may carry numbers. */
    val precise: Boolean get() = this == HIGH

    /** Advice at this confidence may be given at all. */
    val advises: Boolean get() = this != LOW

    /** "High", "Medium" or "Low", as the screens show it. */
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        /** The lowest of [levels]; [HIGH] for none. */
        fun lowest(levels: Iterable<Confidence>): Confidence = levels.minOrNull() ?: HIGH

        /** The confidence stored as [name], or null for a name this version does not know. */
        fun parse(name: String?): Confidence? = entries.firstOrNull { it.name == name }
    }
}
