@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.coaching.CoachedSession
import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryStore
import com.flappedear.betterdriver.analysis.coaching.FocusAsk
import com.flappedear.betterdriver.analysis.coaching.FocusAttempt
import com.flappedear.betterdriver.analysis.coaching.FocusVerdict
import com.flappedear.betterdriver.analysis.coaching.Persistence
import com.flappedear.betterdriver.analysis.coaching.SessionCoaching
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.coaching.attemptText
import com.flappedear.betterdriver.analysis.coaching.checkText
import com.flappedear.betterdriver.analysis.coaching.coachingHistory
import com.flappedear.betterdriver.analysis.coaching.sessionCoachingRecord
import com.flappedear.betterdriver.analysis.confidence.ConfidenceReason
import com.flappedear.betterdriver.analysis.confidence.Graded
import com.flappedear.betterdriver.analysis.confidence.SegmentConfidence
import com.flappedear.betterdriver.analysis.confidence.SessionConfidence
import com.flappedear.betterdriver.analysis.corners.BrakingMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapObservation
import com.flappedear.betterdriver.analysis.corners.CornerSpeeds
import com.flappedear.betterdriver.analysis.corners.ExitMetrics
import com.flappedear.betterdriver.analysis.focus.FocusMetric
import com.flappedear.betterdriver.analysis.identity.identityRevisionTag
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.LapSectorTimes
import com.flappedear.betterdriver.analysis.segments.SectorTime
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestRow
import com.flappedear.betterdriver.analysis.segments.TheoreticalBestSummary
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.FocusTarget
import com.flappedear.betterdriver.coach.SessionFocusRecord
import com.flappedear.betterdriver.coach.SessionFocusStore
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.timing.track.RevisionSegment
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.transfer.Backup
import com.flappedear.betterdriver.transfer.BackupRestore
import com.flappedear.betterdriver.transfer.DataGate
import com.flappedear.betterdriver.transfer.RestoreMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The coaching effectiveness history (FBBD-180): what came of a next-session focus in the session
 * that worked on it, whether the gain held later, and that numbers are said only from High
 * confidence measured data, Low passes are never counted, and the history outlives its sessions
 * and comes back with a backup. All on hand-built sessions: no recording, no positions.
 */
class CoachingHistoryTest {
    @TempDir
    lateinit var tmp: File

    private val track = "t1"

    /** The track's corner identity: four corners, CornerIds c1 to c4 (synthetic centreline). */
    private fun identity(number: Int = 1) = TrackRevision(
        number = number,
        lats = listOf(0.0, 0.0),
        lons = listOf(0.0, 0.01),
        segments = (1..4).map {
            RevisionSegment("c$it", SegmentKind.Corner, it, startMeters = it * 200.0 - 50.0, endMeters = it * 200.0)
        },
    )

    /** Corner times of the four corners on six laps, before the focus: c1 loses the most. */
    private val before = mapOf(
        "c1" to listOf(10.10, 10.50, 10.12, 10.45, 10.48, 10.50),
        "c2" to listOf(8.00, 8.20, 8.02, 8.18, 8.20, 8.22),
        "c3" to listOf(6.05, 6.17, 6.06, 6.15, 6.16, 6.17),
        "c4" to listOf(5.00, 5.11, 5.01, 5.10, 5.11, 5.12),
    )

    /** c1 before: the two fastest passes (laps 1 and 3) brake later and keep more minimum speed. */
    private val brakingBefore = listOf(128.0, 120.0, 128.0, 121.0, 119.0, 120.0)
    private val minimumBefore = listOf(74.0, 70.0, 74.0, 71.0, 70.0, 69.0)

