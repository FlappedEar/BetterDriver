package com.flappedear.betterdriver.timing.edit

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Gate
import com.flappedear.betterdriver.timing.track.DefaultLineWidthMeters
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.atan2
import kotlin.math.sqrt

/** One line of a track, as the editor lists them (UI-23). */
sealed interface LineRef {
    /** The start/finish line; the start line of a point-to-point track. */
    data object StartFinish : LineRef

    /** Sector line [index], in lap order. */
    data class Sector(val index: Int) : LineRef

    data object PitEntry : LineRef

    data object PitExit : LineRef

    /** The finish line of a point-to-point track (FBBD-155). */
    data object Finish : LineRef
}

/** The line [ref] names, or null if it is not set. */
fun Track.line(ref: LineRef): TrackLine? = when (ref) {
    LineRef.StartFinish -> startFinish
    is LineRef.Sector -> sectorLines.getOrNull(ref.index)
    LineRef.PitEntry -> pitEntry
    LineRef.PitExit -> pitExit
    LineRef.Finish -> finish
}

/**
 * The track with [ref] set to [line]. A null line clears it; for a sector line that removes it, so
 * the later ones move up. A sector index one past the end adds a line.
 */
fun Track.withLine(ref: LineRef, line: TrackLine?): Track = when (ref) {
    LineRef.StartFinish -> copy(startFinish = line)
    LineRef.PitEntry -> copy(pitEntry = line)
    LineRef.PitExit -> copy(pitExit = line)
    LineRef.Finish -> copy(finish = line)
    is LineRef.Sector -> {
        val lines = sectorLines.toMutableList()
        when {
            line == null -> if (ref.index in lines.indices) lines.removeAt(ref.index)
            ref.index in lines.indices -> lines[ref.index] = line
            ref.index == lines.size -> lines += line
            else -> throw IndexOutOfBoundsException("no sector line ${ref.index}")
        }
        copy(sectorLines = lines)
    }
}

/**
 * Every line of the track's kind, in the editor's order: a circuit's pit lines, or a point-to-point
 * track's finish line (FBBD-155), come after the sector lines.
 */
fun Track.lineRefs(): List<LineRef> =
    listOf(LineRef.StartFinish) +
        sectorLines.indices.map { LineRef.Sector(it) } +
        if (pointToPoint) listOf(LineRef.Finish) else listOf(LineRef.PitEntry, LineRef.PitExit)
/** A place on a recorded path: the nearest point to a tap, and which way the car was going there. */
data class PathHit(
    val point: GeoPoint,
    /** Direction of travel, degrees clockwise from north. */
    val headingDeg: Double,
    /** Index of the path segment the point is on. */
    val segment: Int,
    /** Distance from the tap to the path, metres. */
    val offMeters: Double,
)

/** How far a tap may be from the recorded lap and still place a line on it, metres. */
const val MaxTapOffPathMeters = 30.0

/** Heading is taken over this much path on each side of the hit, to smooth GNSS noise, metres. */
private const val HeadingSpanMeters = 6.0

/**
 * A recorded drive as a line on the map, used to place timing lines from the car's own path
 * (UI-24 option A). [points] are the fixes in order.
 */
class LapPath(val points: List<GeoPoint>) {
    init {
        require(points.size >= 2) { "a path needs at least two points" }
    }

    val frame = LocalFrame(points.first())
    private val xy = points.map(frame::toXy)

    /** The nearest point of the path to [tap], if it is within [maxOffMeters]. */
    fun nearest(tap: GeoPoint, maxOffMeters: Double = MaxTapOffPathMeters): PathHit? {
        val p = frame.toXy(tap)
        var best = -1
        var bestAt = xy[0]
        var bestD2 = Double.MAX_VALUE
        for (i in 0 until xy.size - 1) {
            val at = closestOnSegment(p, xy[i], xy[i + 1])
            val d = at - p
            val d2 = d.dot(d)
            if (d2 < bestD2) {
                bestD2 = d2
                best = i
                bestAt = at
            }
        }
        val off = sqrt(bestD2)
        if (best < 0 || off > maxOffMeters) return null
        return PathHit(frame.toGeo(bestAt), headingAt(best), best, off)
    }

    /** A line [widthMeters] wide across the path at the point nearest [tap], square to the car's travel. */
    fun lineAt(tap: GeoPoint, widthMeters: Double = DefaultLineWidthMeters): TrackLine? =
        nearest(tap)?.let { TrackLine.across(it.point, it.headingDeg, widthMeters) }

    /**
     * [line] turned so the car crosses it forwards along this path. Unchanged when the path crosses
     * it both ways, or not at all (a line placed with two taps away from the recorded lap).
     */
    fun oriented(line: TrackLine): TrackLine {
        val forwards = firstCrossing(line) != null
        val backwards = firstCrossing(line.flipped()) != null
        return if (!forwards && backwards) line.flipped() else line
    }

    /** Index of the first segment that crosses [line] forwards, at or after segment [from]. */
    fun firstCrossing(line: TrackLine, from: Int = 0): Int? {
        val gate = Gate(line, frame)
        for (i in from until xy.size - 1) {
            if (gate.crossing(xy[i], i.toLong(), xy[i + 1], (i + 1).toLong()) != null) return i
        }
        return null
    }

    private fun headingAt(segment: Int): Double {
        var a = segment
        while (a > 0 && (xy[segment] - xy[a]).length() < HeadingSpanMeters) a--
        var b = segment + 1
        while (b < xy.size - 1 && (xy[b] - xy[segment + 1]).length() < HeadingSpanMeters) b++
        val d = xy[b] - xy[a]
        val deg = Math.toDegrees(atan2(d.x, d.y))
        return (deg + FullCircle) % FullCircle
    }

    private fun closestOnSegment(p: Xy, a: Xy, b: Xy): Xy {
        val ab = b - a
        val len2 = ab.dot(ab)
        if (len2 == 0.0) return a
        val t = ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        return a + ab * t
    }
}

private const val FullCircle = 360.0

/**
 * The track with its sector lines in the order the car crosses them after start/finish on [path].
 * Lines the path does not cross keep their order after the others. Without a start/finish line, or
 * if the path never crosses it, the track is returned unchanged.
 */
@Suppress("ReturnCount") // Guard clauses: no line to start from, or the path never crosses it.
fun Track.sectorsInLapOrder(path: LapPath): Track {
    val sf = startFinish ?: return this
    val start = path.firstCrossing(sf) ?: return this
    // Each line's first crossing after the start/finish crossing.
    val order = sectorLines.withIndex().sortedBy { (i, line) ->
        val at = path.firstCrossing(line, start)
        at?.toLong() ?: (Long.MAX_VALUE / 2 + i)
    }
    return copy(sectorLines = order.map { it.value })
}
