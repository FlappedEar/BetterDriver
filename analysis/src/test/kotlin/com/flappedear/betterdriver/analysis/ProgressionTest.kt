@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.coaching.AskedChange
import com.flappedear.betterdriver.analysis.coaching.CoachedSession
import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryStore
import com.flappedear.betterdriver.analysis.coaching.CornerPassRecord
import com.flappedear.betterdriver.analysis.coaching.CornerRecord
import com.flappedear.betterdriver.analysis.coaching.FocusAsk
import com.flappedear.betterdriver.analysis.coaching.FocusVerdict
import com.flappedear.betterdriver.analysis.coaching.LapRecord
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.coaching.sessionCoachingRecord
import com.flappedear.betterdriver.analysis.confidence.segmentSeconds
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceInferred
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceMeasured
import com.flappedear.betterdriver.analysis.focus.median
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.progression.CornerMapping
import com.flappedear.betterdriver.analysis.progression.MetricSeries
import com.flappedear.betterdriver.analysis.progression.MetricValue
import com.flappedear.betterdriver.analysis.progression.ProgressionFilter
import com.flappedear.betterdriver.analysis.progression.ProgressionGrouping
import com.flappedear.betterdriver.analysis.progression.ProgressionMetric
import com.flappedear.betterdriver.analysis.progression.coachingProgression
import com.flappedear.betterdriver.analysis.progression.trackProgression
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.summary.summarizeProgress
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.cornerIdIn
import com.flappedear.betterdriver.timing.track.corrected
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Longitudinal progression (FBBD-181): each metric per session, track day and month with its
 * trend, the same numbers each session's own analysis gives, corners followed by CornerId whatever
 * lap the analysis was referenced on and never across two revisions of the corners, filters by
 * session, day and date range, and imported sessions marked with where they came from.
 */
class ProgressionTest {
    @TempDir
    lateinit var tmp: File

    private val utc = ZoneOffset.UTC
    private val day = 86_400_000L
    private val sim = SimTrack.rectangle()

