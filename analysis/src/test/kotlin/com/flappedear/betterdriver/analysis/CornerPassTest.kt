package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.corners.BrakingMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapObservation
import com.flappedear.betterdriver.analysis.corners.CornerPass
import com.flappedear.betterdriver.analysis.corners.CornerSpeedValue
import com.flappedear.betterdriver.analysis.corners.CornerSpeeds
import com.flappedear.betterdriver.analysis.corners.ExitMetrics
import com.flappedear.betterdriver.analysis.corners.cornerClip
import com.flappedear.betterdriver.analysis.corners.cornerLeadSeconds
import com.flappedear.betterdriver.analysis.corners.cornerPasses
import com.flappedear.betterdriver.analysis.corners.sameLap
import com.flappedear.betterdriver.analysis.corners.sessionMicros
import com.flappedear.betterdriver.analysis.laps.LapReferenceIssue
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.SectorTime
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CornerPassTest {
    private fun lap(number: Int, start: Double, seconds: Double, issue: LapReferenceIssue = LapReferenceIssue.None) =
        TimedLap(number, start, start + seconds, seconds, referenceIssue = issue)

    private fun sector(start: Double?, end: Double?, seconds: Double?) =
        SectorTime(
            "c1", "Corner 1", TrackSegmentType.Corner, 400.0, 500.0,
            startTime = start, endTime = end, seconds = seconds,
        )

    private fun metrics(lap: Int, braking: Double?, minimum: Double) = CornerLapMetrics(
        lap,
        CornerSpeeds(minimum = CornerSpeedValue(minimum), unit = "km/h"),
        BrakingMetrics(brakingPointTime = braking, distanceBeforeEntryMeters = 40.0, provenance = "inferred"),
        ExitMetrics(),
        CornerLapObservation(lap),
    )

    // Four laps of 60 s from t = 10 s; lap 3 has a GPS gap, lap 4 does not reach the corner exit.
    private val analysis = SessionAnalysis(
        LapSession(
            LapSessionStatus.Available,
            timedLaps = listOf(
                lap(1, 10.0, 62.0),
                lap(2, 72.0, 60.0),
                lap(3, 132.0, 61.0, LapReferenceIssue.GpsGap),
                lap(4, 193.0, 63.0),
            ),
        ),
        lapTimes = listOf(
            LapSectorTimes(1, sectors = listOf(sector(30.0, 34.5, 4.5))),
            LapSectorTimes(2, sectors = listOf(sector(91.0, 95.2, 4.2))),
            LapSectorTimes(3, sectors = listOf(sector(151.0, 155.0, 4.0))),
            LapSectorTimes(4, sectors = listOf(sector(212.0, null, null))),
        ),
        cornerMetrics = mapOf(
            "c1" to listOf(
                metrics(1, 28.0, 80.0),
                metrics(2, 89.5, 82.0),
                metrics(3, 149.0, 85.0),
                metrics(4, null, 70.0),
            ),
        ),
    )

    @Test
    fun `every counted lap's pass, fastest through the corner first`() {
        val passes = cornerPasses(analysis, "c1")
        // Lap 3 is not counted; lap 4 has no time through the corner, so it comes last.
        assertEquals(listOf(2, 1, 4), passes.map { it.lapNumber })
        val best = passes.first()
        assertEquals(1, best.lapIndex)
        assertEquals(91.0, best.entryTime)
        assertEquals(95.2, best.exitTime!!, 1e-9)
        assertEquals(4.2, best.seconds)
        assertEquals(89.5, best.brakingTime)
        assertEquals(40.0, best.brakingBeforeEntryMeters)
        assertTrue(best.brakingInferred)
        assertEquals(82.0, best.minimumSpeed)
        assertEquals("km/h", best.speedUnit)
        assertNull(passes.last().exitTime)
        assertTrue(cornerPasses(analysis, "unknown").isEmpty())
    }

    @Test
    fun `a corner over the line is one stretch of time, into the next lap`() {
        // The entry is near the lap's end and the exit is timed at its start: the drive leaves the
        // corner seconds after the entry, on the next lap.
        val over = analysis.copy(
            lapTimes = listOf(LapSectorTimes(1, sectors = listOf(sector(70.0, 12.0, 4.0)))),
            laps = analysis.laps.copy(timedLaps = analysis.laps.timedLaps.take(1)),
        )
        val pass = cornerPasses(over, "c1").single()
        assertEquals(70.0, pass.entryTime)
        assertEquals(74.0, pass.exitTime)
    }

    private fun pass(entry: Double?, exit: Double?, braking: Double? = null) =
        CornerPass(0, 1, entry, exit, null, braking, null, false, null, "")

    @Test
    fun `the video starts just before the braking point, or a second before the entry`() {
        assertEquals(1.0, cornerLeadSeconds(pass(100.0, 104.0)))
        assertEquals(2.5, cornerLeadSeconds(pass(100.0, 104.0, braking = 98.0)), 1e-9)
        // A braking point far back, or after the entry, is not waited for.
        assertEquals(6.0, cornerLeadSeconds(pass(100.0, 104.0, braking = 80.0)))
        assertEquals(1.0, cornerLeadSeconds(pass(100.0, 104.0, braking = 101.0)))
    }

    // The session clock starts at 5 s; telemetry time 0 is its first sample.
    private val origin = 5_000_000L

    @Test
    fun `a pass maps to the clip showing its entry, from the lead-in to the exit`() {
        val timeline = VideoTimeline(
            listOf(
                VideoClip("phone", "a.mp4", 0L, 60_000_000L),
                VideoClip("phone", "b.mp4", 60_000_000L, 200_000_000L),
                VideoClip("gopro", "g.mp4", 0L, null),
            ),
        )
        assertEquals(105_000_000L, sessionMicros(origin, 100.0))
        val clip = assertNotNull(cornerClip(pass(100.0, 104.0, braking = 98.0), timeline, "phone", origin))
        assertEquals("b.mp4", clip.clip.file)
        // Session 102.5 s to 109 s, in a clip starting at 60 s.
        assertEquals(42_500L, clip.startMillis)
        assertEquals(49_000L, clip.endMillis)
        val gopro = assertNotNull(cornerClip(pass(100.0, 104.0), timeline, "gopro", origin))
        assertEquals(104_000L, gopro.startMillis)
        assertEquals(109_000L, gopro.endMillis)
    }

    @Test
    fun `a pass is cut to its clip, and has none without video at its entry`() {
        val timeline = VideoTimeline(
            listOf(
                VideoClip("phone", "a.mp4", 50_000_000L, 107_000_000L),
                VideoClip("phone", "b.mp4", 120_000_000L, null),
            ),
        )
        // The clip starts after the lead-in and ends before the exit.
        val cut = assertNotNull(cornerClip(pass(46.0, 104.0), timeline, "phone", origin))
        assertEquals(0L, cut.startMillis)
        assertEquals(57_000L, cut.endMillis)
        // A clip with no end runs to the exit.
        val open = assertNotNull(cornerClip(pass(120.0, 124.0), timeline, "phone", origin))
        assertEquals("b.mp4", open.clip.file)
        assertEquals(9_000L, open.endMillis)
        // Between the clips, before the video, on another camera, and without an exit: none.
        assertNull(cornerClip(pass(108.0, 112.0), timeline, "phone", origin))
        assertNull(cornerClip(pass(10.0, 14.0), timeline, "phone", origin))
        assertNull(cornerClip(pass(100.0, 104.0), timeline, "gopro", origin))
        assertNull(cornerClip(pass(100.0, null), timeline, "phone", origin))
        assertNull(cornerClip(pass(null, 104.0), timeline, "phone", origin))
    }

    @Test
    fun `the same lap in another lap list is the one starting at the same crossing`() {
        val starts = mapOf(1 to 15_000_000L, 2 to 77_100_000L, 3 to 137_000_000L)
        assertEquals(2, sameLap(77_000_000L, starts))
        assertEquals(1, sameLap(14_000_000L, starts))
        assertNull(sameLap(100_000_000L, starts))
        assertNull(sameLap(77_000_000L, emptyMap()))
    }

    @Test
    fun `a recorded session's passes run from the entry to the exit`() {
        val analysis = analyzeSession(VboFixture.read("corners_measured.vbo"))
        val corner = assertNotNull(analysis.cornerMetrics.keys.firstOrNull())
        val passes = cornerPasses(analysis, corner)
        val counted = analysis.laps.timedLaps.count { it.referenceEligible }
        assertEquals(counted, passes.size)
        val timed = passes.filter { it.seconds != null }
        assertTrue(timed.isNotEmpty())
        assertEquals(timed.sortedBy { it.seconds }, timed)
        for (p in timed) {
            assertEquals(p.seconds!!, p.exitTime!! - p.entryTime!!, 1e-9)
            val lap = analysis.laps.timedLaps[p.lapIndex]
            assertTrue(p.entryTime in lap.startTelemetryTime..lap.endTelemetryTime)
        }
    }
}
