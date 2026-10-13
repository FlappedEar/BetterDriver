// One function per correction, each refusing early what it cannot do.
@file:Suppress("TooManyFunctions", "ReturnCount")

package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.timing.geo.LocalFrame
import kotlin.math.roundToLong

// The driver's corrections of the detected corners and straights (FBBD-179). A correction is kept in
// the track's corner identity ([Track.identity]), so every session at the track is analysed and
// coached with it from its next analysis on. One that changes where corners and straights are (a
// boundary moved, a corner split, two merged, one removed, a corner made a straight or the other way)
// makes the next [TrackRevision], with [TrackRevision.previousIds] mapping each id to where it went;
// every corner it does not touch keeps its CornerId. A new name, or a corner called a chicane, keeps
// the revision: nothing is timed differently. The detected segments are kept beside the corrected
// ones ([TrackRevision.detected]), so the driver can always go back to them.

/** What the driver sees a corner or straight as (FBBD-179). A chicane is timed as a corner. */
enum class SegmentType { Corner, Chicane, Straight }

/** One correction of a track's corners and straights (FBBD-179). Metres are along the revision's centreline. */
sealed interface SegmentEdit {
    /** Names segment [id] [label]; null or blank goes back to its numbered name. */
    data class Rename(val id: String, val label: String?) : SegmentEdit

    /** Moves where segment [id] starts (and the one before it ends) to [meters]. */
    data class MoveBoundary(val id: String, val meters: Double) : SegmentEdit

    /** Splits segment [id] in two at [meters]; the first part keeps its id, the second gets a new one. */
    data class Split(val id: String, val meters: Double) : SegmentEdit

    /** Makes segment [id] and the one after it one segment. */
    data class Merge(val id: String) : SegmentEdit

    /** Gives segment [id]'s stretch to its neighbours: it was no corner (or no straight). */
    data class Remove(val id: String) : SegmentEdit

    /** Makes segment [id] a corner, a chicane or a straight. */
    data class ChangeType(val id: String, val type: SegmentType) : SegmentEdit

    /** Goes back to the corners and straights as detected. */
    data object RestoreAutomatic : SegmentEdit
}

/** No corner or straight is made shorter than this by a correction, in metres. */
const val MinCorrectedSegmentMeters = 10.0

/**
 * [this] revision with [edit] applied (FBBD-179), or null when it cannot be: an unknown segment, a
 * boundary or split point outside the segment (or within [MinCorrectedSegmentMeters] of its ends), a
 * merge or remove that would leave fewer than two segments, a name that is too long.
 */
fun TrackRevision.corrected(edit: SegmentEdit): TrackRevision? = when (edit) {
    is SegmentEdit.Rename -> renamed(edit.id, edit.label)
    is SegmentEdit.MoveBoundary -> boundaryMoved(edit.id, edit.meters)
    is SegmentEdit.Split -> split(edit.id, edit.meters)
    is SegmentEdit.Merge -> merged(edit.id)
    is SegmentEdit.Remove -> removed(edit.id)
    is SegmentEdit.ChangeType -> retyped(edit.id, edit.type)
    SegmentEdit.RestoreAutomatic -> automatic()
}

/** Whether segment [id] is not as detected: renamed, retyped, moved, or made by a correction. */
fun TrackRevision.edited(id: String): Boolean {
    val base = detected ?: return false
    val now = segment(id) ?: return false
    val was = base.firstOrNull { it.id == id } ?: return true
    return now.label != was.label || now.type != was.type || now.cornerCount != was.cornerCount ||
        now.startMeters != was.startMeters || now.endMeters != was.endMeters
}

/** A segment being corrected, with how many corners it holds (0 for a straight). */
private class Part(val segment: RevisionSegment, val corners: Int = segment.cornerCount)

private fun TrackRevision.renamed(id: String, label: String?): TrackRevision? {
    val s = segment(id) ?: return null
    val name = label?.trim()?.takeIf { it.isNotEmpty() && it != s.numberedName }
    if (name != null && name.length > MaxSegmentLabelChars) return null
    if (name == s.label) return this
    return finish(segments.map { Part(if (it.id == id) it.copy(label = name) else it) }, mapping = null)
}

