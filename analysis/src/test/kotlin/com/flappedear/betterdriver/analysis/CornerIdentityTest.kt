@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.laps.rankLaps
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.timing.track.SegmentKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The track's corner identity in the lap analysis (FBBD-172). */
class CornerIdentityTest {
    /** [laps] with its fastest lap left out, so the next fastest becomes the reference lap. */
    private fun withoutFastest(laps: LapSession): LapSession {
        val fastest = assertNotNull(laps.fastestLapIndex)
        val marked = laps.timedLaps.mapIndexed { i, lap ->
            if (i == fastest) lap.copy(userExclusionReason = "test") else lap
        }
        val (ranked, next) = rankLaps(marked)
        return laps.copy(timedLaps = ranked, fastestLapIndex = next)
    }

    private fun sessions(): List<Pair<String, TelemetrySession>> {
        val sims = listOf(SimTrack.rectangle(), SimTrack.chicane(), SimTrack.hairpin()).map { sim ->
            sim.id to telemetrySessionOf(sim.session(laps = 5), sim.track())
        }
        val vbo = listOf("segments_mixed.vbo", "corners_measured.vbo", "segments_rectangle.vbo")
            .map { it to VboFixture.read(it) }
        return sims + vbo
    }

    @Test
    fun `a session analysed twice, and with another reference lap, keeps its corner ids and numbers`() {
        for ((name, session) in sessions()) {
            val first = analyzeSession(session)
            val identity = assertNotNull(first.identity, name)
            assertTrue(first.identified, name)
            assertEquals(1, identity.number, name)
            val ids = assertNotNull(first.segments).segments.map { it.id to it.name }
            // The first analysis names its segments by the identity it made.
            assertEquals(identity.segments.map { it.id to it.name }, ids, name)

            val again = analyzeSession(session, identity = identity)
            assertSame(identity, again.identity, name)
            assertEquals(ids, assertNotNull(again.segments).segments.map { it.id to it.name }, name)
            assertEquals(first.cornerMetrics.keys, again.cornerMetrics.keys, name)

            val laps = withoutFastest(deriveSourceLapSession(session))
            val other = analyzeSession(session, laps, identity)
            assertNotEquals(first.axisLapNumber, other.axisLapNumber, name)
            assertSame(identity, other.identity, "$name: another lap of the same track keeps the revision")
            val otherSegments = assertNotNull(other.segments).segments
            assertEquals(ids.sortedBy { it.first }, otherSegments.map { it.id to it.name }.sortedBy { it.first }, name)
            assertEquals(first.cornerMetrics.keys, other.cornerMetrics.keys, name)
            // Placed where the corners are: within a few metres of where the first lap had them.
            val length = assertNotNull(first.axis).lengthMeters
            for (s in otherSegments) {
                val was = first.segments!!.segments.single { it.id == s.id }
                val d = kotlin.math.abs(s.startProgressMeters - was.startProgressMeters)
                val moved = "$name ${s.name}: ${was.startProgressMeters} -> ${s.startProgressMeters}"
                assertTrue(minOf(d, length - d) < 15.0, moved)
            }
        }
    }

    @Test
    fun `a new chicane makes the next revision, with the old ids mapped onto it`() {
        val before = analyzeSession(telemetrySessionOf(SimTrack.rectangle().session(5), SimTrack.rectangle().track()))
        val r1 = assertNotNull(before.identity)
        val chicane = SimTrack.chicane()
        val after = analyzeSession(telemetrySessionOf(chicane.session(5), chicane.track()), identity = r1)
        val r2 = assertNotNull(after.identity)
        assertEquals(2, r2.number)
        assertTrue(after.identified)
        // Every corner and straight of the first revision is still on the track, so each maps to one.
        assertEquals(r1.segments.map { it.id }.toSet(), r2.previousIds.keys)
        // The four corners keep their ids; the chicane is a new corner, so the corners after it are renumbered.
        val oldCorners = r1.corners.map { it.id }
        assertEquals(4, oldCorners.size, "${r1.segments.map { it.name }}")
        for (id in oldCorners) assertEquals(id, r2.previousIds[id])
        val added = r2.corners.filter { it.id !in oldCorners }
        assertTrue(added.isNotEmpty(), "${r2.segments.map { it.id to it.name }}")
        // The top straight's middle is in the chicane now: it keeps its id for the stretch before it.
        val top = r1.segments.filter { it.kind == SegmentKind.Straight }.maxBy {
            (it.endMeters - it.startMeters).let { span -> if (span < 0) span + r1.lengthMeters else span }
        }
        assertEquals(top.id, r2.previousIds[top.id])
        assertEquals(SegmentKind.Straight, r2.segment(top.id)?.kind)
        // Corner numbers follow the lap: the chicane comes after the second corner.
        assertEquals(listOf(1, 2), r2.corners.filter { it.id in oldCorners.take(2) }.map { it.number })
        val names = "${r2.segments.map { it.id to it.name }}"
        assertEquals((3 until 3 + added.size).toList(), added.map { it.number }, names)
        val later = r2.corners.filter { it.id in oldCorners.drop(2) }.map { it.number }
        assertEquals(listOf(3, 4).map { it + added.size }, later, names)
        // Analysed again on the new layout, the second revision stays.
        val again = analyzeSession(telemetrySessionOf(chicane.session(4), chicane.track()), identity = r2)
        assertSame(r2, again.identity)
        assertEquals(
            r2.segments.map { it.id }.toSet(),
            assertNotNull(again.segments).segments.map { it.id }.toSet(),
        )
        val addedIds = added.map { it.id }
        assertTrue(again.segments!!.segments.any { it.type == TrackSegmentType.Corner && it.id in addedIds })
    }
}