    /**
     * One session at the track, built by hand: six laps (lap 2 the fastest) through c1 to c4 with
     * [times], and c1's braking points (metres along the lap; c1 starts at 150 m) and minimum
     * speeds. Brake and speed measured unless said; every lap High unless [graded] says otherwise
     * (lap index to its confidence in c1).
     */
    @Suppress("LongParameterList")
    private fun session(
        times: Map<String, List<Double>> = before,
        braking: List<Double> = brakingBefore,
        minimum: List<Double> = minimumBefore,
        graded: Map<Int, Confidence> = emptyMap(),
        brake: Provenance? = Provenance.MEASURED_CAN,
        speed: Provenance? = Provenance.MEASURED_GNSS_RECEIVER,
        revision: Int = 1,
    ): SessionAnalysis {
        val id = identity(revision)
        val laps = (1..6).map { n -> TimedLap(n, n * 100.0, n * 100.0 + 80.0 + (if (n == 2) 0.0 else 0.5), 80.0) }
        val segments = id.segments.map {
            TrackSegment(it.id, it.name, TrackSegmentType.Corner, it.startMeters, it.endMeters)
        }
        val sectorTimes = (1..6).map { n ->
            LapSectorTimes(
                n,
                sectors = segments.map {
                    SectorTime(
                        it.id, it.name, it.type, it.startProgressMeters, it.endProgressMeters,
                        seconds = times.getValue(it.id)[n - 1],
                    )
                },
            )
        }
        val rows = segments.map { s ->
            val t = times.getValue(s.id)
            val best = t.min()
            TheoreticalBestRow(
                s.id, s.name, s.type, s.startProgressMeters, s.endProgressMeters, best, t.indexOf(best) + 1,
                actualSeconds = t[1], lossSeconds = t[1] - best,
            )
        }
        val metrics = (1..6).map { n ->
            val o = CornerLapObservation(
                lapReference = n,
                brakingPointMeters = braking[n - 1],
                brakingProvenance = "measured",
                minimumSpeed = minimum[n - 1],
                exitSpeed = 100.0,
                speedUnit = "km/h",
            )
            CornerLapMetrics(n, CornerSpeeds(), BrakingMetrics(), ExitMetrics(), o)
        }
        val lapGrades = (0 until 6).map { i ->
            when (graded[i]) {
                null, Confidence.HIGH -> Graded.High
                else -> Graded.of(ConfidenceReason("gps_accuracy", graded.getValue(i), "lap ${i + 1}: weak positions"))
            }
        }
        return SessionAnalysis(
            LapSession(LapSessionStatus.Available, timedLaps = laps, fastestLapIndex = 1),
            axisLapNumber = 2,
            segments = ApprovedSegments(segments, identityRevisionTag(revision)),
            lapTimes = sectorTimes,
            summary = TheoreticalBestSummary(rows, rows.sortedByDescending { it.lossSeconds }),
            cornerMetrics = mapOf("c1" to metrics),
            provenance = AnalysisProvenance(speed = speed, brake = brake),
            identity = id,
            confidence = SessionConfidence(segments = mapOf("c1" to SegmentConfidence("c1", laps = lapGrades))),
        )
    }

    /** After working on c1: braking about 8 m later, 3 km/h more minimum speed, median 0.18 s quicker. */
    private val after = before + ("c1" to listOf(10.20, 10.30, 10.25, 10.28, 10.29, 10.50))
    private val brakingAfter = listOf(129.0, 128.0, 128.0, 127.0, 129.0, 125.0)
    private val minimumAfter = listOf(74.0, 73.0, 74.0, 73.0, 74.0, 72.0)

    private fun record(analysis: SessionAnalysis, id: String, start: Long, coached: CoachedSession = CoachedSession()) =
        assertNotNull(sessionCoachingRecord(analysis, track, id, start, coached))

    private val s0 by lazy { record(session(), "s0", 1_000) }

    private fun s1(analysis: SessionAnalysis = session(after, brakingAfter, minimumAfter)) =
        record(analysis, "s1", 2_000, CoachedSession(focusFrom = "s0", cues = mapOf("c1" to 5, "c2" to 1)))

    /** What came of the primary focus, c1. */
    private fun SessionCoaching.c1(): FocusAttempt = attempts.first { it.ask.cornerId == "c1" }

