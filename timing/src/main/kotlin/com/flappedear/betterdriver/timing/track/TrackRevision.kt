package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.MaxLatitude
import com.flappedear.betterdriver.timing.geo.MaxLongitude
import com.flappedear.betterdriver.timing.geo.Xy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

// The track's corner and straight identity (FBBD-172). Every part of the app that names a corner
// (the lap analysis, its losses and focus, the corner laps and their video, the comparison with the
// session before, the coach's references, cues and picks) maps its own corners to these by position
// on the revision's centreline, never by the order it happens to find them in. So "Corner 3" is the
// same corner whichever lap is the reference and however often a session is analysed.

/** A corner or a straight. */
@Serializable
enum class SegmentKind {
    @SerialName("corner")
    Corner,

    @SerialName("straight")
    Straight,
}

/**
 * One corner or straight of a [TrackRevision]. [id] is its SegmentId, for a corner its CornerId: it
 * never changes while the track keeps its shape, and a new revision keeps it for the same corner.
 * [number] is what the driver reads ("Corner 3"); corners with no straight between them are one
 * segment numbered [number] to [lastNumber] ("Corners 3–4"). [startMeters] and [endMeters] are along
 * the revision's centreline; the last segment of a closed track may wrap ([endMeters] < [startMeters]).
 *
 * [label] is the driver's own name for it and [chicane] marks a corner the driver calls a chicane
 * (FBBD-179); neither changes how it is timed.
 */
@Serializable
data class RevisionSegment(
    val id: String,
    val kind: SegmentKind,
    val number: Int,
    val lastNumber: Int = number,
    val startMeters: Double,
    val endMeters: Double,
    val label: String? = null,
    val chicane: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id.length <= MaxSegmentIdChars) { "bad segment id" }
        require(number >= 1 && lastNumber >= number) { "bad segment numbers" }
        require(startMeters.isFinite() && endMeters.isFinite()) { "bad segment range" }
        require(label == null || label.isNotBlank() && label.length <= MaxSegmentLabelChars) { "bad segment label" }
        require(!chicane || kind == SegmentKind.Corner) { "only a corner is a chicane" }
    }

    /** The driver's [label], else the [numberedName]. */
    val name: String get() = label ?: numberedName

    /** "Corner 3", "Corners 3–4", "Chicane 5", "Chicane 5–6" or "Straight 2". */
    val numberedName: String
        get() = when {
            kind == SegmentKind.Straight -> "Straight $number"
            chicane && lastNumber > number -> "Chicane $number–$lastNumber"
            chicane -> "Chicane $number"
            lastNumber > number -> "Corners $number–$lastNumber"
            else -> "Corner $number"
        }

    /** What the driver sees it as: a corner, a chicane or a straight. */
    val type: SegmentType
        get() = when {
            kind == SegmentKind.Straight -> SegmentType.Straight
            chicane -> SegmentType.Chicane
            else -> SegmentType.Corner
        }

    /** How many corners it holds: 0 for a straight. */
    val cornerCount: Int get() = if (kind == SegmentKind.Corner) lastNumber - number + 1 else 0
}

/**
 * One version of a track's shape and its corners and straights (FBBD-172), kept in the track file. The
 * centreline ([lats], [lons], a point about every [CentrelineStepMeters] from the start/finish line in
 * driving order) is what every corner is placed by. [number] counts the revisions: a lap that leaves
 * the centreline for good ([fits] is false) makes the next one ([TrackRevision.revised]), and
 * [previousIds] maps each id of the revision before to the id of the same place in this one; an id
 * that is not there is a corner or straight that is gone. [nextCornerId] and [nextStraightId] number
 * new ids, so an id is never used for two places. [madeFromUtcMillis] is when the session whose lap
 * made it started, so a session recorded before it (on the layout before) does not replace it; null
 * for revisions kept before it was.
 */
