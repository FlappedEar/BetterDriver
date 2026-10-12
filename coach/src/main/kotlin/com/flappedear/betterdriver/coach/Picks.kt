package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A corner the driver picked in a session's lap analysis for the coach to work on next time
 * (FBBD-148). The analysis and the coach find and number corners differently (see [pickedCorners]),
 * so a pick is kept as where the corner is: its line on the analysed session's fastest lap, latitude
 * and longitude every few metres from where the corner starts to where it ends, in driving order.
 */
@Serializable
data class CornerPick(
    /** The analysis's name for it, "Corner 3", as the coach settings page lists it. */
    val name: String,
    val lats: List<Double>,
    val lons: List<Double>,
    /** The session it was picked in. */
    val sessionId: String = "",
) {
    init {
        require(lats.isNotEmpty() && lats.size == lons.size) { "a pick needs a line" }
    }

    /** The line in [frame]. */
    fun line(frame: LocalFrame): List<Xy> = lats.indices.map { frame.toXy(lats[it], lons[it]) }

    /**
     * The same corner as [other], picked in another session or on another line: the middle of each
     * line is within [SamePickMeters] of the other line. Toggling a corner in the analysis uses it, so a
     * corner picked in one session shows as picked in every session at the track.
     */
    fun sameCorner(other: CornerPick): Boolean {
        val frame = LocalFrame(GeoPoint(lats.first(), lons.first()))
        val a = line(frame)
        val b = other.line(frame)
        return nearest(b, a[a.size / 2]).distance <= SamePickMeters &&
            nearest(a, b[b.size / 2]).distance <= SamePickMeters
    }
}

/** The middle of one pick's line is this close to another's for both to be the same corner. */
const val SamePickMeters = 15.0

/** A coach corner's apex is this close to a picked line for the corner to be picked. */
const val PickMatchMeters = 25.0

/** The direction of travel at an apex is taken over this far either side of it. */
private const val ApexHeadingMeters = 5.0

/** [picks] with [pick] added, or without it (and anything else at the same corner) if it was there. */
fun List<CornerPick>.toggled(pick: CornerPick): List<CornerPick> =
    if (any { it.sameCorner(pick) }) filterNot { it.sameCorner(pick) } else this + pick

/**
 * The corners of [map] the driver picked (FBBD-148), by number. Matched by position, never by number:
 * the lap analysis splits its fastest lap into corners by curvature ("Corner 3", flat-out bends too,
 * a quick left-right may be one "Corners 3–4"), while the coach lists only the corners its reference
 * lap brakes for, numbered in lap order. Distances along a lap do not line up either: each module
 * measures from the start/finish line along its own reference lap, on its own line.
 *
 * So a coach corner is picked when its apex, the slowest point of the reference lap there, is within
 * [PickMatchMeters] of a picked line and the reference lap there runs the same way as the line (not
 * a neighbouring stretch of track driven the other way). A pick that covers two braking corners picks
 * both; a pick of a bend taken flat out matches nothing and is never cued.
 */
fun pickedCorners(map: CornerMap, frame: LocalFrame, picks: List<CornerPick>): Set<Int> {
    if (picks.isEmpty()) return emptySet()
    val lines = picks.map { it.line(frame) }
    return map.corners.filter { c ->
        val apex = pointAt(map.path, c.apexAt)
        val heading = pointAt(map.path, c.apexAt + ApexHeadingMeters) - pointAt(map.path, c.apexAt - ApexHeadingMeters)
        lines.any { line ->
            val near = nearest(line, apex)
            near.distance <= PickMatchMeters && (near.direction == null || near.direction.dot(heading) > 0)
        }
    }.map { it.number }.toSet()
}

/** The point of [path] at [meters] along it, interpolated. */
internal fun pointAt(path: LapTrace, meters: Double): Xy {
    val m = meters.coerceIn(0.0, path.lengthMeters)
    val along = path.along
    var i = 0
    while (i < along.size - 2 && along[i + 1] < m) i++
    val span = along[i + 1] - along[i]
    val u = if (span <= 0.0) 0.0 else ((m - along[i]) / span).coerceIn(0.0, 1.0)
    return path.points[i] + (path.points[i + 1] - path.points[i]) * u
}

/** How far a point is from a line, and the line's direction there (null for a line of one point). */
private class Nearest(val distance: Double, val direction: Xy?)

private fun nearest(line: List<Xy>, p: Xy): Nearest {
    if (line.size == 1) return Nearest((p - line[0]).length(), null)
    var best = Nearest(Double.MAX_VALUE, null)
    for (i in 0 until line.size - 1) {
        val a = line[i]
        val ab = line[i + 1] - a
        val len2 = ab.dot(ab)
        val u = if (len2 <= 0.0) 0.0 else ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        val d = (p - (a + ab * u)).length()
        if (d < best.distance) best = Nearest(d, ab.takeIf { len2 > 0.0 })
    }
    return best
}

/** One track's picks, as stored. */
@Serializable
data class CoachPicks(
    val format: String = Format,
    val version: Int = Version,
    val trackId: String,
    val picks: List<CornerPick>,
) {
    companion object {
        const val Format = "betterdriver-coach-picks"
        const val Version = 1
    }
}

/**
 * The picked corners of each track, one `<track id>.json` in [dir] (FBBD-148). The app writes them from
 * the lap analysis; the coach reads them at each session start, so a change applies from the next
 * session. Writes are synced and renamed into place; a file that cannot be read counts as no picks.
 */
class CoachPicksStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(trackId: String): List<CornerPick> =
        read(fileFor(trackId))?.takeIf { it.trackId == trackId }?.picks.orEmpty()

    /** Every track with picks, by track id. */
    fun all(): Map<String, List<CornerPick>> = dir.listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
        .mapNotNull { f -> read(f)?.takeIf { f.name == it.trackId + Suffix && it.picks.isNotEmpty() } }
        .sortedBy { it.trackId }
        .associate { it.trackId to it.picks }

    /** Saves [picks] for [trackId]; none deletes the file. */
    fun save(trackId: String, picks: List<CornerPick>) {
        val f = fileFor(trackId)
        if (picks.isEmpty()) {
            f.delete()
        } else {
            val text = json.encodeToString(CoachPicks.serializer(), CoachPicks(trackId = trackId, picks = picks))
            writeAtomically(f, text)
        }
    }

    private fun read(f: File): CoachPicks? {
        if (!f.isFile) return null
        return try {
            json.decodeFromString(CoachPicks.serializer(), f.readText())
                .takeIf { it.format == CoachPicks.Format && it.version == CoachPicks.Version }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun fileFor(trackId: String) = File(dir, checkedTrackId(trackId) + Suffix)

    private companion object {
        const val Suffix = ".json"
    }
}
