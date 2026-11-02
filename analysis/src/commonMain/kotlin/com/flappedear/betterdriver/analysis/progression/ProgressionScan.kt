package com.flappedear.betterdriver.analysis.progression

/** At most this many of the track's sessions are analysed for the progression each time it opens, newest first. */
const val MaxProgressionAdds = 20

/** Of at most this many of the track's sessions, newest first; sessions at other tracks are not counted. */
const val MaxProgressionScan = 120

/** What came of a session tried for the progression ([addNewest]). */
enum class AddResult {
    /** Analysed at the track, its record kept. */
    Added,

    /** Not read or not analysed: it may be the track's, so it counts as tried. */
    Failed,

    /** Another track's session, or one at no track: it leaves the limits as they are. */
    Elsewhere,
}

/**
 * Goes through [candidates] (session ids, newest first) for the progression (FBBD-181), the same on
 * Android and iOS (FBBD-360), analysing with [add] those whose record is missing or older than this
 * app's ([current] says whether one is current), not tried already ([tried]). [add] says what came of
 * each: only sessions at other tracks ([AddResult.Elsewhere]) leave the limits as they are, so they
 * never use them up, while a session that could not be read or analysed counts as tried. It stops
 * after [maxScan] of the track's sessions (a current record says one is) or [maxAdds] tried. [left]
 * hears, before each [add], how many more may be tried at most, and 0 at the end. Returns how many
 * were tried. Inline, so Android's [add] may suspend.
 */
@Suppress("LongParameterList") // What to go through, how, and its limits.
inline fun addNewest(
    candidates: List<String>,
    current: (String) -> Boolean,
    tried: (String) -> Boolean,
    add: (String) -> AddResult,
    left: (Int) -> Unit = {},
    maxScan: Int = MaxProgressionScan,
    maxAdds: Int = MaxProgressionAdds,
): Int {
    val pending = candidates.count { !current(it) && !tried(it) }
    var passed = 0
    var seen = 0
    var added = 0
    for (id in candidates) {
        if (seen >= maxScan || added >= maxAdds) break
        if (current(id)) {
            seen++
        } else if (!tried(id)) {
            left(minOf(pending - passed, maxAdds - added))
            passed++
            if (add(id) != AddResult.Elsewhere) {
                seen++
                added++
            }
        }
    }
    left(0)
    return added
}