@Serializable
data class TrackRevision(
    val number: Int,
    val lats: List<Double>,
    val lons: List<Double>,
    val closed: Boolean = true,
    val segments: List<RevisionSegment>,
    val previousIds: Map<String, String> = emptyMap(),
    val nextCornerId: Int = segments.count { it.kind == SegmentKind.Corner } + 1,
    val nextStraightId: Int = segments.count { it.kind == SegmentKind.Straight } + 1,
    val madeFromUtcMillis: Long? = null,
    val detected: List<RevisionSegment>? = null,
) {
    init {
        require(number >= 1) { "revisions start at 1" }
        require(lats.size == lons.size && lats.size >= 2) { "a centreline needs two points" }
        require(lats.all { it.isFinite() && it in -MaxLatitude..MaxLatitude }) { "latitude out of range" }
        require(lons.all { it.isFinite() && it in -MaxLongitude..MaxLongitude }) { "longitude out of range" }
        require(segments.size >= 2 && segments.map { it.id }.toSet().size == segments.size) { "bad segments" }
        require(detected == null || detected.size >= 2 && detected.map { it.id }.toSet().size == detected.size) {
            "bad detected segments"
        }
    }

    /** The driver corrected the detected corners and straights (FBBD-179); [detected] keeps those. */
    val corrected: Boolean get() = detected != null

    /** The point [meters] along the centreline. */
    fun pointAt(meters: Double): GeoPoint {
        val frame = LocalFrame(start)
        return frame.toGeo(Centreline(this, frame).pointAt(meters))
    }

    /** Metres along the centreline nearest [p], whichever way it runs there; null further than [maxMeters]. */
    fun metersNear(p: GeoPoint, maxMeters: Double = Double.MAX_VALUE): Double? {
        val frame = LocalFrame(start)
        return Centreline(this, frame).locate(frame.toXy(p), null, maxMeters)
    }

    /**
     * The centreline from [fromMeters] to [toMeters] in driving order (across the line on a closed
     * track when [toMeters] is before [fromMeters]): both ends and every stored point between, to draw.
     */
    fun path(fromMeters: Double, toMeters: Double): List<GeoPoint> {
        val frame = LocalFrame(start)
        val line = Centreline(this, frame)
        val length = line.lengthMeters
        val from = if (closed) fromMeters.mod(length) else fromMeters.coerceIn(0.0, length)
        val to = if (closed) toMeters.mod(length) else toMeters.coerceIn(0.0, length)
        val inside = if (from <= to) {
            line.points.indices.filter { line.along[it] > from && line.along[it] < to }
        } else {
            line.points.indices.filter { line.along[it] > from } + line.points.indices.filter { line.along[it] < to }
        }
        val points = listOf(line.pointAt(from)) + inside.map { line.points[it] } + line.pointAt(to)
        return points.map(frame::toGeo)
    }

    /** Length of the centreline, closed back to its start on a closed track. */
    val lengthMeters: Double get() = Centreline(this, LocalFrame(start)).lengthMeters

    /** Where the centreline starts: the start/finish line when the revision was made. */
    val start: GeoPoint get() = GeoPoint(lats.first(), lons.first())

    val corners: List<RevisionSegment> get() = segments.filter { it.kind == SegmentKind.Corner }

    fun segment(id: String): RevisionSegment? = segments.firstOrNull { it.id == id }

    /** The segment [meters] along the centreline falls in. */
    fun segmentAt(meters: Double): RevisionSegment {
        val length = lengthMeters
        val m = if (closed) ((meters % length) + length) % length else meters.coerceIn(0.0, length)
        return segments.firstOrNull { inRange(m, it.startMeters, it.endMeters) }
            ?: segments.minBy { circular(m, it.startMeters, length) }
    }

    /**
     * Metres along the centreline nearest [p] (in [frame]) where it runs the way [heading] does; null
     * when no part of it running that way is within [RevisionChangeMeters].
     */
    fun locate(frame: LocalFrame, p: Xy, heading: Xy? = null): Double? =
        Centreline(this, frame).locate(p, heading, RevisionChangeMeters)

    /**
     * The corner a position belongs to, with the number the driver hears there: the corner segment
     * [meters] along the centreline falls in, or the nearest corner when it falls on a straight.
     */
    fun cornerAt(meters: Double): RevisionSegment? {
        val at = segmentAt(meters)
        if (at.kind == SegmentKind.Corner) return at
        val length = lengthMeters
        return corners.minByOrNull { c ->
            minOf(circular(meters, c.startMeters, length), circular(meters, c.endMeters, length))
        }
    }

    /**
     * Whether [path], one lap in [frame] from its start to its finish, drives the shape of this
     * revision: no stretch of it at least [RevisionChangeRunMeters] long is more than
     * [RevisionChangeMeters] from the centreline, and no such stretch of the centreline is that far
     * from it. Another line through the same corners always fits; a new chicane or layout does not.
     */
    fun fits(frame: LocalFrame, path: List<Xy>): Boolean {
        if (path.size < 2) return false
        val centreline = Centreline(this, frame)
        val other = Polyline(path, closed)
        return !centreline.leaves(other) && !other.leaves(centreline)
    }

    /**
     * Where each segment starts along [path] (one lap in [frame], with [along] its metres from the
     * line at each point), in segment order; null when a start is not near the path.
     */
    fun place(frame: LocalFrame, path: List<Xy>, along: DoubleArray): List<Double>? {
        val centreline = Centreline(this, frame)
        val target = Polyline(path, closed, along)
        val starts = segments.map { s ->
            target.locate(centreline.pointAt(s.startMeters), centreline.headingAt(s.startMeters), RevisionChangeMeters)
        }
        return if (starts.any { it == null }) null else starts.map { it!! }
    }

    /**
     * This revision if [path] fits it, else the next one made from [path] and [shapes] (see
     * [first]) with the ids carried over. Each segment of this revision is placed on [path] by its
     * middle, or a quarter in from either end when its middle is no longer on the track; a new
     * segment keeps the id of the one segment of its kind placed in it, and [previousIds] maps every
     * segment that could be placed to the new segment it is in. A track that became a circuit or a
     * point-to-point course ([closed] differs from this revision's) always makes the next revision,
     * with its new topology and the ids carried over the same way.
     */
    fun revised(
        frame: LocalFrame,
        path: List<Xy>,
        shapes: List<SegmentShape>,
        along: DoubleArray? = null,
        closed: Boolean = this.closed,
    ): TrackRevision {
        if (closed == this.closed && fits(frame, path)) return this
        val line = Polyline(path, closed, along)
        val oldLine = Centreline(this, frame)
        // Each old segment on the new path: its middle, or a quarter in from either end when the
        // middle is gone (a straight with a new chicane in the middle of it).
        val middles = segments.associate { s ->
            s.id to MappedFractions.firstNotNullOfOrNull { f ->
                val m = pointIn(s, f, oldLine.lengthMeters)
                line.locate(oldLine.pointAt(m), oldLine.headingAt(m), RevisionChangeMeters)
            }
        }
        val inShape = shapes.map { shape ->
            segments.filter { s -> middles[s.id]?.let { inRange(it, shape.startMeters, shape.endMeters) } == true }
        }
        var nextCorner = nextCornerId
        var nextStraight = nextStraightId
        val ids = shapes.mapIndexed { i, shape ->
            val kept = inShape[i].filter { it.kind == shape.kind }.singleOrNull()
                ?.takeIf { k -> inShape.count { k in it } == 1 }
            kept?.id ?: if (shape.kind == SegmentKind.Corner) "c${nextCorner++}" else "s${nextStraight++}"
        }
        val previous = segments.mapNotNull { s ->
            val i = shapes.indices.firstOrNull { s in inShape[it] } ?: return@mapNotNull null
            s.id to ids[i]
        }.toMap()
        val made = build(number + 1, frame, line, shapes, ids, previous, nextCorner, nextStraight)
        // A corner or straight that keeps its id keeps the driver's name for it (FBBD-179).
        return made.copy(
            segments = made.segments.map { s ->
                segment(s.id)?.takeIf { it.kind == s.kind }?.let { s.copy(label = it.label, chicane = it.chicane) } ?: s
            },
        )
    }

    companion object {
        /**
         * The first revision of a track, from one lap: [path], in [frame], from the start/finish line,
         * and its corners and straights [shapes] in lap order, by metres along [along] (the path's own
         * metres when null). Corners and straights are numbered in lap order and get ids c1, c2, ...
         * and s1, s2, ...
         */
        fun first(
            frame: LocalFrame,
            path: List<Xy>,
            closed: Boolean,
            shapes: List<SegmentShape>,
            along: DoubleArray? = null,
        ): TrackRevision {
            var corner = 1
            var straight = 1
            val ids = shapes.map { if (it.kind == SegmentKind.Corner) "c${corner++}" else "s${straight++}" }
            return build(1, frame, Polyline(path, closed, along), shapes, ids, emptyMap(), corner, straight)
        }

        @Suppress("LongParameterList") // One revision's parts, made in one place.
        private fun build(
            number: Int,
            frame: LocalFrame,
            line: Polyline,
            shapes: List<SegmentShape>,
            ids: List<String>,
            previous: Map<String, String>,
            nextCorner: Int,
            nextStraight: Int,
        ): TrackRevision {
            val step = maxOf(1, (CentrelineStepMeters / line.meanSpacing()).toInt())
            val kept = line.points.indices.filter { it % step == 0 }
            val points = kept.map { frame.toGeo(line.points[it]) }
            // Metres along the stored centreline at each kept point, to place the boundaries on it.
            val stored = Polyline(kept.map { line.points[it] }, line.closed)
            fun onStored(m: Double): Double = metresOnStored(stored, kept, line, m)
            var corner = 0
            var straight = 0
            val segments = shapes.mapIndexed { i, s ->
                val first = if (s.kind == SegmentKind.Corner) corner + 1 else ++straight
                if (s.kind == SegmentKind.Corner) corner += s.corners
                RevisionSegment(
                    ids[i], s.kind, first, if (s.kind == SegmentKind.Corner) corner else first,
                    onStored(s.startMeters).round(), onStored(s.endMeters).round(),
                )
            }
            return TrackRevision(
                number = number,
                lats = points.map { it.lat.roundGeo() },
                lons = points.map { it.lon.roundGeo() },
                closed = line.closed,
                segments = segments,
                previousIds = previous,
                nextCornerId = nextCorner,
                nextStraightId = nextStraight,
            )
        }
    }
}

