package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.optimal.JointThresholds
import com.flappedear.betterdriver.analysis.optimal.OptimalKind
import com.flappedear.betterdriver.analysis.optimal.RealisticOptimalLap
import com.flappedear.betterdriver.analysis.optimal.RealisticSector
import com.flappedear.betterdriver.analysis.optimal.OptimalLap
import com.flappedear.betterdriver.analysis.optimal.OptimalLapUnavailable
import com.flappedear.betterdriver.analysis.optimal.optimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.optimalLap
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.SectorTime
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestLap
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestSector
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.video.VideoClip
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptimalClipPlanTest {
    // A 1,000 m lap of three segments: Straight 1 (0-300 m), Corner 1 (300-600 m), Straight 2
    // (600-1,000 m). Three laps of 60 s from telemetry time 10 s; lap 3 has a GPS gap and does not count.
    private val segments = ApprovedSegments(
        listOf(
            TrackSegment("s0", "Straight 1", TrackSegmentType.Straight, 0.0, 300.0),
            TrackSegment("s1", "Corner 1", TrackSegmentType.Corner, 300.0, 600.0),
            TrackSegment("s2", "Straight 2", TrackSegmentType.Straight, 600.0, 1_000.0),
        ),
        revision = "auto-lap-1",
    )

    private fun lap(number: Int, start: Double, seconds: Double, issue: LapReferenceIssue = LapReferenceIssue.None) =
        TimedLap(number, start, start + seconds, seconds, referenceIssue = issue)

    private fun sector(s: TrackSegment, start: Double, end: Double) = SectorTime(
        s.id, s.name, s.type, s.startProgressMeters, s.endProgressMeters,
        startTime = start, endTime = end, seconds = end - start,
    )

    /** Lap [n] from [start]; its segments end [a], [b] and [c] seconds into the lap. */
    private fun times(n: Int, start: Double, a: Double, b: Double, c: Double) = LapSectorTimes(
        n,
        sectors = listOf(
            sector(segments.segments[0], start, start + a),
            sector(segments.segments[1], start + a, start + b),
            sector(segments.segments[2], start + b, start + c),
        ),
        valid = true,
    )

    private fun best(vararg sectors: Pair<Double, Int>) = TheoreticalBestLap(
        sectors = sectors.mapIndexed { i, (seconds, lap) ->
            val s = segments.segments[i]
            TheoreticalBestSector(s.id, s.name, s.type, seconds, lap)
        },
        totalSeconds = sectors.sumOf { it.first },
        valid = true,
    )

    // Lap 1: 20 + 22 + 18 = 60 s (fastest). Lap 2: 19 + 23 + 19 = 61 s. Lap 3 is fastest
    // through Corner 1 but does not count. Theoretical best: Straight 1 from lap 2 (19 s),
    // Corner 1 from lap 1 (22 s), Straight 2 from lap 1 (18 s): 59 s.
    private val analysis = SessionAnalysis(
        LapSession(
            LapSessionStatus.Available,
            timedLaps = listOf(lap(1, 10.0, 60.0), lap(2, 70.0, 61.0), lap(3, 131.0, 59.0, LapReferenceIssue.GpsGap)),
            fastestLapIndex = 0,
        ),
        segments = segments,
        lapTimes = listOf(
            times(1, 10.0, 20.0, 42.0, 60.0),
            times(2, 70.0, 19.0, 42.0, 61.0),
            times(3, 131.0, 20.0, 41.0, 59.0),
        ),
        theoreticalBest = best(19.0 to 2, 22.0 to 1, 18.0 to 1),
    )

    /** Session clock: telemetry time 0 is 5 s of session time. */
    private val origin = 5_000_000L

    private fun clip(file: String, startSeconds: Double, endSeconds: Double) =
        VideoClip("phone", file, (startSeconds * 1e6).toLong(), (endSeconds * 1e6).toLong())

    private fun lengthOf(c: VideoClip): Long? = c.endMicros?.let { it - c.startMicros }

    private fun optimal(): OptimalLap = assertNotNull(optimalLap(analysis, OptimalKind.RawTheoreticalBest).lap)

    @Test
    fun `each segment comes from the lap that won it, in track order, adding up to the theoretical best`() {
        val lap = optimal()
        assertEquals(listOf(2, 1, 1), lap.parts.map { it.lapNumber })
        assertEquals(listOf("Straight 1", "Corner 1", "Straight 2"), lap.parts.map { it.name })
        assertEquals(listOf(70.0, 30.0, 52.0), lap.parts.map { it.startTime })
        assertEquals(listOf(89.0, 52.0, 70.0), lap.parts.map { it.endTime })
        assertEquals(listOf(0.0, 19.0, 41.0), lap.parts.map { it.offsetSeconds })
        assertEquals(59.0, lap.totalSeconds)
        assertEquals(59.0, lap.parts.sumOf { it.seconds })
        assertEquals(listOf(1, 2), lap.laps)
        assertEquals(1, lap.fastestLapNumber)
        assertEquals(listOf(20.0, 22.0, 18.0), lap.parts.map { it.referenceSeconds })
    }

    @Test
    fun `segment boundaries map to media times across clips, laid back to back`() {
        // Session time = telemetry time + 5 s. Clip a covers 0-60 s of session time, clip b 60-200 s.
        val plan = optimalClipPlan(
            optimal(),
            listOf(clip("a.mp4", 0.0, 60.0), clip("b.mp4", 60.0, 200.0)),
            ::lengthOf,
            origin,
        )
        // Straight 1 of lap 2: telemetry 70-89 s = session 75-94 s, all in clip b (media 15-34 s).
        // Corner 1 of lap 1: session 35-57 s, in clip a (media 35-57 s).
        // Straight 2 of lap 1: session 57-75 s, over the seam: clip a 57-60 s, then clip b 0-15 s.
        val cuts = plan.cuts.map { it.cut }
        assertEquals(listOf("b.mp4", "a.mp4", "a.mp4", "b.mp4"), cuts.map { it.clip.file })
        assertEquals(listOf(15_000_000L, 35_000_000L, 57_000_000L, 0L), cuts.map { it.fromMicros })
        assertEquals(listOf(34_000_000L, 57_000_000L, 60_000_000L, 15_000_000L), cuts.map { it.toMicros })
        assertEquals(listOf(0L, 19_000_000L, 41_000_000L, 44_000_000L), cuts.map { it.outputStartMicros })
        assertEquals(listOf(0, 1, 2, 2), plan.cuts.map { it.partIndex })
        assertEquals(59_000_000L, plan.totalMicros)
        assertTrue(plan.missing.isEmpty())
        assertTrue(plan.partial.isEmpty())

        // A frame 10 s into the film is 10 s into Straight 1 of lap 2.
        val first = plan.cuts[0]
        val session = first.cut.sessionMicrosAt(10_000_000L)
        assertEquals(85_000_000L, session)
        assertEquals(10_000_000L, first.theoreticalMicrosAt(session))
        // 2 s into the second clip of Straight 2: 41 + 3 + 2 = 46 s into the theoretical lap.
        val last = plan.cuts[3]
        val t = last.cut.sessionMicrosAt(46_000_000L)
        assertEquals(62_000_000L, t)
        assertEquals(46_000_000L, last.theoreticalMicrosAt(t))
    }

    @Test
    fun `a part without video is skipped and listed, and the running time jumps over it`() {
        // Only lap 1 is on video (session 15-75 s): Straight 1 of lap 2 has none.
        val plan = optimalClipPlan(optimal(), listOf(clip("a.mp4", 15.0, 75.0)), ::lengthOf, origin)
        assertTrue(plan.onVideo)
        assertEquals(listOf("Straight 1"), plan.missing.map { it.name })
        assertEquals(listOf(1, 2), plan.cuts.map { it.partIndex })
        assertEquals(40_000_000L, plan.totalMicros)
        // The film starts with Corner 1, 19 s into the theoretical lap.
        val first = plan.cuts.first()
        assertEquals(0L, first.cut.outputStartMicros)
        assertEquals(19_000_000L, first.theoreticalMicrosAt(first.cut.sessionMicrosAt(0L)))
    }

    @Test
    fun `a gap between clips shortens a part, which is listed as partly on video`() {
        // Corner 1 (session 35-57 s) loses 45-50 s between clips.
        val clips = listOf(clip("a.mp4", 0.0, 45.0), clip("b.mp4", 50.0, 200.0))
        val plan = optimalClipPlan(optimal(), clips, ::lengthOf, origin)
        assertEquals(listOf("Corner 1"), plan.partial.map { it.name })
        assertTrue(plan.missing.isEmpty())
        val corner = plan.cuts.filter { it.partIndex == 1 }
        assertEquals(2, corner.size)
        // After the gap the running time is still the session's: 50 s is 15 s into the corner, 34 s into the lap.
        val after = corner[1]
        assertEquals(0L, after.cut.fromMicros)
        assertEquals(34_000_000L, after.theoreticalMicrosAt(after.cut.sessionMicrosAt(after.cut.outputStartMicros)))
        assertEquals(54_000_000L, plan.totalMicros)
    }

    @Test
    fun `no video at all leaves nothing to film`() {
        val plan = optimalClipPlan(optimal(), listOf(clip("a.mp4", 300.0, 400.0)), ::lengthOf, origin)
        assertFalse(plan.onVideo)
        assertEquals(3, plan.missing.size)
        assertEquals(0L, plan.totalMicros)
    }

    @Test
    fun `the overlay names the source lap and part, the running time and the gain so far`() {
        val plan = optimalClipPlan(optimal(), listOf(clip("a.mp4", 0.0, 200.0)), ::lengthOf, origin)
        val first = plan.cuts[0]
        val start = plan.overlayAt(first, first.partStartMicros + 3_000_000L)
        assertEquals(listOf("RAW THEORETICAL · LAP 2 · STRAIGHT 1", "LAP 2 · STRAIGHT 1", "LAP 2"), start.captions)
        assertEquals(3_000_000L, start.theoreticalMicros)
        assertEquals(0.0, start.deltaSeconds)
        // In Corner 1: Straight 1 gained 1 s on the fastest lap.
        val corner = plan.cuts[1]
        val inCorner = plan.overlayAt(corner, corner.partStartMicros)
        assertEquals("LAP 1 · CORNER 1", inCorner.captions[1])
        assertEquals("RUN 1", plan.overlayAt(corner, corner.partStartMicros, runs = true).captions.last())
        assertEquals(19_000_000L, inCorner.theoreticalMicros)
        assertEquals(-1.0, assertNotNull(inCorner.deltaSeconds), 1e-9)
        // Never past the part's end, even on a frame stamped a little late.
        assertEquals(41_000_000L, corner.theoreticalMicrosAt(corner.partStartMicros + 30_000_000L))
    }

    @Test
    fun `a corner over the line is two parts of its lap, after the line first and before it last`() {
        // Corner 1 runs from 900 m over the line to 100 m; Straight 1 from 100 to 900 m.
        val over = ApprovedSegments(
            listOf(
                TrackSegment("c", "Corner 1", TrackSegmentType.Corner, 900.0, 100.0),
                TrackSegment("s", "Straight 1", TrackSegmentType.Straight, 100.0, 900.0),
            ),
            revision = "auto-lap-1",
        )
        val c = over.segments[0]
        val s = over.segments[1]
        // Lap 1, 10-70 s: line to 100 m in 4 s, 900 m at 64 s. Lap 2, 70-131 s: 100 m at 73 s, 900 m at 125 s.
        fun overTimes(n: Int, head: Double, tail: Double) = LapSectorTimes(
            n,
            sectors = listOf(
                SectorTime(c.id, c.name, c.type, 900.0, 100.0, startTime = tail, endTime = head, seconds = 0.0),
                SectorTime(s.id, s.name, s.type, 100.0, 900.0, startTime = head, endTime = tail, seconds = tail - head),
            ),
            valid = true,
        )
        val a = SessionAnalysis(
            LapSession(
                LapSessionStatus.Available,
                timedLaps = listOf(lap(1, 10.0, 60.0), lap(2, 70.0, 61.0)),
                fastestLapIndex = 0,
            ),
            segments = over,
            lapTimes = listOf(overTimes(1, 14.0, 64.0), overTimes(2, 73.0, 125.0)),
            theoreticalBest = TheoreticalBestLap(
                sectors = listOf(
                    TheoreticalBestSector(c.id, c.name, c.type, 9.0, 2), // lap 2: 3 s after the line + 6 s before it
                    TheoreticalBestSector(s.id, s.name, s.type, 50.0, 1),
                ),
                totalSeconds = 59.0,
                valid = true,
            ),
        )
        val lap = assertNotNull(optimalLap(a, OptimalKind.RawTheoreticalBest).lap)
        assertEquals(listOf("Corner 1", "Straight 1", "Corner 1"), lap.parts.map { it.name })
        assertEquals(listOf(2, 1, 2), lap.parts.map { it.lapNumber })
        assertEquals(listOf(70.0, 14.0, 125.0), lap.parts.map { it.startTime })
        assertEquals(listOf(73.0, 64.0, 131.0), lap.parts.map { it.endTime })
        assertEquals(listOf(0.0, 3.0, 53.0), lap.parts.map { it.offsetSeconds })
        assertEquals(listOf(true, false, true), lap.parts.map { it.overTheLine })
        // The fastest lap's head and tail of the corner: 4 s and 6 s.
        assertEquals(listOf(4.0, 50.0, 6.0), lap.parts.map { it.referenceSeconds })
        assertEquals(59.0, lap.parts.sumOf { it.seconds })
    }

    @Test
    fun `the realistic optimal is filmed from the laps it chose, and says what it is`() {
        val realistic = RealisticOptimalLap(
            listOf(
                RealisticSector("s0", "Straight 1", 1, 20.0),
                RealisticSector("s1", "Corner 1", 1, 22.0),
                RealisticSector("s2", "Straight 2", 1, 18.0),
            ),
            joints = emptyList(),
            totalSeconds = 60.0,
            thresholds = JointThresholds(),
        )
        val lap = assertNotNull(optimalLap(analysis.copy(realisticOptimal = realistic)).lap)
        assertEquals(OptimalKind.RealisticOptimal, lap.kind)
        assertEquals(listOf(1, 1, 1), lap.parts.map { it.lapNumber })
        assertEquals(60.0, lap.totalSeconds)
        val plan = optimalClipPlan(lap, listOf(clip("a.mp4", 0.0, 200.0)), ::lengthOf, origin)
        val first = plan.cuts[0]
        val caption = plan.overlayAt(first, first.partStartMicros).captions[0]
        assertEquals("REALISTIC OPTIMAL · LAP 1 · STRAIGHT 1", caption)
    }

    @Test
    fun `no optimal lap without segments or with a segment no lap timed`() {
        val raw = OptimalKind.RawTheoreticalBest
        assertEquals(OptimalLapUnavailable.NoSegments, optimalLap(analysis.copy(segments = null), raw).why)
        val incomplete = analysis.copy(theoreticalBest = analysis.theoreticalBest?.copy(totalSeconds = null))
        assertEquals(OptimalLapUnavailable.IncompleteCoverage, optimalLap(incomplete, raw).why)
        assertNull(optimalLap(incomplete, raw).lap)
        // Without a realistic optimal (no chain joins), only the raw theoretical best can be filmed.
        assertEquals(OptimalLapUnavailable.NoCompatibleChain, optimalLap(analysis).why)
    }
}
