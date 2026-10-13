package com.flappedear.betterdriver.timing.track

import kotlinx.serialization.Serializable

// Following a corner across revisions of a track's corners (FBBD-179, FBBD-181): each revision only
// maps the one before it ([TrackRevision.previousIds]); the store keeps every step it replaced at the
// track ([TrackRevision.lineage]), so records of a session analysed several revisions ago (coaching
// history, progression) find their corner in the corners of today.

/** The most steps kept: older ones are dropped, and corners that far back are no longer followed. */
const val MaxRevisionLinks = 64

/** One step from revision [from] to revision [to] of a track's corners: each id of [from] to its id in [to]. */
@Serializable
data class RevisionLink(val from: Int, val to: Int, val ids: Map<String, String>)

/**
 * This revision stored in place of [old]: the step from [old] to it added to the lineage when it is
 * a later revision; otherwise [old]'s lineage kept (a rename or a corner made a chicane keeps the
 * revision).
 */
fun TrackRevision.following(old: TrackRevision?): TrackRevision = when {
    old == null || lineage == old.lineage && number <= old.number -> this
    number <= old.number -> copy(lineage = old.lineage)
    else -> copy(lineage = (old.lineage + RevisionLink(old.number, number, previousIds)).takeLast(MaxRevisionLinks))
}

/**
 * The id in revision [toRevision] of corner or straight [id] of revision [fromRevision], following
 * the [TrackRevision.lineage] forwards or backwards; null when it is gone, or the steps between are not
 * known. Backwards, a place two ids were merged into goes back to the one that kept its id, if any.
 */
fun TrackRevision.cornerIdIn(fromRevision: Int, id: String, toRevision: Int): String? {
    var at = fromRevision
    var cur: String? = id
    while (cur != null && at != toRevision) {
        val (next, moved) = stepFrom(at, cur, toRevision) ?: return null
        at = next
        cur = moved
    }
    return cur
}

/** One step of the lineage from revision [at] towards [toRevision]: the revision reached and [id] there. */
@Suppress("ReturnCount") // No step, forwards or backwards.
private fun TrackRevision.stepFrom(at: Int, id: String, toRevision: Int): Pair<Int, String?>? {
    if (at < toRevision) {
        val step = lineage.lastOrNull { it.from == at && it.to <= toRevision } ?: return null
        return step.to to step.ids[id]
    }
    val step = lineage.lastOrNull { it.to == at && it.from >= toRevision } ?: return null
    val back = step.ids.filterValues { it == id }.keys
    return step.from to (if (id in back) id else back.singleOrNull())
}
