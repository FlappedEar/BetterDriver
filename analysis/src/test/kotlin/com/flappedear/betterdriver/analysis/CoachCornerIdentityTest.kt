@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.data.TimingGateType
import com.flappedear.betterdriver.analysis.data.declaredSpeedUnit
import com.flappedear.betterdriver.analysis.data.metresPerSecondPerSpeedUnit
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.coach.CoachFix
import com.flappedear.betterdriver.coach.LapTrace
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.coachFixes
import com.flappedear.betterdriver.coach.lapFrame
import com.flappedear.betterdriver.coach.tracedLaps
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The coach's corner N is the lap analysis's corner N (FBBD-172), on every fixture track: the
 * simulated circuits and every file of the analysis corpus with laps. The coach builds its reference
 * as it does in the app, from its own lap timer and its own braking corners; each braking corner's
 * apex is then placed on the analysis's own axis, independently of the track's centreline, and must
 * be in the analysis corner with the coach corner's CornerId and number.
 */
class CoachCornerIdentityTest {
    private class Fixture(val name: String, val session: TelemetrySession, val track: Track, val fixes: List<CoachFix>)

    private fun sims(): List<Fixture> =
        listOf(SimTrack.rectangle(), SimTrack.chicane("chicane"), SimTrack.hairpin()).map { sim ->
            val data = sim.session(laps = 5)
            Fixture(sim.id, telemetrySessionOf(data, sim.track()), sim.track(), data.coachFixes().toList())
        }

    private val corpus = listOf(
        "corners_gaps.vbo", "corners_inferred.vbo", "corners_measured.vbo", "event-laps.vbo",
        "laps_arc_minutes.vbo", "laps_clean.vbo", "laps_clockwise.vbo", "laps_end_inside_corridor.vbo",
        "laps_gps_gap.vbo", "laps_invalid_coordinate.vbo", "laps_long_trace.vbo", "racechrono_10_2_4.vbo",
        "segments_mixed.vbo", "segments_mixed_gps_gap.vbo", "segments_mixed_mirrored.vbo", "segments_rectangle.vbo",
    )

    /** A corpus file as the coach would have driven it: its start gate as the line, its GNSS as fixes. */
    private fun vbo(name: String): Fixture? {
        val session = VboFixture.read(name)
        val gate = session.timingGates.firstOrNull { it.type == TimingGateType.Start }
        val lat = session.channel(Aliases.LATITUDE)
        val speed = session.channel(Aliases.SPEED)
        if (gate == null || lat == null || speed == null) return null
        val unit = declaredSpeedUnit(session, speed.name).ifBlank { "km/h" }
        val toKmh = (metresPerSecondPerSpeedUnit(unit) ?: (1 / 3.6)) * 3.6
        val fixes = lat.timestamps.indices.mapNotNull { i ->
            val t = lat.timestamps[i]
            val lo = session.valueAt(Aliases.LONGITUDE, t)
            val v = session.valueAt(Aliases.SPEED, t)
            val position = runCatching { GeoPoint(lat.values[i], lo!!) }.getOrNull()
            if (position == null || v == null) null else CoachFix((t * 1e6).toLong(), lat.values[i], lo!!, v * toKmh)
        }
        fun point(c: GeoCoordinate) = GeoPoint(c.latitudeDegrees, c.longitudeDegrees)
        val line = TrackLine(point(gate.endpointA), point(gate.endpointB))
        val center = point(gate.endpointA)
        // The coach times laps one way across the line; the corpus does not say which.
        val track = listOf(line, line.flipped())
            .map { Track("vbo", name, center, startFinish = it) }
            .maxBy { t -> tracedLaps(t, fixes.asSequence()).count { it.lap.kind == LapKind.Timed } }
        return Fixture(name, session, track, fixes)
    }

    private fun pointAt(path: LapTrace, meters: Double): Xy {
        val along = path.along
        var i = 0
        while (i < along.size - 2 && along[i + 1] < meters) i++
        val span = along[i + 1] - along[i]
        val u = if (span <= 0.0) 0.0 else ((meters - along[i]) / span).coerceIn(0.0, 1.0)
        return path.points[i] + (path.points[i + 1] - path.points[i]) * u
    }

    /** The analysis corner the geo point [p], driven heading [heading] (east, north), is in on [analysis]'s axis. */
    private fun analysisCornerAt(analysis: SessionAnalysis, p: GeoPoint, heading: Xy): TrackSegment {
        val axis = assertNotNull(analysis.axis)
        val at = projectCoordinate(GeoCoordinate(p.lat, p.lon), axis.origin)
        val n = axis.points.size
        val best = axis.points.indices.filter { i ->
            val a = axis.points[i]
            val b = axis.points[(i + 1) % n]
            (b.eastMeters - a.eastMeters) * heading.x + (b.northMeters - a.northMeters) * heading.y > 0
        }.minBy { hypot(axis.points[it].eastMeters - at.eastMeters, axis.points[it].northMeters - at.northMeters) }
        val progress = axis.cumulative[best]
        val length = axis.lengthMeters
        val segments = assertNotNull(analysis.segments).segments
        fun inside(s: TrackSegment) = if (s.startProgressMeters <= s.endProgressMeters) {
            progress >= s.startProgressMeters && progress < s.endProgressMeters
        } else {
            progress >= s.startProgressMeters || progress < s.endProgressMeters
        }
        fun gap(a: Double) = abs(a - progress).let { minOf(it, length - it) }
        val found = segments.first(::inside)
        return if (found.type == TrackSegmentType.Corner) {
            found
        } else {
            segments.filter { it.type == TrackSegmentType.Corner }
                .minBy { minOf(gap(it.startProgressMeters), gap(it.endProgressMeters)) }
        }
    }

    /** Checks every braking corner of the coach's reference at [f]; returns how many there were. */
    private fun checkCorners(f: Fixture): Int {
        val analysis = analyzeSession(f.session)
        val track = f.track.copy(identity = analysis.identity)
        val laps = tracedLaps(track, f.fixes.asSequence())
        val identity = analysis.identity
        val reference = identity?.let { buildReference(track, listOf(SessionLaps(f.name, laps))) } ?: return 0
        assertTrue(analysis.identified, f.name)
        val frame = assertNotNull(lapFrame(track))
        val path = reference.path()
        for (c in reference.corners) {
            val apex = pointAt(path, c.apexAt)
            val heading = pointAt(path, c.apexAt + 5.0) - pointAt(path, c.apexAt - 5.0)
            val corner = analysisCornerAt(analysis, frame.toGeo(apex), heading)
            assertEquals(corner.id, c.cornerId, "${f.name}: coach corner ${c.number} at ${c.apexAt} m")
            val numbers = identity.segment(corner.id)!!.let { it.number..it.lastNumber }
            assertTrue(assertNotNull(c.turnNumber) in numbers, "${f.name}: T${c.turnNumber} is ${corner.name}")
            assertEquals(c.turnNumber, c.label)
        }
        return reference.corners.size
    }

    @Test
    fun `coach corner N is analysis corner N on every fixture track`() {
        val checked = (sims() + corpus.mapNotNull(::vbo)).associate { it.name to checkCorners(it) }
        println("braking corners checked per fixture: $checked")
        // Not vacuous: the simulated circuits and several corpus tracks have braking corners to compare.
        assertTrue(listOf("rectangle", "chicane", "hairpin").all { (checked[it] ?: 0) >= 3 }, "$checked")
        assertTrue(checked.count { it.value > 0 } >= 5, "$checked")
    }
}
