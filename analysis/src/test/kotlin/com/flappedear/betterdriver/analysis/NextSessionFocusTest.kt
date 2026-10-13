@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.confidence.ClaimCodes
import com.flappedear.betterdriver.analysis.confidence.ConfidenceReason
import com.flappedear.betterdriver.analysis.confidence.Graded
import com.flappedear.betterdriver.analysis.confidence.SegmentConfidence
import com.flappedear.betterdriver.analysis.confidence.SessionConfidence
import com.flappedear.betterdriver.analysis.corners.BrakingMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapMetrics
import com.flappedear.betterdriver.analysis.corners.CornerLapObservation
import com.flappedear.betterdriver.analysis.corners.CornerSpeeds
import com.flappedear.betterdriver.analysis.corners.ExitMetrics
import com.flappedear.betterdriver.analysis.focus.FocusChange
import com.flappedear.betterdriver.analysis.focus.FocusMetric
import com.flappedear.betterdriver.analysis.focus.FocusRole
import com.flappedear.betterdriver.analysis.focus.MaxSecondaryFocus
import com.flappedear.betterdriver.analysis.focus.changeText
import com.flappedear.betterdriver.analysis.focus.nextSessionPlan
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
import com.flappedear.betterdriver.analysis.segments.segmentLine
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.CueEngine
import com.flappedear.betterdriver.coach.FocusTarget
import com.flappedear.betterdriver.coach.SessionFocusRecord
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.coachFixes
import com.flappedear.betterdriver.coach.tracedLaps
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.Provenance
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What to work on next session (FBBD-20): one primary corner and at most two secondary ones, chosen
 * from the confidence-gated focus areas (FBBD-177), each with its time available and what the faster
 * passes did differently; and the focus handed to the next session's coach at the track.
 */
class NextSessionFocusTest {
    /**
     * Six laps through six corners, built by hand. Lap 2 is the fastest lap; in each corner laps 1
     * and 3 are faster than it, so what the best of each shows is seen on two laps, except in c6,
     * where only lap 1 was faster: an improvement seen on one lap only.
     */
    private val times = mapOf(
        "c1" to listOf(10.10, 10.50, 10.12, 10.45, 10.48, 10.50),
        "c2" to listOf(8.00, 8.20, 8.02, 8.18, 8.20, 8.22),
        "c3" to listOf(6.05, 6.17, 6.06, 6.15, 6.16, 6.17),
        "c4" to listOf(5.00, 5.11, 5.01, 5.10, 5.11, 5.12),
        "c5" to listOf(4.00, 4.06, 4.01, 4.05, 4.06, 4.06),
        "c6" to listOf(3.50, 3.80, 3.79, 3.80, 3.81, 3.80),
    )

    /** In c1 the two fastest passes (laps 1 and 3) brake 8 m later and keep 4 km/h more minimum speed. */
    private fun c1(lap: Int): CornerLapObservation {
        val fast = lap == 1 || lap == 3
        val braking = if (fast) 128.0 else listOf(120.0, 121.0, 119.0, 120.0)[listOf(2, 4, 5, 6).indexOf(lap)]
        val minimum = if (fast) 74.0 else listOf(70.0, 71.0, 70.0, 69.0)[listOf(2, 4, 5, 6).indexOf(lap)]
        return CornerLapObservation(
            lapReference = lap,
            brakingPointMeters = braking,
            brakingProvenance = "measured",
            minimumSpeed = minimum,
            exitSpeed = 100.0,
            speedUnit = "km/h",
        )
    }

    private fun handBuilt(
        drop: Set<String> = emptySet(),
        times: Map<String, List<Double>> = this.times,
    ): SessionAnalysis {
        val ids = times.keys.filter { it !in drop }
        val laps = (1..6).map { n -> TimedLap(n, n * 100.0, n * 100.0 + 80.0 + (if (n == 2) 0.0 else 0.5), 80.0) }
        val segments = ids.mapIndexed { i, id ->
            TrackSegment(id, "Corner ${i + 1}", TrackSegmentType.Corner, i * 100.0, i * 100.0 + 50.0)
        }
        val sectorTimes = (1..6).map { n ->
            LapSectorTimes(
                n,
                sectors = segments.map {
                    val seconds = times.getValue(it.id)[n - 1]
                    SectorTime(it.id, it.name, it.type, it.startProgressMeters, it.endProgressMeters, seconds = seconds)
                },
            )
        }
        val rows = segments.map { s ->
            val t = times[s.id]!!
            val best = t.min()
            TheoreticalBestRow(
                s.id, s.name, s.type, s.startProgressMeters, s.endProgressMeters, best, t.indexOf(best) + 1,
                actualSeconds = t[1], lossSeconds = t[1] - best,
            )
        }
        val metrics = (1..6).map { n -> CornerLapMetrics(n, CornerSpeeds(), BrakingMetrics(), ExitMetrics(), c1(n)) }
        return SessionAnalysis(
            LapSession(LapSessionStatus.Available, timedLaps = laps, fastestLapIndex = 1),
            axisLapNumber = 2,
            segments = ApprovedSegments(segments, "t"),
            lapTimes = sectorTimes,
            summary = TheoreticalBestSummary(rows, rows.sortedByDescending { it.lossSeconds }),
            cornerMetrics = if ("c1" in ids) mapOf("c1" to metrics) else emptyMap(),
        )
    }