    @Test
    fun `after a focus the history records the measured change on comparable passes`() {
        // The session before recommends c1: brake later and carry more minimum speed.
        val ask = s0.focus.first()
        assertEquals("c1", ask.cornerId)
        assertTrue(ask.primary)
        assertEquals(
            setOf(FocusMetric.BrakingPoint.code to 1, FocusMetric.MinimumSpeed.code to 1),
            ask.changes.map { it.metric to it.direction }.toSet(),
        )

        val history = coachingHistory("s1", listOf(s0, s1()))
        // The primary first, then the secondary focuses, each with its own result.
        assertEquals(s0.focus.map { it.cornerId }, history.attempts.map { it.ask.cornerId })
        val attempt = history.c1()
        assertEquals(FocusVerdict.Improved, attempt.verdict)
        assertTrue(attempt.coached)
        assertEquals(5, attempt.cues)
        assertEquals(6, attempt.passes)
        assertEquals(5, attempt.fasterPasses)
        assertTrue(attempt.repeated)
        assertTrue(attempt.behaviourChanged)
        assertEquals(0.18, attempt.gainSeconds!!, 1e-9)
        val braking = attempt.shifts.first { it.metric == FocusMetric.BrakingPoint }
        // Metres from the corner's start, so it compares across sessions whatever lap is the axis.
        assertEquals(120.5 - 150.0, braking.before, 1e-9)
        assertEquals(7.5, braking.delta, 1e-9)
        assertTrue(braking.precise)
        assertEquals(false, attempt.topThree)
        assertEquals(
            "You worked on Corner 1 with the coach in this session (5 cues there). The braking point moved " +
                "8 m later, the minimum speed rose by 3 km/h and the median corner time improved by 0.18 s; " +
                "5 of 6 counted passes were faster than before. This is no longer a top-three loss.",
            attemptText(attempt),
        )
    }

    @Test
    fun `without the coach the session worked on the latest focus before it`() {
        val uncoached = record(session(after, brakingAfter, minimumAfter), "s1", 2_000)
        val attempt = coachingHistory("s1", listOf(s0, uncoached)).c1()
        assertFalse(attempt.coached)
        assertEquals(0, attempt.cues)
        assertTrue(attemptText(attempt).startsWith("Corner 1 was this session's focus. The braking point"))
    }

    @Test
    fun `the session the coach worked from is found even when the clock put it later`() {
        // The phone clock was set back: s1 starts "before" s0, whose focus its coach loaded.
        val earlierClock = record(
            session(after, brakingAfter, minimumAfter), "s1", 500,
            CoachedSession(focusFrom = "s0", cues = mapOf("c1" to 5)),
        )
        val attempt = coachingHistory("s1", listOf(s0, earlierClock)).c1()
        assertEquals("s0", attempt.source.sessionId)
        assertTrue(attempt.coached)
        assertEquals(FocusVerdict.Improved, attempt.verdict)
        // Without the coach's word, only a session before it can be the one it worked on.
        assertTrue(coachingHistory("s1", listOf(s0, earlierClock.copy(coachedFrom = null))).empty)
        // That attempt is not in the past of its source, nor of a session the clock put between them:
        // neither checks whether its gain held.
        val between = later("s2", 700, listOf(10.22, 10.30, 10.26, 10.29, 10.28, 10.45), brakingAfter)
        val records = listOf(s0, earlierClock, between)
        assertTrue(coachingHistory("s0", records).checks.isEmpty())
        assertTrue(coachingHistory("s2", records).checks.isEmpty())
    }

    @Test
    fun `a corner left out for its claim, not its passes, is withheld in the record`() {
        // c4's gap rests on lap 1 alone: the gap is withheld though every lap there is High.
        val oneLap = before + ("c4" to listOf(4.70, 5.11, 5.10, 5.10, 5.11, 5.12))
        val s = record(session(oneLap, brakingAfter, minimumAfter), "s1", 2_000, CoachedSession(focusFrom = "s0"))
        val c4 = assertNotNull(s.corner("c4"))
        assertNull(c4.focusRank)
        assertTrue(c4.withheld)
        // So a focus on c4 from before says nothing about it being a top-three loss now.
        val asked = s0.copy(focus = listOf(FocusAsk("c4", "Corner 4", "primary", "HIGH")))
        val attempt = coachingHistory("s1", listOf(asked, s)).attempts.single()
        assertNull(attempt.topThree)
    }

