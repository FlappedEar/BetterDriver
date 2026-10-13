@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceInferred
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceMeasured
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.driving.DrivingState
import com.flappedear.betterdriver.analysis.driving.DrivingStatesLateralCalculated
import com.flappedear.betterdriver.analysis.driving.DrivingStatesNoChannels
import com.flappedear.betterdriver.analysis.driving.DrivingStatesNoLateral
import com.flappedear.betterdriver.analysis.driving.cornerCoasting
import com.flappedear.betterdriver.analysis.driving.drivingStates
import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.progress.ProgressSegment
import com.flappedear.betterdriver.analysis.progress.ProjectedSample
import com.flappedear.betterdriver.analysis.progress.TrackFeatureSample
import com.flappedear.betterdriver.analysis.progress.TrackFeatures
import com.flappedear.betterdriver.analysis.segments.ApprovedSegments
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Driving states (FBBD-175) on hand-made laps whose answer is known exactly: 10 s at 20 m/s,
 * read every 0.1 s, each stretch taking the state of the fix it starts at.
 */
class DrivingStatesTest {
    private val steps = 100
    private val times = DoubleArray(steps + 1) { it / 10.0 }

    /** One run along a 200 m axis at 20 m/s. */
    private val trace = listOf(
        ProgressSegment(MutableList(steps + 1) { ProjectedSample(times[it], 20.0 * times[it], true) }),
    )

    private fun channel(name: String, unit: String, value: (Int) -> Double) =
        TelemetryChannel(name, times, DoubleArray(steps + 1) { value(it) }, unit)

    private fun session(vararg channels: TelemetryChannel): TelemetrySession {
        val aliases = mapOf(
            Aliases.BRAKE to "brake",
            Aliases.THROTTLE to "throttle",
            Aliases.LONGITUDINAL_ACCELERATION to "longacc",
            Aliases.LATERAL_ACCELERATION to "latacc",
            Aliases.SPEED to "speed",
        ).filterValues { name -> channels.any { it.name == name } }
        return TelemetrySession(channels.associateBy { it.name }, aliases)
    }

    // Braking for 2 s, off both pedals for 1.5 s, a part throttle through a bend for 1.5 s,
    // accelerating for 2 s, full throttle for 2 s, then off the throttle for the last second.
    private val brake = channel("brake", "%") { if (it < 20) 50.0 else 0.0 }
    private val throttle = channel("throttle", "%") {
        when {
            it < 35 -> 0.0
            it < 50 -> 10.0
            it < 70 -> 50.0
            it < 90 -> 100.0
            else -> 3.0
        }
    }
    private val lateral = channel("latacc", "g") { if (it in 35 until 50) -0.5 else 0.0 }

    private fun assertSeconds(expected: Map<DrivingState, Double>, actual: (DrivingState) -> Double) {
        for (s in DrivingState.entries) assertEquals(expected[s] ?: 0.0, actual(s), 1e-9, s.name)
    }

    @Test
    fun `measured pedals give every state, and time and distance add up to the lap`() {
        val states = drivingStates(session(brake, throttle, lateral), trace, 200.0, null, 7)
        assertEquals(BrakingProvenanceMeasured, states.provenance)
        assertEquals(7, states.lapReference)
        val totals = states.totals()
        val expected = mapOf(
            DrivingState.Braking to 2.0,
            DrivingState.Coasting to 2.5,
            DrivingState.Cornering to 1.5,
            DrivingState.Accelerating to 2.0,
            DrivingState.FullThrottle to 2.0,
        )
        assertSeconds(expected, totals::seconds)
        for (s in DrivingState.entries) assertEquals(20.0 * (expected[s] ?: 0.0), totals.meters(s), 1e-9, s.name)
        assertEquals(1.0, totals.coverage, 1e-12)
        assertEquals(
            listOf(
                DrivingState.Braking, DrivingState.Coasting, DrivingState.Cornering,
                DrivingState.Accelerating, DrivingState.FullThrottle, DrivingState.Coasting,
            ),
            states.runs.map { it.state },
        )
        assertEquals(40.0, states.runs[1].startMeters, 1e-9)
        assertEquals(70.0, states.runs[1].endMeters, 1e-9)
    }