private fun TrackRevision.boundaryMoved(id: String, meters: Double): TrackRevision? {
    val i = indexOf(id) ?: return null
    val p = previous(i) ?: return null
    val at = normal(meters)
    val before = segments[p]
    val s = segments[i]
    if (!inside(before.startMeters, at, s.endMeters)) return null
    if (at == s.startMeters) return this
    val parts = segments.mapIndexed { k, seg ->
        Part(
            when (k) {
                p -> seg.copy(endMeters = at)
                i -> seg.copy(startMeters = at)
                else -> seg
            },
        )
    }
    return finish(parts, mapping = sameIds())
}

private fun TrackRevision.split(id: String, meters: Double): TrackRevision? {
    val i = indexOf(id) ?: return null
    val s = segments[i]
    val at = normal(meters)
    if (!inside(s.startMeters, at, s.endMeters)) return null
    val corner = s.kind == SegmentKind.Corner
    val newId = if (corner) "c$nextCornerId" else "s$nextStraightId"
    val first = (s.cornerCount + 1) / 2
    val second = maxOf(1, s.cornerCount / 2)
    val head = Part(s.copy(endMeters = at), if (corner) first else 0)
    val tail = Part(
        RevisionSegment(newId, s.kind, 1, startMeters = at, endMeters = s.endMeters, chicane = s.chicane),
        if (corner) second else 0,
    )
    val parts = segments.flatMapIndexed { k, seg -> if (k == i) listOf(head, tail) else listOf(Part(seg)) }
    return finish(
        parts,
        mapping = sameIds(),
        nextCorner = if (corner) nextCornerId + 1 else nextCornerId,
        nextStraight = if (corner) nextStraightId else nextStraightId + 1,
    )
}

private fun TrackRevision.merged(id: String): TrackRevision? {
    val i = indexOf(id) ?: return null
    val j = following(i) ?: return null
    if (segments.size < MinSegmentsToJoin) return null
    val a = segments[i]
    val b = segments[j]
    val joined = join(a, b)
    val keep = joined.segment.id
    val parts = segments.indices.mapNotNull { k ->
        when (k) {
            i -> joined
            j -> null
            else -> Part(segments[k])
        }
    }
    return finish(parts, mapping = sameIds() + mapOf(a.id to keep, b.id to keep))
}

/** [a] and the [b] after it as one segment: a corner if either is, under the id of the one of that kind. */
private fun join(a: RevisionSegment, b: RevisionSegment): Part {
    val corner = a.kind == SegmentKind.Corner || b.kind == SegmentKind.Corner
    val keep = if (!corner || a.kind == SegmentKind.Corner) a else b
    return Part(keep.copy(startMeters = a.startMeters, endMeters = b.endMeters), a.cornerCount + b.cornerCount)
}

/**
 * A removed corner's stretch goes to the straights either side, which become one; a removed straight
 * joins the corners either side into one chain ("Corners 3–4"). Next to one of its own kind and one of
 * the other, a removed corner goes to the straight and a removed straight to the segment before it.
 */
private fun TrackRevision.removed(id: String): TrackRevision? {
    val i = indexOf(id) ?: return null
    if (segments.size < MinSegmentsToJoin) return null
    val p = previous(i)
    val q = following(i)
    val before = p?.let { segments[it] }
    val after = q?.let { segments[it] }
    val alike = before != null && after != null && before.kind == after.kind
    return if (alike && segments.size > MinSegmentsToJoin) joinedAround(i, p!!, q!!) else absorbed(i, before, p, q)
}

/** Segment [i] gone, and the ones either side of it ([p] before, [q] after) one, under [p]'s id. */
private fun TrackRevision.joinedAround(i: Int, p: Int, q: Int): TrackRevision {
    val before = segments[p]
    val after = segments[q]
    val joined = Part(before.copy(endMeters = after.endMeters), before.cornerCount + after.cornerCount)
    val parts = segments.indices.mapNotNull { k ->
        when (k) {
            p -> joined
            i, q -> null
            else -> Part(segments[k])
        }
    }
    return finish(parts, mapping = sameIds() + mapOf(segments[i].id to before.id, after.id to before.id))
}