    @Test
    fun `one primary focus, at most two secondary ones, and a corner seen on one lap is never one`() {
        val analysis = handBuilt()
        val plan = nextSessionPlan(analysis)
        val primary = assertNotNull(plan.primary)
        assertEquals("c1", primary.segmentId)
        assertEquals(FocusRole.Primary, primary.role)
        assertEquals(Confidence.HIGH, primary.confidence.level)
        assertEquals(0.40, primary.timeAvailableSeconds!!, 1e-9)
        // Never every loss: c3, c4 and c5 leave time too, but two secondary focuses at most.
        assertEquals(MaxSecondaryFocus, plan.secondary.size)
        assertEquals(listOf("c2", "c3"), plan.secondary.map { it.segmentId })
        assertTrue(plan.secondary.all { it.role == FocusRole.Secondary })
        // c6 rests on one lap: Low confidence, left out and named as such.
        assertTrue(plan.focuses.none { it.segmentId == "c6" })
        val withheld = plan.withheld.single { it.segmentId == "c6" }
        assertEquals(Confidence.LOW, withheld.confidence.level)
        assertTrue(withheld.confidence.has(ClaimCodes.ONE_LAP), withheld.confidence.why)
        assertNull(plan.identityRevision)
    }

    @Test
    fun `each focus says what the faster passes did differently`() {
        val primary = assertNotNull(nextSessionPlan(handBuilt()).primary)
        assertEquals(2, primary.fasterLaps)
        assertEquals(4, primary.otherLaps)
        assertEquals(listOf(FocusMetric.BrakingPoint, FocusMetric.MinimumSpeed), primary.changes.map { it.metric })
        assertEquals(8.0, primary.changes[0].delta, 1e-9)
        assertEquals(4.0, primary.changes[1].delta, 1e-9)
        assertEquals(
            "Your 2 fastest passes braked 8 m later and carried 4 km/h more minimum speed than your other 4.",
            primary.what,
        )
    }

    @Test
    fun `an inferred braking point or a calculated speed is said without its number, even at High`() {
        val analysis = handBuilt().let { a ->
            a.copy(
                cornerMetrics = a.cornerMetrics.mapValues { (_, laps) ->
                    laps.map { it.copy(observation = it.observation.copy(brakingProvenance = "inferred")) }
                },
                provenance = AnalysisProvenance(speed = Provenance.CALCULATED),
            )
        }
        val primary = assertNotNull(nextSessionPlan(analysis).primary)
        assertEquals(Confidence.HIGH, primary.confidence.level)
        assertEquals(listOf(false, false), primary.changes.map { it.measured })
        assertEquals(
            "Your 2 fastest passes braked later and carried more minimum speed than your other 4.",
            primary.what,
        )
        // A braking point from a brake channel of unknown origin is no measurement either.
        val unknownBrake = assertNotNull(
            nextSessionPlan(handBuilt().copy(provenance = AnalysisProvenance(brake = Provenance.UNKNOWN))).primary,
        )
        assertEquals(listOf(false, true), unknownBrake.changes.map { it.measured })
        assertEquals(
            "Your 2 fastest passes braked later and carried 4 km/h more minimum speed than your other 4.",
            unknownBrake.what,
        )
        // Measured and inferred in one sentence: only the measured one has its number.
        val mixed = listOf(
            FocusChange(FocusMetric.BrakingPoint, 120.0, 128.0, "m", measured = false),
            FocusChange(FocusMetric.MinimumSpeed, 70.0, 74.0, "km/h"),
        )
        assertEquals(
            "Your 2 fastest passes braked later and carried 4 km/h more minimum speed than your other 4.",
            changeText(mixed, 2, 4, Confidence.HIGH),
        )
    }