    @Test
    fun `a number is said only when every pass behind it is High and its channel measured`() {
        // One slower pass (lap 6) Medium in c1 after the focus: the time and every change lose their numbers.
        val medium = s1(session(after, brakingAfter, minimumAfter, graded = mapOf(5 to Confidence.MEDIUM)))
        val attempt = coachingHistory("s1", listOf(s0, medium)).c1()
        assertFalse(attempt.timePrecise)
        assertTrue(attempt.shifts.none { it.precise })
        assertEquals(
            "You worked on Corner 1 with the coach in this session (5 cues there). The braking point moved " +
                "later, the minimum speed rose and the median corner time improved; " +
                "5 of 6 counted passes were faster than before. This is no longer a top-three loss.",
            attemptText(attempt),
        )
        assertFalse(Regex("\\d+(\\.\\d+)? (m|s|km/h)\\b").containsMatchIn(attemptText(attempt)), attemptText(attempt))

        // All High, but the brake channel not measured and the speed of unknown origin: the time keeps
        // its number, braking and speed do not.
        val unmeasured = s1(session(after, brakingAfter, minimumAfter, brake = null, speed = Provenance.CALCULATED))
        val other = coachingHistory("s1", listOf(s0, unmeasured)).c1()
        assertTrue(other.timePrecise)
        assertTrue(other.shifts.none { it.precise })
        val text = attemptText(other)
        assertTrue("The braking point moved later, the minimum speed rose and" in text, text)
        assertTrue("improved by 0.18 s" in text, text)
    }

    @Test
    fun `Low confidence passes are never counted`() {
        // Lap 6 after the focus (the slow 10.50) and lap 1 before it (the fast 10.10) are Low in c1.
        // (Graded Low in the record itself: Low in the analysis, lap 1 would also take c1 out of the focus.)
        val before = s0.copy(
            corners = s0.corners.map { c ->
                val passes = c.passes.map { if (it.lap == 1) it.copy(confidence = "LOW") else it }
                if (c.cornerId != "c1") c else c.copy(passes = passes)
            },
        )
        val lowAfter = s1(session(after, brakingAfter, minimumAfter, graded = mapOf(5 to Confidence.LOW)))
        val attempt = coachingHistory("s1", listOf(before, lowAfter)).c1()
        assertEquals(5, attempt.passes)
        assertEquals(5, attempt.fasterPasses)
        // Before: the median of 10.50, 10.12, 10.45, 10.48, 10.50; after: of 10.20, 10.30, 10.25, 10.28, 10.29.
        assertEquals(10.48, attempt.medianBefore!!, 1e-9)
        assertEquals(10.28, attempt.medianAfter!!, 1e-9)
        // Every pass counted is High, so the numbers stay.
        assertTrue(attempt.timePrecise)
        assertTrue("5 of 5 counted passes" in attemptText(attempt), attemptText(attempt))

        // With too few countable passes it says it is too early, never a result.
        val mostlyLow = s1(
            session(after, brakingAfter, minimumAfter, graded = (0 until 4).associateWith { Confidence.LOW }),
        )
        val early = coachingHistory("s1", listOf(s0, mostlyLow)).c1()
        assertEquals(FocusVerdict.TooFewPasses, early.verdict)
        assertEquals(2, early.passes)
        assertNull(early.gainSeconds)
    }

    /** A later session with c1 at [times] and braking at [braking]; the coach worked on nothing. */
    private fun later(
        id: String,
        start: Long,
        times: List<Double>,
        braking: List<Double>,
        minimum: List<Double> = minimumAfter,
    ) = record(session(before + ("c1" to times), braking, minimum), id, start)

