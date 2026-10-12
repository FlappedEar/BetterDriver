@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.confidence.ClaimCodes
import com.flappedear.betterdriver.analysis.confidence.Codes
import com.flappedear.betterdriver.analysis.confidence.bestGapConfidence
import com.flappedear.betterdriver.analysis.confidence.gainConfidence
import com.flappedear.betterdriver.analysis.confidence.lossConfidence
import com.flappedear.betterdriver.analysis.focus.FocusAreaKind
import com.flappedear.betterdriver.analysis.focus.sessionFocus
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.SectorTime
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestRow
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestSummary
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.geo.Xy
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Confidence-gated advice (FBBD-177): corners whose positions are weak are graded Low and give no
 * numeric advice; an improvement seen on one lap only is never a focus area.
 */
class ConfidenceGatingTest {
    private val sim = SimTrack.hairpin()

    /** The hairpin of [SimTrack.hairpin]: a right half-turn of 20 m radius around this centre, after 400 m. */
    private val centre = Xy(400.0, -20.0)
    private val apex = Xy(420.0, -20.0)

    private fun xyOf(set: SampleSet): Xy {
        val lat = set.values.first { it.channelId == GnssChannels.LAT.id }.value
        val lon = set.values.first { it.channelId == GnssChannels.LON.id }.value
        return sim.frame.toXy(lat, lon)
    }

    private fun moved(set: SampleSet, to: Xy): SampleSet {
        val g = sim.geo(to)
        return set.copy(
            values = set.values.map {
                when (it.channelId) {
                    GnssChannels.LAT.id -> ChannelValue(it.channelId, g.lat)
                    GnssChannels.LON.id -> ChannelValue(it.channelId, g.lon)
                    else -> it
                }
            },
        )
    }

    private fun analyse(edit: (List<SampleSet>) -> List<SampleSet>): SessionAnalysis {
        val data = sim.session(laps = 5)
        return analyzeSession(telemetrySessionOf(data.copy(sampleSets = edit(data.sampleSets)), sim.track()))
    }

    /** The corner of [analysis] the hairpin's apex is in. */
    private fun hairpinCorner(analysis: SessionAnalysis): TrackSegment {
        val axis = assertNotNull(analysis.axis)
        val g = sim.geo(apex)
        val p = projectCoordinate(GeoCoordinate(g.lat, g.lon), axis.origin)
        val i = axis.points.indices.minBy {
            hypot(axis.points[it].eastMeters - p.eastMeters, axis.points[it].northMeters - p.northMeters)
        }
        val at = axis.cumulative[i]
        return assertNotNull(analysis.segments).segments.single {
            it.type == TrackSegmentType.Corner && at >= it.startProgressMeters && at <= it.endProgressMeters
        }
    }

    /** No focus area at [corner], and nothing it shows carries a number of seconds, metres or km/h. */
    private fun assertNoNumericAdvice(analysis: SessionAnalysis, corner: TrackSegment, affected: List<Int>) {
        val focus = sessionFocus(analysis)
        assertTrue(focus.areas.none { it.area.segmentId == corner.id }, "focus ${focus.areas.map { it.area.name }}")
        for (i in affected) {
            val lap = analysis.laps.timedLaps[i].number
            assertEquals(Confidence.LOW, analysis.lossConfidence(corner.id, i, 0.2).level)
            assertEquals(Confidence.LOW, analysis.gainConfidence(corner.id, i, 0.2).level)
            assertEquals(Confidence.LOW, analysis.bestGapConfidence(corner.id, 0.2, lap).level)
        }
        for (area in focus.areas) {
            if (area.confidence.level != Confidence.HIGH) {
                val numbers = Regex("\\d+\\.\\d+ ?(s|m|km/h)")
                assertFalse(numbers.containsMatchIn(area.area.observation), area.area.observation)
            }
        }
    }

    @Test
    fun `a clean session is not graded down`() {
        val analysis = analyse { it }
        val corner = hairpinCorner(analysis)
        for (i in analysis.laps.timedLaps.indices) {
            val graded = analysis.confidence.lapOnly(corner.id, i)
            assertTrue(graded.level != Confidence.LOW, "lap ${i + 1}: ${graded.why}")
        }
        assertFalse(analysis.confidence.session.level == Confidence.LOW, analysis.confidence.session.why)
    }