/**
 * A corner or straight found on one lap, by metres along it, for [TrackRevision.first] and
 * [TrackRevision.revised]; [corners] is how many corners a chain of them holds (1 for a straight).
 */
data class SegmentShape(val kind: SegmentKind, val startMeters: Double, val endMeters: Double, val corners: Int = 1)

/** A lap further than this from a revision's centreline has left its shape (FBBD-172). */
const val RevisionChangeMeters = 25.0

/** ... over at least this far along it; a shorter excursion is GNSS noise or a moment off track. */
const val RevisionChangeRunMeters = 30.0

/** A revision's centreline keeps about one point every this many metres. */
const val CentrelineStepMeters = 5.0

private const val MaxSegmentIdChars = 32

/** The longest name the driver can give a corner or straight (FBBD-179). */
const val MaxSegmentLabelChars = 40

/** Positions are kept to seven decimals of a degree: about a centimetre. */
private const val GeoScale = 1e7

/** Within this angle of each other two directions are the same way: cos 60°. */
private const val SameWayCosine = 0.5

private const val Centi = 100.0

/** Shortest piece length a line's spacing is taken as, so a line of repeated points still has a step. */
private const val MinSpacingMeters = 1e-3

private fun Double.round() = (this * Centi).roundToLong() / Centi

