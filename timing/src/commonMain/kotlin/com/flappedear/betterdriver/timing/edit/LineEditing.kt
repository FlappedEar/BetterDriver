package com.flappedear.betterdriver.timing.edit

import com.flappedear.betterdriver.math.toDegrees
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.Gate
import com.flappedear.betterdriver.timing.track.DefaultLineWidthMeters
import com.flappedear.betterdriver.timing.track.MaxLineWidthMeters
import com.flappedear.betterdriver.timing.track.MinLineWidthMeters
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.atan2
import kotlin.math.roundToInt
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
 * Room a line fitted to the passes leaves on each side of the outermost pass, metres (FBBD-438):
 * GNSS error and a wider line next time. On Arek's Jastrząb and Silesia sessions the racing laps
 * cross start/finish within 6 m of each other, a sector line in a corner within 12 m.
 */
const val FittedLineMarginMeters = 4.0

/** The narrowest line fitted to the passes, metres: about a Polish circuit's width (FBBD-438). */
const val MinFittedLineWidthMeters = 12.0

/**
 * A pass counts towards a fitted line only at 0.4 to 2.5 times the tapped pass's speed (FBBD-454):
 * on Arek's Silesia Ring sessions the pit lane beside start/finish is driven at 16 to 49 km/h, the
 * straight at 98 to 137 km/h, and the slowest racing pass at a sector line is half the fastest.
 */
private val SameLaneSpeedShare = 0.4..2.5

private const val MicrosPerSecond = 1_000_000.0

/** Width steps of the editor's − and + (FBBD-438), metres. */
const val LineWidthStepMeters = 2.0

/**
 * [this] line [steps] widths of [LineWidthStepMeters] wider (narrower when negative), on whole
 * steps and within [MinLineWidthMeters]..[MaxLineWidthMeters]; centre and direction kept (FBBD-438).
 */
fun TrackLine.widened(steps: Int): TrackLine {
    val onStep = (widthMeters / LineWidthStepMeters).roundToInt() + steps
    return withWidth((onStep * LineWidthStepMeters).coerceIn(MinLineWidthMeters, MaxLineWidthMeters))
}

/**
 * A recorded drive as a line on the map, used to place timing lines from the car's own path
 * (UI-24 option A). [points] are the fixes in order.
 */
class LapPath(
    val points: List<GeoPoint>,
    /** When each point was taken, microseconds; null when unknown (then every pass counts as fast). */
    private val timesMicros: List<Long>? = null,
) {
    init {
        require(points.size >= 2) { "a path needs at least two points" }
        require(timesMicros == null || timesMicros.size == points.size) { "one time per point" }
    }

    companion object {
        /** The drive of [fixes] with their times, or null with fewer than two (FBBD-454). */
        fun of(fixes: List<Fix>): LapPath? = fixes.takeIf { it.size >= 2 }
            ?.let { f -> LapPath(f.map { GeoPoint(it.lat, it.lon) }, f.map { it.tMicros }) }
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
    fun lineAt(tap: GeoPoint, widthMeters: Double): TrackLine? =
        nearest(tap)?.let { TrackLine.across(it.point, it.headingDeg, widthMeters) }

    /**
     * A line across the path at the point nearest [tap], square to the car's travel and fitted to
     * every pass of the drive there (FBBD-438): centred between the outermost passes within
     * [DefaultLineWidthMeters] / 2 of the tapped one, [FittedLineMarginMeters] past each, at least
     * [MinFittedLineWidthMeters] wide, and never past [DefaultLineWidthMeters] / 2 from the tapped
     * pass. A pit lane further off than that, as at Silesia Ring, stays outside.
     */
    fun lineAt(tap: GeoPoint): TrackLine? {
        val hit = nearest(tap) ?: return null
        val probe = TrackLine.across(hit.point, hit.headingDeg, DefaultLineWidthMeters)
        val gate = Gate(probe, frame)
        val a = frame.toXy(probe.left)
        val along = frame.toXy(probe.right) - a
        val unit = along * (1.0 / along.length())
        // Each forward pass as metres from the probe's left end. Passes at a very different speed from
        // the tapped one are another lane: the pit lane beside a straight (FBBD-454).
        val tapped = speedAt(hit.segment)
        val passes = (0 until xy.size - 1).mapNotNull { i ->
            gate.crossing(xy[i], i.toLong(), xy[i + 1], (i + 1).toLong())
                ?.takeIf { i == hit.segment || sameLane(tapped, speedAt(i)) }
                ?.let { (it.at - a).dot(unit) }
        }
        // The tapped pass always crosses the probe; [half] stands in should rounding miss it.
        val half = DefaultLineWidthMeters / 2
        // The margins never reach past the probe's ends, so a pit lane the probe misses stays missed.
        var from = ((passes.minOrNull() ?: half) - FittedLineMarginMeters).coerceAtLeast(0.0)
        var to = ((passes.maxOrNull() ?: half) + FittedLineMarginMeters).coerceAtMost(DefaultLineWidthMeters)
        val short = MinFittedLineWidthMeters - (to - from)
        if (short > 0) {
            from = (from - short / 2).coerceIn(0.0, DefaultLineWidthMeters - MinFittedLineWidthMeters)
            to = from + MinFittedLineWidthMeters
        }
        val middle = frame.toGeo(a + unit * ((from + to) / 2))
        return TrackLine.across(middle, hit.headingDeg, to - from)
    }

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

    /** Speed over segment [i], metres per second; null without times. */
    private fun speedAt(i: Int): Double? {
        val t = timesMicros ?: return null
        val dt = (t[i + 1] - t[i]) / MicrosPerSecond
        return if (dt > 0) (xy[i + 1] - xy[i]).length() / dt else null
    }

    private fun sameLane(tapped: Double?, pass: Double?): Boolean =
        tapped == null || pass == null || tapped <= 0.0 || pass / tapped in SameLaneSpeedShare

    private fun headingAt(segment: Int): Double {
        var a = segment
        while (a > 0 && (xy[segment] - xy[a]).length() < HeadingSpanMeters) a--
        var b = segment + 1
        while (b < xy.size - 1 && (xy[b] - xy[segment + 1]).length() < HeadingSpanMeters) b++
        val d = xy[b] - xy[a]
        val deg = toDegrees(atan2(d.x, d.y))
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