    @Test
    fun `a GNSS dropout in the hairpin grades it Low on every lap and gives no numeric advice there`() {
        // The receiver holds its last position for the 24 m of the hairpin around the apex.
        val analysis = analyse { sets ->
            var held: Xy? = null
            sets.map { set ->
                val p = xyOf(set)
                if (hypot(p.x - apex.x, p.y - apex.y) < 12.0) {
                    val h = held ?: p.also { held = it }
                    moved(set, h)
                } else {
                    held = null
                    set
                }
            }
        }
        val corner = hairpinCorner(analysis)
        assertTrue(analysis.laps.timedLaps.size >= 4)
        for (i in analysis.laps.timedLaps.indices) {
            val graded = analysis.confidence.lap(corner.id, i)
            assertEquals(Confidence.LOW, graded.level, "lap ${i + 1}: ${graded.why}")
            assertTrue(graded.has(Codes.GNSS_GAP), graded.why)
            assertEquals(Confidence.LOW, analysis.lossConfidence(corner.id, i, 0.2).level)
        }
        assertNoNumericAdvice(analysis, corner, analysis.laps.timedLaps.indices.toList())
        // The corners it did not touch keep their confidence.
        val others = assertNotNull(analysis.segments).segments.filter { it.id != corner.id }
        assertTrue(others.any { analysis.confidence.lap(it.id, 0).level != Confidence.LOW })
    }

    @Test
    fun `a position on the other leg of the hairpin grades it Low and gives no numeric advice there`() {
        // On the way out, 30 degrees past the apex, one position a lap lands on the way in, 21 m back
        // along the track. Not on the last lap: the fastest, whose line is the axis.
        val out = Xy(centre.x + 17.32, centre.y - 10.0)
        val mirrored = Xy(out.x, 2 * centre.y - out.y)
        val analysis = analyse { sets ->
            var done = -1.0
            var injected = 0
            sets.map { set ->
                val p = xyOf(set)
                val t = set.tMicros / 1e6
                if (hypot(p.x - out.x, p.y - out.y) < 2.0 && t - done > 10.0 && injected < 4) {
                    done = t
                    injected++
                    moved(set, mirrored)
                } else {
                    set
                }
            }
        }
        val corner = hairpinCorner(analysis)
        val fastest = assertNotNull(analysis.laps.fastestLapIndex)
        assertEquals(analysis.laps.timedLaps.lastIndex, fastest)
        val affected = analysis.laps.timedLaps.indices.filter { it != fastest }
        assertTrue(affected.size >= 3)
        for (i in affected) {
            val graded = analysis.confidence.lap(corner.id, i)
            assertEquals(Confidence.LOW, graded.level, "lap ${i + 1}: ${graded.why}")
            assertTrue(graded.has(Codes.HAIRPIN), graded.why)
        }
        assertFalse(analysis.confidence.lap(corner.id, fastest).has(Codes.HAIRPIN))
        assertNoNumericAdvice(analysis, corner, affected)
    }

    // A session of five laps through one corner, built by hand: lap 2 is the fastest; its time
    // through the corner is beaten by [faster] laps.
    private fun oneCorner(faster: Map<Int, Double>): SessionAnalysis {
        val times = mapOf(1 to 10.50, 2 to 10.50, 3 to 10.52, 4 to 10.48, 5 to 10.51) + faster
        val laps = (1..5).map { n -> TimedLap(n, n * 100.0, n * 100.0 + 80.0 + (if (n == 2) 0.0 else 0.5), 80.0) }
        val sectors = times.toSortedMap().map { (n, t) ->
            val sector = SectorTime("c1", "Turn 1", TrackSegmentType.Corner, 0.0, 100.0, seconds = t)
            LapSectorTimes(n, sectors = listOf(sector))
        }
        val (source, best) = times.minBy { it.value }
        val row = TheoreticalBestRow(
            "c1", "Turn 1", TrackSegmentType.Corner, 0.0, 100.0, best, source,
            actualSeconds = 10.50, lossSeconds = 10.50 - best,
        )
        return SessionAnalysis(
            LapSession(LapSessionStatus.entries.first(), timedLaps = laps, fastestLapIndex = 1),
            axisLapNumber = 2,
            segments = ApprovedSegments(listOf(TrackSegment("c1", "Turn 1", TrackSegmentType.Corner, 0.0, 100.0)), "t"),
            lapTimes = sectors,
            summary = TheoreticalBestSummary(listOf(row), listOf(row)),
        )
    }

    @Test
    fun `a corner improvement seen on one lap only is never a focus area`() {
        val once = sessionFocus(oneCorner(mapOf(4 to 10.10)))
        assertTrue(once.areas.none { it.area.kind == FocusAreaKind.SectorGap }, "${once.areas}")
        val withheld = once.withheld.single()
        assertEquals("c1", withheld.segmentId)
        assertTrue(withheld.confidence.has(ClaimCodes.ONE_LAP), withheld.confidence.why)
        assertEquals(Confidence.LOW, withheld.confidence.level)

        // Seen on two laps, it is.
        val twice = sessionFocus(oneCorner(mapOf(4 to 10.10, 5 to 10.12)))
        val area = twice.areas.single { it.area.kind == FocusAreaKind.SectorGap }
        assertEquals("c1", area.area.segmentId)
        assertTrue(area.confidence.level != Confidence.LOW)
        assertTrue(twice.withheld.isEmpty())
    }
}
