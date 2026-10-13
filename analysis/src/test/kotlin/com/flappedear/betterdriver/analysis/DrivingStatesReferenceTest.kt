@file:Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceInferred
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceMeasured
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.driving.DrivingState
import com.flappedear.betterdriver.analysis.driving.cornerCoasting
import com.flappedear.betterdriver.analysis.driving.cornerCoastingConsistency
import com.flappedear.betterdriver.analysis.driving.lapCoasting
import com.flappedear.betterdriver.analysis.progress.computeTrackFeatures
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Driving states and coasting (FBBD-175) against a reference on the parity corpus shared with
 * FlappedEar Telemetry (corners_measured.vbo, with brake and throttle, and corners_inferred.vbo,
 * the same driving with only the longitudinal acceleration).
 *
 * The Telemetry repository could not be read from where this port was made, so the reference is
 * Telemetry's driving-state algorithm written out again here, as plainly as it can be and apart from
 * the port: every fix of a lap classified from its raw channel samples read by straight-line
 * interpolation (brake ≥ 10 % braking; throttle ≥ 90 % full, ≥ 20 % accelerating, ≤ 5 % off, a part
 * throttle cornering at ≥ 0.3 g of turning; without pedals, < −0.15 g braking, up to 0.05 g at
 * ≥ 0.3 g of turning cornering, < −0.05 g coasting, else accelerating), each stretch to the next fix taking the
 * fix's state. Every total must agree within 1 % or 0.01 s, whichever is larger.
 */
class DrivingStatesReferenceTest {
    private class Reference(val session: TelemetrySession, val analysis: SessionAnalysis) {
        private val line = computeTrackFeatures(analysis.axis!!, 20.0)

        private fun raw(alias: String, time: Double): Double? {
            val c = session.channel(alias) ?: return null
            val t = c.timestamps
            if (time < t.first() || time > t.last()) return null
            var i = 0
            while (i < t.size - 1 && t[i + 1] < time) i++
            if (t[i] == time) return c.values[i].takeIf { it.isFinite() }
            // A loss of signal (more than three usual intervals) is never bridged.
            val usual = (1 until t.size).map { t[it] - t[it - 1] }.sorted()[(t.size - 1) / 2]
            if (t[i + 1] - t[i] > 3 * usual) return null
            val r = (time - t[i]) / (t[i + 1] - t[i])
            return (c.values[i] + (c.values[i + 1] - c.values[i]) * r).takeIf { it.isFinite() }
        }

        private fun turning(time: Double, meters: Double): Double? {
            val kmh = raw(Aliases.SPEED, time) ?: return null
            val nearest = line.samples.minBy { abs(it.progressMeters - meters) }
            val v = kmh / 3.6
            return v * v * abs(nearest.curvaturePerMeter) / 9.80665
        }

        fun state(time: Double, meters: Double): DrivingState? {
            // With both pedals recorded they alone tell the state; where either has no value, nothing does.
            if (session.channel(Aliases.BRAKE) != null && session.channel(Aliases.THROTTLE) != null) {
                val brake = raw(Aliases.BRAKE, time) ?: return null
                val throttle = raw(Aliases.THROTTLE, time) ?: return null
                return when {
                    brake >= 10.0 -> DrivingState.Braking
                    throttle >= 90.0 -> DrivingState.FullThrottle
                    throttle >= 20.0 -> DrivingState.Accelerating
                    throttle <= 5.0 -> DrivingState.Coasting
                    (turning(time, meters) ?: 0.0) >= 0.3 -> DrivingState.Cornering
                    else -> DrivingState.Accelerating
                }
            }
            val g = raw(Aliases.LONGITUDINAL_ACCELERATION, time) ?: return null
            return when {
                g < -0.15 -> DrivingState.Braking
                g <= 0.05 && (turning(time, meters) ?: 0.0) >= 0.3 -> DrivingState.Cornering
                g < -0.05 -> DrivingState.Coasting
                else -> DrivingState.Accelerating
            }
        }