    /** [handBuilt] with no braking point on the slower laps in [missing]. */
    private fun withoutBraking(missing: Set<Int>): SessionAnalysis = handBuilt().let { a ->
        a.copy(
            cornerMetrics = a.cornerMetrics.mapValues { (_, laps) ->
                laps.map { m ->
                    val none = m.observation.copy(brakingPointMeters = null)
                    if (m.lapReference in missing) m.copy(observation = none) else m
                }
            },
        )
    }

    @Test
    fun `each change rests on two passes of each group at least, and says how many it rests on`() {
        // Three of the four slower laps have no braking point: one is no median, so no braking change.
        val one = assertNotNull(nextSessionPlan(withoutBraking(setOf(4, 5, 6))).primary)
        assertEquals(listOf(FocusMetric.MinimumSpeed), one.changes.map { it.metric })
        assertEquals("Your 2 fastest passes carried 4 km/h more minimum speed than your other 4.", one.what)

        // Two left: the braking change rests on 2 and 2, the speed on 2 and 4, so no count is said.
        val two = assertNotNull(nextSessionPlan(withoutBraking(setOf(5, 6))).primary)
        assertEquals(listOf(2 to 2, 2 to 4), two.changes.map { it.fasterCount to it.otherCount })
        assertEquals(
            "Your fastest passes braked 8 m later and carried 4 km/h more minimum speed than your others.",
            two.what,
        )
    }

    @Test
    fun `below High confidence what to change is said without numbers, and at Low not at all`() {
        val changes = listOf(
            FocusChange(FocusMetric.BrakingPoint, 120.0, 112.0, "m"),
            FocusChange(FocusMetric.MinimumSpeed, 70.0, 74.0, "km/h"),
            FocusChange(FocusMetric.ExitSpeed, 100.0, 104.0, "km/h"),
        )
        assertEquals(
            "Your 3 fastest passes braked 8 m earlier, carried 4 km/h more minimum speed and left the corner " +
                "4 km/h faster than your other 5.",
            changeText(changes, 3, 5, Confidence.HIGH),
        )
        val medium = changeText(changes, 3, 5, Confidence.MEDIUM)
        assertEquals(
            "Your fastest passes braked earlier, carried more minimum speed and left the corner faster " +
                "than your others.",
            medium,
        )
        assertFalse(Regex("\\d").containsMatchIn(medium), medium)
        assertEquals("", changeText(changes, 3, 5, Confidence.LOW))
    }

    /** [analysis] with lap [index] (0-based) graded Medium through c1 only, the rest of c1 High. */
    private fun mediumLap(index: Int, analysis: SessionAnalysis = handBuilt()): SessionAnalysis = analysis.let { a ->
        val medium = Graded.of(ConfidenceReason("gps_accuracy", Confidence.MEDIUM, "lap ${index + 1}: weak positions"))
        val laps = (0 until 6).map { if (it == index) medium else Graded.High }
        a.copy(confidence = SessionConfidence(segments = mapOf("c1" to SegmentConfidence("c1", laps = laps))))
    }

    @Test
    fun `a change or a time resting on one pass below High is said without its number`() {
        // Lap 5, a slower pass that the braking and speed medians use, is Medium in c1; the focus is High.
        val primary = assertNotNull(nextSessionPlan(mediumLap(4)).primary)
        assertEquals("c1", primary.segmentId)
        assertEquals(Confidence.HIGH, primary.confidence.level)
        assertEquals(listOf(Confidence.MEDIUM, Confidence.MEDIUM), primary.changes.map { it.confidence })
        assertTrue(primary.changes.none { it.precise })
        assertEquals(
            "Your 2 fastest passes braked later and carried more minimum speed than your other 4.",
            primary.what,
        )
        // Its time is lap 2's loss to lap 1 (0.40 s), both High there: it keeps its number.
        assertEquals(0.40, primary.timeAvailableSeconds!!, 1e-9)
        assertEquals(Confidence.HIGH, primary.timeConfidence)
        // On the High-only session every change and the time keep their numbers.
        val high = assertNotNull(nextSessionPlan(handBuilt()).primary)
        assertTrue(high.changes.all { it.precise && it.confidence == Confidence.HIGH })
        assertEquals(Confidence.HIGH, high.timeConfidence)

        // With laps 4 to 6 slower still in c1, its time is what a typical pass leaves against the
        // second fastest (10.55 - 10.12 s), from every pass: with lap 5 Medium, no number.
        val typical = handBuilt(times = times + ("c1" to listOf(10.10, 10.50, 10.12, 10.60, 10.62, 10.64)))
        val c1 = assertNotNull(nextSessionPlan(mediumLap(4, typical)).primary)
        assertEquals("c1", c1.segmentId)
        assertEquals(0.43, c1.timeAvailableSeconds!!, 1e-9)
        assertEquals(Confidence.MEDIUM, c1.timeConfidence)
        assertEquals(Confidence.HIGH, assertNotNull(nextSessionPlan(typical).primary).timeConfidence)
    }