private fun Double.roundGeo() = (this * GeoScale).roundToLong() / GeoScale

private fun inRange(m: Double, start: Double, end: Double) =
    if (start <= end) m in start..end else m >= start || m <= end

/** [ab] runs the way [heading] does, within 60°; any way when there is no heading. */
private fun sameWay(ab: Xy, heading: Xy?): Boolean =
    heading == null || heading.length() == 0.0 || ab.dot(heading) >= SameWayCosine * heading.length() * ab.length()

private fun circular(a: Double, b: Double, length: Double): Double {
    val d = kotlin.math.abs(a - b)
    return minOf(d, length - d)
}

/** Where an old segment is looked for on a new revision's lap: its middle, then a quarter from either end. */
private val MappedFractions = listOf(0.5, 0.25, 0.75)

/** The point [fraction] of the way through [s], in metres along a centreline [length] long. */
private fun pointIn(s: RevisionSegment, fraction: Double, length: Double): Double {
    var span = s.endMeters - s.startMeters
    if (span < 0) span += length
    return (s.startMeters + span * fraction) % length
}

/** A revision's centreline in [frame]. */
private class Centreline(revision: TrackRevision, frame: LocalFrame) :
    Polyline(revision.lats.indices.map { frame.toXy(revision.lats[it], revision.lons[it]) }, revision.closed)

/**
 * Points in driving order, closed back to the first on a loop, with metres [along] it at each point
 * (its own length by default).
 */