    @Test
    fun `a range counts the share of each stretch inside it, across the start line too`() {
        val states = drivingStates(session(brake, throttle, lateral), trace, 200.0, null)
        val entry = states.totals(30.0, 70.0)
        assertSeconds(mapOf(DrivingState.Braking to 0.5, DrivingState.Coasting to 1.5), entry::seconds)
        // Half a stretch: 35 m to 41 m is 5 m of braking and 1 m of coasting.
        val part = states.totals(35.0, 41.0)
        assertEquals(0.25, part.seconds(DrivingState.Braking), 1e-9)
        assertEquals(0.05, part.seconds(DrivingState.Coasting), 1e-9)
        val acrossLine = states.totals(190.0, 10.0)
        assertSeconds(mapOf(DrivingState.Coasting to 0.5, DrivingState.Braking to 0.5), acrossLine::seconds)
        assertEquals(20.0, acrossLine.spanMeters, 1e-9)
    }

    @Test
    fun `without pedals the acceleration stands in, and a turning car is cornering, never coasting`() {
        val longitudinal = channel("longacc", "g") {
            when {
                it < 20 -> -0.6
                it < 50 -> -0.10
                else -> 0.2
            }
        }
        val states = drivingStates(session(longitudinal, lateral), trace, 200.0, null)
        assertEquals(BrakingProvenanceInferred, states.provenance)
        val expected = mapOf(
            DrivingState.Braking to 2.0,
            DrivingState.Coasting to 1.5,
            DrivingState.Cornering to 1.5,
            DrivingState.Accelerating to 5.0,
        )
        assertSeconds(expected, states.totals()::seconds)
        assertTrue(states.limitations.isEmpty())

        // No lateral accelerometer: the turning is the speed squared times the line's curvature.
        val speed = channel("speed", "km/h") { 72.0 }
        val line = TrackFeatures(
            List(201) { TrackFeatureSample(it.toDouble(), 0.0, if (it in 70..99) 0.01 else 0.0) },
            20.0,
            true,
        )
        val calculated = drivingStates(session(longitudinal, speed), trace, 200.0, line)
        assertSeconds(expected, calculated.totals()::seconds)
        assertEquals(listOf(DrivingStatesLateralCalculated), calculated.limitations)
        // Nothing to tell the turning by: the neutral stretch is all coasting, and the lap says so.
        val blind = drivingStates(session(longitudinal), trace, 200.0, null)
        assertEquals(3.0, blind.totals().seconds(DrivingState.Coasting), 1e-9)
        assertEquals(listOf(DrivingStatesNoLateral), blind.limitations)
    }

    @Test
    fun `one pedal measured is still inferred, and no channel at all gives no states`() {
        val longitudinal = channel("longacc", "g") { 0.0 }
        val oneMeasured = drivingStates(session(brake, longitudinal), trace, 200.0, null)
        assertEquals(BrakingProvenanceInferred, oneMeasured.provenance)
        val none = drivingStates(session(lateral), trace, 200.0, null)
        assertEquals(DrivingStatesNoChannels, none.unavailableReason)
        assertTrue(!none.valid)
    }

    @Test
    fun `coasting through a corner is read by its CornerId, null where the lap has too little of it`() {
        val states = drivingStates(session(brake, throttle, lateral), trace, 200.0, null)
        val lap = TimedLap(1, 0.0, 10.0, 10.0)
        val analysis = SessionAnalysis(
            LapSession(LapSessionStatus.Available, timedLaps = listOf(lap), fastestLapIndex = 0),
            segments = ApprovedSegments(
                listOf(
                    TrackSegment("s0", "Straight 1", TrackSegmentType.Straight, 0.0, 30.0),
                    TrackSegment("s1", "Corner 1", TrackSegmentType.Corner, 30.0, 70.0),
                    TrackSegment("s2", "Straight 2", TrackSegmentType.Straight, 70.0, 200.0),
                ),
                "r",
            ),
            drivingStates = listOf(states),
        )
        val corner = assertNotNull(analysis.cornerCoasting("s1", 0))
        assertEquals(1.5, corner.seconds, 1e-9)
        assertEquals(30.0, corner.meters, 1e-9)
        assertEquals(BrakingProvenanceMeasured, corner.provenance)
        assertNull(analysis.cornerCoasting("nope", 0))
        assertNull(analysis.cornerCoasting("s1", 1))
        // A lap that covers only half the corner has no coasting time there.
        val half = states.copy(intervals = states.intervals.filter { it.startMeters < 50.0 })
        assertNull(analysis.copy(drivingStates = listOf(half)).cornerCoasting("s1", 0))
    }
}