/** Segment [i] gone, its stretch given to one neighbour: the straight beside a corner, else [before]. */
private fun TrackRevision.absorbed(i: Int, before: RevisionSegment?, p: Int?, q: Int?): TrackRevision {
    val x = segments[i]
    val afterIsStraight = q != null && segments[q].kind == SegmentKind.Straight
    val cornerBetween = x.kind == SegmentKind.Corner && before?.kind == SegmentKind.Corner
    val toAfter = before == null || cornerBetween && afterIsStraight
    val target = if (toAfter) q!! else p!!
    val parts = segments.indices.mapNotNull { k ->
        val seg = segments[k]
        when {
            k == i -> null
            k == target && toAfter -> Part(seg.copy(startMeters = x.startMeters))
            k == target -> Part(seg.copy(endMeters = x.endMeters))
            else -> Part(seg)
        }
    }
    return finish(parts, mapping = sameIds() + mapOf(x.id to segments[target].id))
}

private fun TrackRevision.retyped(id: String, type: SegmentType): TrackRevision? {
    val i = indexOf(id) ?: return null
    val s = segments[i]
    if (s.type == type) return this
    val wasStraight = s.kind == SegmentKind.Straight
    val toStraight = type == SegmentType.Straight
    if (!wasStraight && !toStraight) {
        // A corner called a chicane, or back: named differently, timed the same.
        val chicane = type == SegmentType.Chicane
        return finish(segments.map { Part(if (it.id == id) it.copy(chicane = chicane) else it) }, mapping = null)
    }
    val newId = if (toStraight) "s$nextStraightId" else "c$nextCornerId"
    val made = RevisionSegment(
        newId,
        if (toStraight) SegmentKind.Straight else SegmentKind.Corner,
        1,
        startMeters = s.startMeters,
        endMeters = s.endMeters,
        chicane = type == SegmentType.Chicane,
    )
    val parts = segments.map { if (it.id == id) Part(made, if (toStraight) 0 else 1) else Part(it) }
    return finish(
        parts,
        mapping = sameIds() + (id to newId),
        nextCorner = if (toStraight) nextCornerId else nextCornerId + 1,
        nextStraight = if (toStraight) nextStraightId + 1 else nextStraightId,
    )
}

/**
 * The detected segments again. Each corrected segment maps to the detected one with its id, or else
 * to the one its middle lies in; a revision that only named segments keeps its number.
 */
private fun TrackRevision.automatic(): TrackRevision {
    val base = detected ?: return this
    if (sameGeometry(segments, base)) return copy(segments = base, detected = null)
    val length = lengthMeters
    val mapping = segments.associate { s ->
        val kept = base.firstOrNull { it.id == s.id }
        val middle = middleOf(s, length)
        s.id to (kept ?: base.firstOrNull { inArc(middle, it.startMeters, it.endMeters, length) } ?: base.first()).id
    }
    return copy(number = number + 1, segments = base, previousIds = mapping, detected = null)
}

/**
 * [this] identity, received with its track from the other device (FBBD-179), kept in place of [here],
 * the identity of the same track on this device: the received corners and straights, corrections and
 * all, as the next revision after [here]. A received segment takes the id of the first segment of its
 * kind of [here] whose middle lies in it, so a corner both devices have keeps its CornerId here; the
 * others get new ids, and [TrackRevision.previousIds] maps [here]'s ids to where they went. Without an
 * identity here it is kept as it is. Revision numbers and ids are each device's own, so a received
 * identity is never stored under its own.
 */
fun TrackRevision.adoptedOver(here: TrackRevision?): TrackRevision {
    if (here == null || here == this) return this
    val frame = LocalFrame(start)
    val hereLength = here.lengthMeters
    // Where the middle of each segment here is on the received centreline, driven the same way.
    val placed = here.segments.associate { s ->
        val m = middleOf(s, hereLength)
        val p = frame.toXy(here.pointAt(m))
        val heading = frame.toXy(here.pointAt(m + 1.0)) - frame.toXy(here.pointAt(m - 1.0))
        s.id to locate(frame, p, heading)?.let { segmentAt(it).id }
    }
    var nextCorner = maxOf(nextCornerId, here.nextCornerId)
    var nextStraight = maxOf(nextStraightId, here.nextStraightId)
    fun fresh(kind: SegmentKind) = if (kind == SegmentKind.Corner) "c${nextCorner++}" else "s${nextStraight++}"
    val ids = HashMap<String, String>()
    for (s in segments) {
        val kept = here.segments.firstOrNull { placed[it.id] == s.id && it.kind == s.kind }
        ids[s.id] = kept?.id ?: fresh(s.kind)
    }
    detected?.forEach { d -> if (d.id !in ids) ids[d.id] = fresh(d.kind) }
    fun renamed(list: List<RevisionSegment>) = list.map { it.copy(id = ids.getValue(it.id)) }
    return copy(
        number = maxOf(number, here.number) + 1,
        segments = renamed(segments),
        previousIds = placed.mapNotNull { (id, at) -> at?.let { id to ids.getValue(it) } }.toMap(),
        nextCornerId = nextCorner,
        nextStraightId = nextStraight,
        madeFromUtcMillis = listOfNotNull(madeFromUtcMillis, here.madeFromUtcMillis).maxOrNull(),
        detected = detected?.let(::renamed),
    )
}