    @Test
    fun `a speed without its unit is said without its number`() {
        val noUnit = listOf(FocusChange(FocusMetric.MinimumSpeed, 70.0, 74.0, ""))
        assertFalse(noUnit.single().precise)
        assertEquals(
            "Your 2 fastest passes carried more minimum speed than your other 4.",
            changeText(noUnit, 2, 4, Confidence.HIGH),
        )
        val medium = listOf(FocusChange(FocusMetric.BrakingPoint, 120.0, 128.0, "m", confidence = Confidence.MEDIUM))
        assertEquals("Your 2 fastest passes braked later than your other 4.", changeText(medium, 2, 4, Confidence.HIGH))
    }

    @Test
    fun `with fewer corners worth it there are fewer focuses, and with none there is no plan`() {
        // Without c2 and c3, c4 is the only other corner within a quarter of the primary's time.
        val plan = nextSessionPlan(handBuilt(drop = setOf("c2", "c3")))
        assertEquals("c1", plan.primary?.segmentId)
        assertEquals(listOf("c4"), plan.secondary.map { it.segmentId })
        // Only the corner seen on one lap: nothing to work on, and it is named as left out.
        val none = nextSessionPlan(handBuilt(drop = setOf("c1", "c2", "c3", "c4", "c5")))
        assertNull(none.primary)
        assertTrue(none.focuses.isEmpty())
        assertEquals(listOf("c6"), none.withheld.map { it.segmentId })
    }

    @Test
    fun `the focus of an analysed session is handed to the next session's coach at the track`() {
        val sim = SimTrack.rectangle()
        val first = sim.session(laps = 5, sessionId = "first")
        val analysis = analyzeSession(telemetrySessionOf(first, sim.track()))
        val plan = nextSessionPlan(analysis)
        val primary = assertNotNull(plan.primary, "no focus from ${analysis.laps.timedLaps.size} laps")
        assertTrue(plan.secondary.size <= MaxSecondaryFocus)
        val identity = assertNotNull(analysis.identity)
        assertEquals(identity.number, plan.identityRevision)
        // Kept as the app keeps it: each focus under its CornerId, with its line on the fastest lap.
        val axis = assertNotNull(analysis.axis)
        val segments = assertNotNull(analysis.segments).segments.associateBy { it.id }
        val record = SessionFocusRecord(
            trackId = sim.id,
            sessionId = "first",
            sessionStartUtcMillis = 0,
            targets = plan.focuses.map { f ->
                val line = segmentLine(axis, segments.getValue(f.segmentId))
                FocusTarget(
                    id = FocusTarget.idOf("first", f.segmentId),
                    role = f.role.code,
                    segmentId = f.segmentId,
                    cornerId = f.segmentId,
                    identityRevision = plan.identityRevision,
                    name = f.name,
                    kind = f.area.kind.code,
                    confidence = f.confidence.level.name,
                    timeAvailableSeconds = f.timeAvailableSeconds,
                    lats = line.map { it.latitudeDegrees },
                    lons = line.map { it.longitudeDegrees },
                )
            },
        )
        // The next session's coach, at the track with its corner identity and the first session as reference.
        val track = sim.track().copy(identity = identity)
        val reference = assertNotNull(
            buildReference(track, listOf(SessionLaps("first", tracedLaps(track, first.coachFixes())))),
        )
        val engine = CueEngine(track, CoachSettings(Verbosity.Focus), reference, focus = record.picks())
        val primaryCorners = reference.corners.filter { it.cornerId == primary.segmentId }.map { it.number }
        assertTrue(primaryCorners.isNotEmpty(), "no braking corner in ${primary.name}")
        // The coach works on the primary focus's corners first.
        assertEquals(primaryCorners, engine.focusCorners.take(primaryCorners.size))
    }
}