private open class Polyline(val points: List<Xy>, val closed: Boolean, along: DoubleArray? = null) {
    val along: DoubleArray = along ?: DoubleArray(points.size).also { a ->
        for (i in 1 until points.size) a[i] = a[i - 1] + (points[i] - points[i - 1]).length()
    }

    val lengthMeters: Double =
        this.along.last() + if (closed && points.size > 1) (points.first() - points.last()).length() else 0.0

    private val pieces: Int get() = if (closed) points.size else points.size - 1

    fun meanSpacing(): Double = if (pieces <= 0) 1.0 else maxOf(lengthMeters / pieces, MinSpacingMeters)

    private fun next(i: Int) = (i + 1) % points.size

    private fun pieceLength(i: Int) =
        if (i == points.size - 1) lengthMeters - along[i] else along[i + 1] - along[i]

    /** Metres along the line nearest [p], running the way [heading] does when given, within [maxMeters]. */
    fun locate(p: Xy, heading: Xy?, maxMeters: Double): Double? {
        var best = maxMeters
        var at: Double? = null
        for (i in 0 until pieces) {
            val ab = points[next(i)] - points[i]
            if (ab.dot(ab) > 0.0 && sameWay(ab, heading)) {
                val u = fraction(i, p)
                val d = (p - (points[i] + ab * u)).length()
                if (d <= best) {
                    best = d
                    at = along[i] + u * pieceLength(i)
                }
            }
        }
        return at
    }

    /** Metres from [p] to the nearest point of the line. */
    fun distanceTo(p: Xy): Double = (0 until pieces).minOfOrNull { i ->
        val a = points[i]
        (p - (a + (points[next(i)] - a) * fraction(i, p))).length()
    } ?: (p - points.first()).length()

    /** How far along piece [i] the point nearest [p] is, 0 to 1. */
    private fun fraction(i: Int, p: Xy): Double {
        val a = points[i]
        val ab = points[next(i)] - a
        val len2 = ab.dot(ab)
        return if (len2 <= 0.0) 0.0 else ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
    }

    /** The point [meters] along the line. */
    fun pointAt(meters: Double): Xy {
        val (i, u) = pieceAt(meters)
        return points[i] + (points[next(i)] - points[i]) * u
    }

    /** The direction of travel [meters] along the line. */
    fun headingAt(meters: Double): Xy {
        val (i, _) = pieceAt(meters)
        return points[next(i)] - points[i]
    }

    private fun pieceAt(meters: Double): Pair<Int, Double> {
        val m = if (closed) meters.mod(lengthMeters) else meters.coerceIn(0.0, lengthMeters)
        var i = 0
        while (i < pieces - 1 && along[i + 1] <= m) i++
        val len = pieceLength(i)
        return i to if (len <= 0.0) 0.0 else ((m - along[i]) / len).coerceIn(0.0, 1.0)
    }

    /** Whether a stretch [RevisionChangeRunMeters] or longer is more than [RevisionChangeMeters] from [other]. */
    fun leaves(other: Polyline): Boolean {
        val off = points.map { other.distanceTo(it) > RevisionChangeMeters }
        var runStart = -1
        for (i in points.indices) {
            if (!off[i]) {
                runStart = -1
            } else {
                if (runStart < 0) runStart = i
                if (along[i] - along[runStart] >= RevisionChangeRunMeters) return true
            }
        }
        // On a loop, a stretch across the start/finish line is the run at the end and the one at the start.
        val headEnd = off.indexOfFirst { !it } - 1
        val wraps = closed && runStart > 0 && headEnd >= 0
        return wraps && lengthMeters - along[runStart] + along[headEnd] >= RevisionChangeRunMeters
    }
}

/**
 * Metres along [stored], made of the [kept] points of the denser [dense], at [denseMeters] along
 * [dense].
 */
private fun metresOnStored(stored: Polyline, kept: List<Int>, dense: Polyline, denseMeters: Double): Double {
    val k = kept.indexOfLast { dense.along[it] <= denseMeters }.coerceAtLeast(0)
    val last = k + 1 >= kept.size
    val from = dense.along[kept[k]]
    val to = if (last) dense.lengthMeters else dense.along[kept[k + 1]]
    val end = if (last) stored.lengthMeters else stored.along[k + 1]
    val u = if (to > from) ((denseMeters - from) / (to - from)).coerceIn(0.0, 1.0) else 0.0
    return stored.along[k] + u * (end - stored.along[k])
}