    @Test
    fun `a later session marks the gain persisted, partly persisted or reverted`() {
        val records = listOf(s0, s1())
        // Gain 0.18 s and braking 7.5 m later in s1; each later session against s0's baseline.
        val held = later("s2", 3_000, listOf(10.22, 10.30, 10.26, 10.29, 10.28, 10.45), brakingAfter)
        val partly = later("s3", 4_000, listOf(10.30, 10.40, 10.35, 10.38, 10.39, 10.50), brakingAfter)
        val lost = later("s4", 5_000, listOf(10.40, 10.48, 10.46, 10.47, 10.50, 10.52), brakingBefore, minimumBefore)

        fun check(s: SessionCoachingRecord) =
            coachingHistory(s.sessionId, records + s).checks.first { it.attempt.ask.cornerId == "c1" }

        val c2 = check(held)
        assertEquals(Persistence.Persisted, c2.persistence)
        assertEquals(0.18, c2.attempt.gainSeconds!!, 1e-9)
        assertEquals(0.18, c2.gainSeconds, 1e-9)
        assertEquals("s1", c2.attempt.session.sessionId)
        assertEquals(
            "Corner 1, worked on 1 Jan: the gain held. The median corner time is 0.18 s better than before " +
                "the focus, the braking point 8 m later and the minimum speed 3 km/h higher.",
            checkText(c2),
        )
        assertEquals(Persistence.Partial, check(partly).persistence)
        // Time back where it was but the braking still later: partly, not lost.
        val mixed = later("s6", 7_000, listOf(10.40, 10.48, 10.46, 10.47, 10.50, 10.52), brakingAfter)
        assertEquals(Persistence.Partial, check(mixed).persistence)
        val c4 = check(lost)
        assertEquals(Persistence.Reverted, c4.persistence)
        assertTrue(checkText(c4).startsWith("Corner 1, worked on 1 Jan: the gain was lost."), checkText(c4))

        // A session that works on the corner again shows the new attempt, not a check of the old one.
        val again = record(session(after, brakingAfter, minimumAfter), "s5", 6_000, CoachedSession(focusFrom = "s1"))
        val withS1Focus = records.map {
            if (it.sessionId == "s1") it.copy(focus = s0.focus) else it
        } + again
        val shown = coachingHistory("s5", withS1Focus)
        assertEquals("c1", shown.attempts.first().ask.cornerId)
        assertTrue(shown.checks.none { it.attempt.ask.cornerId == "c1" })
    }

    @Test
    fun `no result across a corner identity revision, nor from a focus whose record is gone`() {
        val revised = record(session(after, brakingAfter, minimumAfter, revision = 2), "s1", 2_000)
        assertEquals(FocusVerdict.NotComparable, coachingHistory("s1", listOf(s0, revised)).c1().verdict)

        // The coach worked from a session the history does not have: nothing is made up.
        val orphan = s1().copy(coachedFrom = "gone")
        assertTrue(coachingHistory("s1", listOf(s0, orphan)).empty)
        // A session the history does not have shows nothing.
        assertTrue(coachingHistory("nope", listOf(s0, s1())).empty)
    }

    @Test
    fun `the store keeps the history, reads a damaged or misplaced file as none, and outlives the session`() {
        val dir = File(tmp, "coach/history")
        val store = CoachingHistoryStore(dir.path)
        assertTrue(store.save(s0))
        assertFalse(store.save(s0))
        assertTrue(store.save(s1()))
        assertEquals(listOf("s0", "s1"), store.history(track).map { it.sessionId })
        assertEquals(s0, store.get(track, "s0"))

        // Damaged, from a newer format, or in another session's place: none of them is read.
        File(dir, "$track/bad.json").writeText("{not json")
        File(dir, "$track/newer.json").writeText(
            File(dir, "$track/s0.json").readText().replace("\"version\":1", "\"version\":99")
                .replace("\"sessionId\":\"s0\"", "\"sessionId\":\"newer\""),
        )
        File(dir, "$track/moved.json").writeText(File(dir, "$track/s0.json").readText())
        File(dir, "$track/negative.json").writeText(
            File(dir, "$track/s0.json").readText().replace("\"sessionId\":\"s0\"", "\"sessionId\":\"negative\"")
                .replaceFirst(Regex("\"seconds\":[0-9.]+"), "\"seconds\":-1.0"),
        )
        assertEquals(listOf("s0", "s1"), store.history(track).map { it.sessionId })

        // Deleting the session (its recording and its focus record, FBBD-20) keeps what was learned.
        val focus = SessionFocusStore(File(tmp, "coach/focus").path)
        focus.save(SessionFocusRecord(trackId = track, sessionId = "s0", sessionStartUtcMillis = 1_000,
            targets = listOf(FocusTarget(FocusTarget.idOf("s0", "c1"), "primary", "c1", "c1", 1, "Corner 1",
                "sectorGap", "HIGH"))))
        assertTrue(focus.deleteSession("s0"))
        assertEquals(FocusVerdict.Improved, coachingHistory("s1", store.history(track)).c1().verdict)

        // Deleting the track deletes it.
        assertTrue(store.delete(track))
        assertTrue(store.history(track).isEmpty())
    }