/** The corrected revision of [parts]: renumbered in lap order, the next revision when [mapping] is given. */
private fun TrackRevision.finish(
    parts: List<Part>,
    mapping: Map<String, String>?,
    nextCorner: Int = nextCornerId,
    nextStraight: Int = nextStraightId,
): TrackRevision {
    var corner = 0
    var straight = 0
    val renumbered = parts.map { part ->
        val s = part.segment
        if (s.kind == SegmentKind.Corner) {
            val first = corner + 1
            corner += maxOf(1, part.corners)
            s.copy(number = first, lastNumber = corner)
        } else {
            s.copy(number = ++straight, lastNumber = straight)
        }
    }
    val base = detected ?: segments
    return copy(
        number = if (mapping != null) number + 1 else number,
        segments = renumbered,
        previousIds = mapping ?: previousIds,
        nextCornerId = nextCorner,
        nextStraightId = nextStraight,
        detected = base.takeIf { it != renumbered },
    )
}

private fun TrackRevision.sameIds(): Map<String, String> = segments.associate { it.id to it.id }

private fun TrackRevision.indexOf(id: String): Int? = segments.indexOfFirst { it.id == id }.takeIf { it >= 0 }

/** The segment before [i]: across the line on a closed track, none before the first on an open one. */
private fun TrackRevision.previous(i: Int): Int? = if (i > 0) i - 1 else if (closed) segments.size - 1 else null

/** The segment after [i], as [previous]. */
private fun TrackRevision.following(i: Int): Int? =
    if (i + 1 < segments.size) i + 1 else if (closed) 0 else null

/** [meters] on the centreline, rounded as stored: within one lap on a closed track. */
private fun TrackRevision.normal(meters: Double): Double {
    val length = lengthMeters
    val m = if (closed) meters.mod(length) else meters.coerceIn(0.0, length)
    return (m * Centi).roundToLong() / Centi
}

/** Whether [m] lies between [from] and [to] in driving order, at least [MinCorrectedSegmentMeters] from both. */
private fun TrackRevision.inside(from: Double, m: Double, to: Double): Boolean {
    if (!m.isFinite()) return false
    val length = lengthMeters
    fun span(a: Double, b: Double) = if (closed) (b - a).mod(length) else b - a
    val whole = span(from, to).let { if (closed && it == 0.0) length else it }
    val head = span(from, m)
    return head >= MinCorrectedSegmentMeters && whole - head >= MinCorrectedSegmentMeters
}

private fun middleOf(s: RevisionSegment, length: Double): Double {
    var span = s.endMeters - s.startMeters
    if (span < 0) span += length
    return (s.startMeters + span / 2).mod(length)
}

private fun inArc(m: Double, start: Double, end: Double, length: Double): Boolean =
    if (start <= end) m in start..end else m >= start || m <= end || m >= length

/** Same ids, kinds, boundaries and corner counts: nothing is timed differently. */
private fun sameGeometry(a: List<RevisionSegment>, b: List<RevisionSegment>): Boolean =
    a.size == b.size && a.zip(b).all { (x, y) ->
        x.id == y.id && x.kind == y.kind && x.startMeters == y.startMeters && x.endMeters == y.endMeters &&
            x.cornerCount == y.cornerCount
    }

/** A merge or a remove needs at least this many segments, so two are left. */
private const val MinSegmentsToJoin = 3

private const val Centi = 100.0