    /** [laps] laps of the rectangle at [pace] (the driver's share of the grip): quicker with each session. */
    private fun drive(pace: Double, laps: Int = 5): SessionData = SessionData(
        sim.id, SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL), SessionState.FINISHED,
        GnssChannels.ALL.associateBy { it.id }, sim.samples(laps, pace = pace), emptyList(), null, 0, 0,
    )

    /** Three sessions on three days, each quicker than the one before, analysed with the track's corner identity. */
    private val analyses: List<SessionAnalysis> by lazy {
        val first = analyzeSession(telemetrySessionOf(drive(0.84), sim.track()))
        val identity = assertNotNull(first.identity)
        listOf(first) + listOf(0.88, 0.92).map {
            analyzeSession(telemetrySessionOf(drive(it), sim.track()), identity = identity)
        }
    }

    private fun recordOf(a: SessionAnalysis, i: Int, origin: String? = null) =
        assertNotNull(sessionCoachingRecord(a, sim.id, "s$i", i * day + 3_600_000L, CoachedSession(), origin))

    private val records by lazy { analyses.mapIndexed { i, a -> recordOf(a, i) } }

    private fun close(expected: Double?, actual: Double?, tolerance: Double, what: String) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, what)
        } else {
            assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, was $actual")
        }
    }

    @Test
    fun `three sessions show each metric per session as each session's analysis has it, with a trend`() {
        val p = trackProgression(records, zone = utc)
        assertEquals(listOf("s0", "s1", "s2"), p.points.map { it.key })
        assertEquals(1, p.revision)
        for ((i, a) in analyses.withIndex()) {
            val laps = a.laps
            val counted = laps.timedLaps.filter { it.referenceEligible }.map { it.durationSeconds }
            val summary = summarizeProgress(laps, emptyList())
            val at = "session $i"
            assertEquals(summary.bestLapSeconds, p.lap(ProgressionMetric.BestLap)!!.values[i]?.value, at)
            assertEquals(median(counted), p.lap(ProgressionMetric.MedianLap)!!.values[i]?.value, at)
            assertEquals(summary.lapSpreadSeconds, p.lap(ProgressionMetric.LapSpread)!!.values[i]?.value, at)
            val mean = counted.average()
            val sd = sqrt(counted.sumOf { (it - mean) * (it - mean) } / (counted.size - 1))
            close(sd, p.lap(ProgressionMetric.LapDeviation)!!.values[i]?.value, 1e-12, at)
            assertEquals(summary.eligibleLapCount, p.points[i].countedLaps, at)

            val corners = a.segments!!.segments.filter { it.type == TrackSegmentType.Corner }
            assertEquals(corners.map { it.id }, p.corners.map { it.cornerId }, at)
            for (c in corners) {
                val cp = p.corners.single { it.cornerId == c.id }
                val eligible = laps.timedLaps.indices.filter { laps.timedLaps[it].referenceEligible }
                val times = eligible.mapNotNull { a.segmentSeconds(c.id, it) }
                assertEquals(median(times), cp.of(ProgressionMetric.CornerTime)!!.values[i]?.value, "$at ${c.name}")
                val v = a.variability.getValue(c.id)
                val minimum = cp.of(ProgressionMetric.MinimumSpeed)!!.values[i]?.value
                close(v.minimumSpeed.median, minimum, 1e-9, "$at ${c.name}")
                close(v.exitSpeed.median, cp.of(ProgressionMetric.ExitSpeed)!!.values[i]?.value, 1e-9, "$at ${c.name}")
                assertEquals("km/h", cp.of(ProgressionMetric.MinimumSpeed)!!.unit)
            }
        }
        // Quicker every session: the best and typical lap and every corner trend the better way.
        for (m in listOf(ProgressionMetric.BestLap, ProgressionMetric.MedianLap)) {
            val t = assertNotNull(p.lap(m)!!.trend, m.name)
            assertEquals(-1, t.direction, m.name)
            assertEquals(true, t.better, m.name)
            assertEquals(3, t.points)
        }
        for (c in p.corners) {
            assertEquals(true, c.of(ProgressionMetric.CornerTime)!!.trend?.better, c.name)
            assertEquals(true, c.of(ProgressionMetric.MinimumSpeed)!!.trend?.better, c.name)
        }
    }

    @Test
    fun `a corner's progression follows its CornerId, whichever lap a session was referenced on`() {
        val identity = assertNotNull(analyses[0].identity)
        val session = telemetrySessionOf(drive(0.88), sim.track())
        val laps = deriveSourceLapSession(session)
        val eligible = laps.timedLaps.indices.filter { laps.timedLaps[it].referenceEligible }
        // The same laps, all still counted, referenced on another lap than the fastest.
        val other = eligible.first { it != laps.fastestLapIndex }
        val reReferenced = analyzeSession(session, laps.copy(fastestLapIndex = other), identity)
        assertEquals(laps.timedLaps[other].number, reReferenced.axisLapNumber)
        assertTrue(reReferenced.identified)

        val before = trackProgression(records, zone = utc)
        val after = trackProgression(listOf(records[0], recordOf(reReferenced, 1), records[2]), zone = utc)
        assertEquals(before.corners.map { it.cornerId to it.name }, after.corners.map { it.cornerId to it.name })
        assertEquals(before.lap(ProgressionMetric.BestLap), after.lap(ProgressionMetric.BestLap))
        val tolerance = mapOf(
            ProgressionMetric.CornerTime to 0.03,
            ProgressionMetric.CornerSpread to 0.03,
            ProgressionMetric.MinimumSpeed to 1.0,
            ProgressionMetric.ExitSpeed to 1.5,
            ProgressionMetric.BrakingPoint to 3.0,
            ProgressionMetric.ThrottlePickup to 3.0,
        )
        for (c in before.corners) {
            val again = after.corners.single { it.cornerId == c.cornerId }
            for (s in c.series) {
                val t = again.of(s.metric)!!
                for (i in s.values.indices) {
                    val what = "${c.name} ${s.metric} $i"
                    close(s.values[i]?.value, t.values[i]?.value, tolerance.getValue(s.metric), what)
                }
                assertEquals(s.trend?.direction, t.trend?.direction, "${c.name} ${s.metric}")
            }
        }
    }

    // Hand-built records from here: no recording, no positions.

    private fun pass(lap: Int, seconds: Double, grade: String = "HIGH", way: String = BrakingProvenanceMeasured) =
        CornerPassRecord(lap, seconds, grade, 10.0, way, 70.0 + lap, 90.0, 40.0, way)

    private fun corner(id: String, vararg passes: CornerPassRecord, unit: String = "km/h") = CornerRecord(
        id, "Corner ${id.drop(1)}", unit, speedMeasured = true, brakeMeasured = true, throttleMeasured = true,
        passes = passes.toList(),
    )

    @Suppress("LongParameterList")
    private fun record(
        id: String,
        start: Long,
        lapTimes: List<Double>,
        revision: Int = 1,
        corners: List<CornerRecord> = listOf(corner("c1", pass(1, 5.0), pass(2, 5.2), pass(3, 5.1))),
        origin: String? = null,
        focus: List<FocusAsk> = emptyList(),
        coachedFrom: String? = null,
    ) = SessionCoachingRecord(
        trackId = "t", sessionId = id, sessionStartUtcMillis = start, identityRevision = revision,
        coachedFrom = coachedFrom, focus = focus, corners = corners,
        laps = lapTimes.mapIndexed { i, s -> LapRecord(i + 1, s, counted = s < 100.0) }, origin = origin,
    )

    @Test
    fun `sessions are grouped by track day and month, and filtered by session, day and date range`() {
        val d1 = LocalDate.of(2026, 9, 5)
        fun at(d: LocalDate, hour: Int) = d.atStartOfDay(utc).toInstant().toEpochMilli() + hour * 3_600_000L
        val rs = listOf(
            record("a", at(d1, 9), listOf(90.0, 91.0, 92.0, 140.0)),
            record("b", at(d1, 14), listOf(89.0, 89.5, 90.5)),
            record("c", at(d1.plusDays(20), 10), listOf(88.0, 88.5, 89.0)),
            record("d", at(LocalDate.of(2026, 10, 3), 10), listOf(87.0, 87.6, 88.0)),
        )
        val byDay = trackProgression(rs, ProgressionGrouping.TrackDay, zone = utc)
        assertEquals(listOf("2026-09-05", "2026-09-25", "2026-10-03"), byDay.points.map { it.key })
        assertEquals(listOf("a", "b"), byDay.points[0].sessionIds)
        // The day's laps pooled: its best, its median, never the slow lap that did not count.
        assertEquals(89.0, byDay.lap(ProgressionMetric.BestLap)!!.values[0]?.value)
        assertEquals(90.25, byDay.lap(ProgressionMetric.MedianLap)!!.values[0]?.value)
        assertEquals(6, byDay.points[0].countedLaps)
        assertEquals(6, byDay.corners.single().of(ProgressionMetric.CornerTime)!!.values[0]?.count)

        val byMonth = trackProgression(rs, ProgressionGrouping.Month, zone = utc)
        assertEquals(listOf("2026-09", "2026-10"), byMonth.points.map { it.key })
        assertEquals(88.0, byMonth.lap(ProgressionMetric.BestLap)!!.values[0]?.value)

        fun keys(f: ProgressionFilter) = trackProgression(rs, filter = f, zone = utc).points.map { it.key }
        assertEquals(listOf("b", "d"), keys(ProgressionFilter(sessionIds = setOf("b", "d"))))
        assertEquals(listOf("a", "b"), keys(ProgressionFilter(days = setOf(d1))))
        assertEquals(listOf("c", "d"), keys(ProgressionFilter(from = d1.plusDays(1))))
        assertEquals(listOf("a", "b", "c"), keys(ProgressionFilter(to = LocalDate.of(2026, 9, 30))))
        assertEquals(listOf("c"), keys(ProgressionFilter(from = d1.plusDays(1), to = LocalDate.of(2026, 9, 30))))
        assertTrue(trackProgression(rs, filter = ProgressionFilter(sessionIds = emptySet()), zone = utc).empty)
        // A day is the phone's: 23:30 UTC on the 5th is the 6th two hours east.
        val late = record("e", at(d1, 23) + 1_800_000L, listOf(90.0))
        val east = trackProgression(listOf(late), ProgressionGrouping.TrackDay, zone = ZoneOffset.ofHours(2))
        assertEquals("2026-09-06", east.points.single().key)
    }

    @Test
    fun `imported sessions take part, marked with where they came from, and their records keep it`() {
        val imported = recordOf(analyses[1], 1, origin = "VBO lap-12.vbo")
        val p = trackProgression(listOf(records[0], imported, records[2]), zone = utc)
        assertEquals(listOf(false, true, false), p.points.map { it.imported })
        assertEquals(mapOf("s1" to "VBO lap-12.vbo"), p.points[1].origins)
        assertEquals(records[1].laps, imported.laps)
        assertNotNull(p.lap(ProgressionMetric.BestLap)!!.values[1])

        // Kept and read back with its laps, its pickups and where it came from.
        val store = CoachingHistoryStore(tmp.path)
        assertTrue(store.save(imported))
        assertEquals(imported, store.get(sim.id, "s1"))
        assertTrue(File(tmp, "${sim.id}/s1.json").readText().contains("\"origin\":\"VBO lap-12.vbo\""))
        assertTrue(imported.laps.isNotEmpty())
    }

    @Test
    fun `a record from before the laps were kept still takes part, without lap times`() {
        val old = record("old", 1_000, emptyList())
        val p = trackProgression(listOf(old, record("new", 2_000, listOf(80.0, 81.0, 82.0))), zone = utc)
        assertEquals(listOf("old", "new"), p.points.map { it.key })
        assertNull(p.lap(ProgressionMetric.BestLap)!!.values[0])
        assertEquals(80.0, p.lap(ProgressionMetric.BestLap)!!.values[1]?.value)
        assertNotNull(p.corners.single().of(ProgressionMetric.CornerTime)!!.values[0])
    }

    @Test
    fun `corners of another revision are never compared, unless a mapping says which is which`() {
        val rs = listOf(
            record("r1a", 1_000, listOf(80.0), revision = 1),
            record("r1b", 2_000, listOf(80.0), corners = listOf(corner("x9", pass(1, 6.0), pass(2, 6.0)))),
            record("r2", 3_000, listOf(79.0), revision = 2, corners = listOf(corner("c1", pass(1, 4.0), pass(2, 4.5)))),
        )
        val p = trackProgression(rs, zone = utc)
        assertEquals(2, p.revision)
        // Same CornerId "c1" in revision 1, yet not the same corner: left out.
        val c1 = p.corners.single()
        assertEquals(listOf(null, null), c1.of(ProgressionMetric.CornerTime)!!.values.take(2))
        assertEquals(listOf("r1a", "r1b"), p.otherRevisionSessions)
        // Laps are the track's whatever its corners: every session takes part there.
        assertEquals(3, p.lap(ProgressionMetric.BestLap)!!.values.count { it != null })

        // Revision 2 renamed revision 1's x9 to c1 (as the segment editor maps it, FBBD-179).
        val mapping = CornerMapping { from, id, to ->
            when {
                from == to -> id
                from == 1 && to == 2 && id == "x9" -> "c1"
                else -> null
            }
        }
        val mapped = trackProgression(rs, zone = utc, mapping = mapping)
        val times = mapped.corners.single().of(ProgressionMetric.CornerTime)!!.values.map { it?.value }
        assertEquals(listOf(null, 6.0, 4.25), times)
        assertEquals(listOf("r1a"), mapped.otherRevisionSessions)
    }

    @Test
    fun `corrected corners are followed across the revisions the corrections made (FBBD-179)`() {
        // The track's corners as found (revision 1); the driver names the first (still revision 1),
        // splits the second (revision 2), then moves where the first starts (revision 3).
        val found = assertNotNull(analyses.first().identity)
        val c1 = found.corners[0]
        val c2 = found.corners[1]
        val named = assertNotNull(found.corrected(SegmentEdit.Rename(c1.id, "Hairpin")))
        val split = assertNotNull(named.corrected(SegmentEdit.Split(c2.id, (c2.startMeters + c2.endMeters) / 2)))
        val moved = assertNotNull(split.corrected(SegmentEdit.MoveBoundary(c1.id, c1.startMeters + 5.0)))
        assertEquals(listOf(1, 2, 3), listOf(named.number, split.number, moved.number))
        val added = split.corners.single { it.id !in found.corners.map { c -> c.id } }.id
        val rs = listOf(
            record("a", 1_000, listOf(80.0), revision = 1, corners = listOf(c(c1.id, 6.0), c(c2.id, 7.0))),
            record(
                "b", 2_000, listOf(79.0), revision = 2,
                corners = listOf(c(c1.id, 5.5), c(c2.id, 6.5), c(added, 3.0)),
            ),
            record("c", 3_000, listOf(78.0), revision = 3, corners = listOf(c(c1.id, 5.0), c(c2.id, 6.0))),
        )

        // Without the mapping, only the latest revision's session counts for the corners.
        assertEquals(listOf("a", "b"), trackProgression(rs, zone = utc).otherRevisionSessions)

        // With the track's own: the named corner and the one split keep their history across all three.
        val p = trackProgression(rs, zone = utc, mapping = CornerMapping.of(moved))
        assertEquals(3, p.revision)
        assertEquals(emptyList(), p.otherRevisionSessions)
        fun times(id: String) =
            p.corners.single { it.cornerId == id }.of(ProgressionMetric.CornerTime)!!.values.map { it?.value }
        assertEquals(listOf(6.0, 5.5, 5.0), times(c1.id))
        assertEquals(listOf(7.0, 6.5, 6.0), times(c2.id))
        // Stored and read back, the track's corners still say which is which.
        val back = TrackJson.decode(TrackJson.encode(sim.track().copy(identity = moved))).identity
        assertEquals(c1.id, back?.cornerIdIn(1, c1.id, 3))
        assertEquals(c2.id, back?.cornerIdIn(3, c2.id, 1))
        assertNull(back?.cornerIdIn(2, added, 1))
    }

    private fun c(id: String, seconds: Double) = corner(id, pass(1, seconds), pass(2, seconds))

    @Test
    fun `Low passes never count, and a value is precise only from High passes of measured channels`() {
        val mixed = corner("c1", pass(1, 5.0), pass(2, 5.5, grade = "MEDIUM"), pass(3, 9.0, grade = "LOW"))
        val inferred = corner(
            "c1", pass(1, 5.0, way = BrakingProvenanceInferred), pass(2, 5.1, way = BrakingProvenanceInferred),
        )
        val unknownUnit = corner("c1", pass(1, 5.0), pass(2, 5.1), unit = "furlongs")
        val p = trackProgression(
            listOf(
                record("a", 1_000, listOf(80.0), corners = listOf(mixed)),
                record("b", 2_000, listOf(80.0), corners = listOf(inferred)),
                record("c", 3_000, listOf(80.0), corners = listOf(unknownUnit)),
                record("d", 4_000, listOf(80.0)),
            ),
            zone = utc,
        )
        val c = p.corners.single()
        val time = c.of(ProgressionMetric.CornerTime)!!
        // The Low 9.0 s pass is not counted; the Medium one is, so the value is not precise.
        assertEquals(5.25, time.values[0]?.value)
        assertEquals(2, time.values[0]?.count)
        assertFalse(time.values[0]!!.precise)
        assertTrue(time.values[3]!!.precise)
        assertFalse(time.trend!!.precise)
        // Measured braking points are compared, never mixed with the inferred ones of session b.
        val braking = c.of(ProgressionMetric.BrakingPoint)!!
        assertNull(braking.values[1])
        assertEquals(BrakingProvenanceMeasured, braking.values[3]?.provenance)
        // A speed in a unit not known is no speed to compare with km/h.
        val speed = c.of(ProgressionMetric.MinimumSpeed)!!
        assertEquals("km/h", speed.unit)
        assertNull(speed.values[2])
        assertTrue(speed.values[3]!!.precise)
        assertEquals(40.0, c.of(ProgressionMetric.ThrottlePickup)!!.values[3]?.value)
    }

    @Test
    fun `a trend needs three points and says which way is better, steady below the noise`() {
        fun series(m: ProgressionMetric, vararg v: Double?) =
            MetricSeries(m, v.map { x -> x?.let { MetricValue(it, 3) } })
        assertNull(series(ProgressionMetric.BestLap, 80.0, 79.0).trend)
        assertNull(series(ProgressionMetric.BestLap, 80.0, null, 79.0).trend)
        val faster = assertNotNull(series(ProgressionMetric.BestLap, 80.0, 79.5, 79.0).trend)
        assertEquals(-1, faster.direction)
        assertEquals(true, faster.better)
        close(-1.0, faster.change, 1e-9, "change")
        val steady = assertNotNull(series(ProgressionMetric.BestLap, 80.0, 80.04, 79.97).trend)
        assertEquals(0, steady.direction)
        assertNull(steady.better)
        val slower = assertNotNull(series(ProgressionMetric.MinimumSpeed, 80.0, 78.0, 75.0).trend)
        assertEquals(false, slower.better)
        // Later braking is neither better nor worse in itself.
        val later = assertNotNull(series(ProgressionMetric.BrakingPoint, 0.0, 6.0, 12.0).trend)
        assertEquals(1, later.direction)
        assertNull(later.better)
    }

    @Test
    fun `the coaching progression says what came of each focus, session by session`() {
        val ask = FocusAsk("c1", "Corner 1", "primary", "HIGH", listOf(AskedChange("brakingPoint", 1)))
        val next = FocusAsk("c2", "Corner 2", "primary", "HIGH")
        val c2 = corner("c2", pass(1, 7.0), pass(2, 7.1), pass(3, 7.05))
        val slow = listOf(corner("c1", pass(1, 5.4), pass(2, 5.5), pass(3, 5.45), pass(4, 5.5)), c2)
        val quick = listOf(corner("c1", pass(1, 5.1), pass(2, 5.15), pass(3, 5.2), pass(4, 5.1)), c2)
        val rs = listOf(
            record("s0", 1_000, listOf(80.0), corners = slow, focus = listOf(ask)),
            record("s1", 2_000, listOf(79.0), corners = quick, coachedFrom = "s0", focus = listOf(next)),
            record("s2", 3_000, listOf(79.0), corners = quick),
        )
        // The first session is filtered out, yet the focus it made is what the second worked on.
        val p = trackProgression(rs, filter = ProgressionFilter(sessionIds = setOf("s1", "s2")), zone = utc)
        val steps = coachingProgression(p, rs)
        assertEquals(listOf("s1", "s2"), steps.map { it.key })
        assertEquals(FocusVerdict.Improved, steps[0].attempts.single().verdict)
        assertEquals(1, steps[0].improved)
        assertTrue(steps[0].attempts.single().coached)
        // The session after worked on the next focus, and the first gain held there.
        assertEquals(listOf("c2"), steps[1].attempts.map { it.ask.cornerId })
        assertEquals(1, steps[1].held)
    }

    /** The median of [values] as worked out by hand: the middle one, or the mean of the middle two. */
    private fun referenceMedian(values: List<Double>): Double {
        val sorted = values.sorted()
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2
    }

    /**
     * Day progression (FBBD-175): a track day opened alone shows its sessions in the order they were
     * driven, each with its best and typical lap and every corner's median time, against a reference
     * worked out here from the records' own laps and passes (within 0.01 s), and every counted lap of
     * the day in driving order with the way it went.
     */
    @Test
    fun `a track day shows how lap and corner times went session by session and lap by lap`() {
        val d = LocalDate.of(2026, 9, 5)
        fun at(hour: Int) = d.atStartOfDay(utc).toInstant().toEpochMilli() + hour * 3_600_000L
        fun c(vararg seconds: Double) =
            listOf(corner("c1", *seconds.mapIndexed { i, s -> pass(i + 1, s) }.toTypedArray()))
        val today = listOf(
            record("m1", at(9), listOf(92.4, 91.8, 91.5, 150.0), corners = c(5.40, 5.31, 5.35)),
            record("m2", at(11), listOf(91.2, 90.9, 91.0, 90.7), corners = c(5.22, 5.18, 5.25, 5.15)),
            record("a1", at(14), listOf(90.6, 90.3, 90.5), corners = c(5.10, 5.12, 5.08)),
            record("a2", at(16), listOf(90.8, 90.4, 90.9), corners = c(5.14, 5.09, 5.11)),
        )
        val other = record("x", at(9) + 3 * day, listOf(89.0, 89.1, 89.2))
        val p = trackProgression(today + other, filter = ProgressionFilter(days = setOf(d)), zone = utc)
        assertEquals(listOf("m1", "m2", "a1", "a2"), p.points.map { it.key })
        for ((i, r) in today.withIndex()) {
            val counted = r.laps.filter { it.counted }.map { it.seconds }
            close(counted.min(), p.lap(ProgressionMetric.BestLap)!!.values[i]?.value, 0.01, "${r.sessionId} best")
            val typical = p.lap(ProgressionMetric.MedianLap)!!.values[i]?.value
            close(referenceMedian(counted), typical, 0.01, "${r.sessionId} median")
            val corner = p.corners.single().of(ProgressionMetric.CornerTime)!!.values[i]?.value
            close(referenceMedian(r.corners.single().passes.map { it.seconds }), corner, 0.01, r.sessionId)
        }
        // Quicker through the day by the session, and the corner with it.
        assertEquals(true, p.lap(ProgressionMetric.MedianLap)!!.trend?.better)
        assertEquals(true, p.corners.single().of(ProgressionMetric.CornerTime)!!.trend?.better)
        // Every counted lap of the day in the order driven, never the lap that did not count.
        assertEquals(
            listOf("m1" to 1, "m1" to 2, "m1" to 3, "m2" to 1, "m2" to 2, "m2" to 3, "m2" to 4) +
                listOf("a1" to 1, "a1" to 2, "a1" to 3, "a2" to 1, "a2" to 2, "a2" to 3),
            p.lapsInOrder.map { it.sessionId to it.number },
        )
        assertEquals(91.8, p.lapsInOrder[1].seconds)
        val trend = assertNotNull(p.lapsInOrderTrend)
        assertEquals(13, trend.points)
        assertEquals(-1, trend.direction)
        assertTrue(trend.precise)
        // The least-squares change from the first lap to the last, worked out here.
        val ys = p.lapsInOrder.map { it.seconds }
        val mx = (ys.size - 1) / 2.0
        val my = ys.average()
        val slope = ys.indices.sumOf { (it - mx) * (ys[it] - my) } / ys.indices.sumOf { (it - mx) * (it - mx) }
        close(slope * (ys.size - 1), trend.change, 0.01, "change over the day")
        // Two laps make no trend.
        assertNull(trackProgression(listOf(record("s", at(9), listOf(90.0, 91.0))), zone = utc).lapsInOrderTrend)
    }
}
