package com.flappedear.betterdriver.analysis.confidence

import com.flappedear.betterdriver.recorder.Confidence

/**
 * One thing found in the evidence behind a conclusion (FBBD-177): what it is ([code], stable, for
 * tests and logs), the most confidence it leaves ([cap]) and what the screens say about it ([text]).
 */
data class ConfidenceReason(val code: String, val cap: Confidence, val text: String)

/**
 * A conclusion's confidence and why it is not higher: the lowest [ConfidenceReason.cap] of its
 * [reasons], [Confidence.HIGH] with none. Reasons are kept weakest first, each once.
 */
data class Graded(val level: Confidence = Confidence.HIGH, val reasons: List<ConfidenceReason> = emptyList()) {
    /** The reasons as one line, weakest first: what the screens show next to the conclusion. */
    val why: String get() = reasons.joinToString("; ") { it.text }

    /** Both sets of reasons: the lower confidence of the two. */
    operator fun plus(other: Graded): Graded = of(reasons + other.reasons)

    /** With [reason] added, when there is one. */
    operator fun plus(reason: ConfidenceReason?): Graded = if (reason == null) this else of(reasons + reason)

    /** Whether a reason with [code] lowered it. */
    fun has(code: String): Boolean = reasons.any { it.code == code }

    companion object {
        val High = Graded()

        fun of(reasons: List<ConfidenceReason>): Graded {
            val kept = reasons.distinctBy { it.code to it.text }.sortedBy { it.cap }
            return Graded(Confidence.lowest(kept.map { it.cap }), kept)
        }

        fun of(vararg reasons: ConfidenceReason?): Graded = of(reasons.filterNotNull())
    }
}