    @Test
    fun `the history comes back with a backup, and a record made before a restore is not written over it`() {
        val phone = File(tmp, "phone")
        CoachingHistoryStore(File(phone, "coach/history").path).apply {
            save(s0)
            save(s1())
        }
        val bytes = ByteArrayOutputStream().also {
            Backup.write(phone, mapOf("setup_done" to true), "0.3.8", false, it, nowUtcMillis = 1_760_000_000_000)
        }.toByteArray()

        // Restored on another phone, without the sessions: the history is all there.
        val tablet = File(tmp, "tablet").apply { mkdirs() }
        val restorer = BackupRestore(tablet, File(tablet, "backup-staging"))
        restorer.restore(restorer.unpack(bytes.inputStream()), RestoreMode.MERGE, emptyMap()) {}
        val restored = CoachingHistoryStore(File(tablet, "coach/history").path)
        assertEquals(listOf("s0", "s1"), restored.history(track).map { it.sessionId })
        assertEquals(FocusVerdict.Improved, coachingHistory("s1", restored.history(track)).c1().verdict)

        // A session read before a restore: its record is not written into the restored data (FBBD-156).
        val gate = DataGate()
        val epoch = gate.epoch
        gate.exclusive { restored.delete(track) }
        val stale = record(session(), "s2", 3_000)
        assertFalse(gate.writeIfCurrent(epoch) { restored.save(stale) })
        assertNull(restored.get(track, "s2"))
    }

    @Test
    fun `a coach that worked on the line plan's corners is judged on them, not on the summary's focus`() {
        val coached = CoachedSession(focusFrom = "s0", cues = mapOf("c3" to 2), planCorners = listOf("c3", "c1"))
        val s1 = record(session(after, brakingAfter, minimumAfter), "s1", 2_000, coached)
        assertEquals(listOf("c3", "c1"), s1.coachedCorners)
        val history = coachingHistory("s1", listOf(s0, s1))
        assertEquals(listOf("c3", "c1"), history.attempts.map { it.ask.cornerId })
        assertEquals(listOf("Corner 3", "Corner 1"), history.attempts.map { it.ask.name })
        assertTrue(history.attempts.first().ask.primary)
        assertTrue(history.attempts.all { it.coached })
        assertEquals(2, history.attempts.first().cues)
        // The plan says how to drive them: no change asked, the corner time decides.
        assertEquals(FocusVerdict.Improved, history.c1().verdict)
        assertTrue(history.c1().shifts.isEmpty())
        // The plan's corners only count for the session the coach worked from.
        assertTrue(record(session(after), "s2", 3_000, coached.copy(focusFrom = null)).coachedCorners.isEmpty())
    }

    @Test
    fun `the coach's events say which focus it worked on and its cues at each corner`() {
        val brake = "kind=Corner corner=1 corner_id=c1 conf=HIGH text=Brake later"
        val events = listOf(
            RecorderEvent(1, CoachEvents.FOCUS, CoachEvents.focusText("s0", listOf("c1", "c3"))),
            RecorderEvent(5, CoachEvents.CUE, "seq=1 lap=2 $brake"),
            RecorderEvent(6, CoachEvents.CUE, "seq=2 lap=3 $brake"),
            RecorderEvent(7, CoachEvents.CUE, "seq=3 lap=3 kind=Corner corner=3 conf=HIGH text=Carry speed"),
            RecorderEvent(8, CoachEvents.CUE, "seq=4 lap=3 kind=Lap text=1:20.1"),
            // A restarted coach logs its focus again; the last one counts.
            RecorderEvent(9, CoachEvents.FOCUS, CoachEvents.focusText("s9", listOf("c2"))),
        )
        assertEquals("s9", CoachEvents.focusSource(events))
        assertEquals(mapOf("c1" to 2), CoachEvents.cuesByCorner(events))
        assertNull(CoachEvents.focusSource(events.filter { it.kind != CoachEvents.FOCUS }))
        // A line plan's corners only when the coach's last word was the plan (UI-174 A).
        assertTrue(CoachEvents.planCorners(events).isEmpty())
        val plan = RecorderEvent(10, CoachEvents.PLAN, CoachEvents.planText("s9", listOf("c4", "c2"), 0.6))
        assertEquals(listOf("c4", "c2"), CoachEvents.planCorners(events + plan))
        assertEquals("s9", CoachEvents.focusSource(events + plan))
        assertTrue(CoachEvents.planCorners(listOf(plan) + events.map { it.copy(tMicros = it.tMicros + 10) }).isEmpty())
    }
}