        /** Seconds in each state of lap [index] between [from] and [to] metres. */
        fun seconds(index: Int, from: Double = 0.0, to: Double = Double.MAX_VALUE): Map<DrivingState, Double> {
            val totals = mutableMapOf<DrivingState, Double>()
            for (segment in analysis.traces[index]) {
                val s = segment.samples
                for (k in 0 until s.size - 1) {
                    val span = s[k + 1].progressMeters - s[k].progressMeters
                    val inside = min(to, s[k + 1].progressMeters) - max(from, s[k].progressMeters)
                    if (span <= 0.0 || inside <= 0.0) continue
                    val state = state(s[k].telemetryTime, s[k].progressMeters) ?: continue
                    val dt = (s[k + 1].telemetryTime - s[k].telemetryTime) * inside / span
                    totals[state] = (totals[state] ?: 0.0) + dt
                }
            }
            return totals
        }
    }

    private fun close(expected: Double, actual: Double, what: String) {
        val tolerance = max(0.01, 0.01 * abs(expected))
        assertTrue(abs(expected - actual) <= tolerance, "$what: reference $expected, port $actual")
    }

    private fun check(file: String, provenance: String) {
        val session = VboFixture.read(file)
        val analysis = analyzeSession(session)
        val reference = Reference(session, analysis)
        val laps = analysis.laps.timedLaps
        assertTrue(laps.size >= 2, "$file: ${laps.size} laps")
        assertEquals(laps.size, analysis.drivingStates.size, file)
        val corners = analysis.segments!!.segments.filter { it.type == TrackSegmentType.Corner }
        assertTrue(corners.isNotEmpty(), file)
        var coasted = 0.0
        for (i in laps.indices) {
            val states = analysis.drivingStates[i]
            assertEquals(provenance, states.provenance, "$file lap $i")
            val expected = reference.seconds(i)
            val totals = states.totals()
            for (s in DrivingState.entries) close(expected[s] ?: 0.0, totals.seconds(s), "$file lap $i ${s.name}")
            val lap = assertNotNull(analysis.lapCoasting(i), "$file lap $i")
            close(expected[DrivingState.Coasting] ?: 0.0, lap.seconds, "$file lap $i coasting")
            coasted += lap.seconds
            for (c in corners) {
                val here = analysis.cornerCoasting(c.id, i) ?: continue
                val want = reference.seconds(i, c.startProgressMeters, c.endProgressMeters)
                close(want[DrivingState.Coasting] ?: 0.0, here.seconds, "$file lap $i ${c.name}")
                assertEquals(provenance, here.provenance)
            }
        }
        // Every lap's states add up to its covered time, and the laps do coast somewhere.
        assertTrue(coasted > 0.0, "$file never coasts")
        for (c in corners) {
            val eligible = laps.indices.filter { laps[it].referenceEligible }
            val times = eligible.mapNotNull { analysis.cornerCoasting(c.id, it)?.seconds }
            val summary = analysis.cornerCoastingConsistency(c.id)
            assertEquals(summarizeConsistency(times), summary, "$file ${c.name}")
        }
    }

    @Test
    fun `measured pedals match the reference on the shared corpus`() =
        check("corners_measured.vbo", BrakingProvenanceMeasured)

    @Test
    fun `inferred states match the reference on the shared corpus`() =
        check("corners_inferred.vbo", BrakingProvenanceInferred)

    /**
     * The two files are the same driving: read from the acceleration alone, the first lap's braking,
     * coasting and cornering come out close to what its pedals say (the measured file's partly-open
     * and full throttle are one "accelerating" without a throttle channel).
     */
    @Test
    fun `inferred states agree with the pedals on the same driving`() {
        val measured = analyzeSession(VboFixture.read("corners_measured.vbo")).drivingStates.first().totals()
        val inferred = analyzeSession(VboFixture.read("corners_inferred.vbo")).drivingStates.first().totals()
        val tolerances = mapOf(DrivingState.Braking to 0.5, DrivingState.Coasting to 0.5, DrivingState.Cornering to 1.0)
        for ((state, tolerance) in tolerances) {
            val m = measured.seconds(state)
            val i = inferred.seconds(state)
            assertTrue(abs(m - i) <= tolerance, "${state.name}: pedals $m s, acceleration $i s")
        }
        val driven = measured.seconds(DrivingState.Accelerating) + measured.seconds(DrivingState.FullThrottle)
        assertTrue(abs(driven - inferred.seconds(DrivingState.Accelerating)) <= 1.0)
    }

    @Test
    fun `GPS gaps are never bridged, and what is left matches the reference`() =
        check("corners_gaps.vbo", BrakingProvenanceMeasured)
}
