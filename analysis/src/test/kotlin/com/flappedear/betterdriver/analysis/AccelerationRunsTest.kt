@file:Suppress("LongParameterList")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.StandardGravity
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.runs.AccelerationRun
import com.flappedear.betterdriver.analysis.runs.AccelerationRunKind
import com.flappedear.betterdriver.analysis.runs.OneFootRolloutMeters
import com.flappedear.betterdriver.analysis.runs.RunQualityIssue
import com.flappedear.betterdriver.analysis.runs.findAccelerationRuns
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceleration runs (FBBD-154) on synthetic constant-acceleration profiles: speed is a straight
 * line in time between samples, so every time, distance and trap speed has an exact answer.
 */
class AccelerationRunsTest {
    private val eps = 1e-6

    /** Standing at 0 until [launch], then [mps2] forward until [top] km/h, held there. */
    private fun launchKmh(launch: Double, mps2: Double, top: Double = 260.0): (Double) -> Double =
        { t -> min(top, max(0.0, (t - launch) * mps2 * 3.6)) }

    /** Speed [kmh] at [hz] from 0 to [end] seconds; samples inside a hole in [holes] are missing. */
    private fun session(
        hz: Double,
        end: Double,
        holes: List<ClosedFloatingPointRange<Double>> = emptyList(),
        extra: Map<String, TelemetryChannel> = emptyMap(),
        aliases: Map<String, String> = emptyMap(),
        kmh: (Double) -> Double,
    ): TelemetrySession {
        val times = (0..(end * hz).roundToInt()).map { it / hz }.filter { t -> holes.none { t in it } }
        val speed = TelemetryChannel(
            "speed",
            times.toDoubleArray(),
            times.map(kmh).toDoubleArray(),
            "km/h",
        )
        return TelemetrySession(mapOf("speed" to speed) + extra, mapOf(Aliases.SPEED to "speed") + aliases)
    }

    private fun List<AccelerationRun>.of(kind: AccelerationRunKind) = filter { it.kind == kind }

    private fun distanceTime(meters: Double, mps2: Double) = sqrt(2 * meters / mps2)

    @Test
    fun `constant acceleration from a standstill gives exact times`() {
        val a = 5.0
        val runs = findAccelerationRuns(session(25.0, 20.0, kmh = launchKmh(2.0, a)))
        val zeroTo100 = runs.of(AccelerationRunKind.ZeroTo100Kmh).single()
        assertEquals(2.0, zeroTo100.startSeconds, eps)
        assertEquals(100 / 3.6 / a, zeroTo100.timeSeconds, eps)
        assertNull(zeroTo100.trapSpeedKmh)
        assertEquals(60 * 0.44704 / a, runs.of(AccelerationRunKind.ZeroTo60Mph).single().timeSeconds, eps)
        val band = runs.of(AccelerationRunKind.From100To200Kmh).single()
        assertEquals(100 / 3.6 / a, band.timeSeconds, eps)
        assertEquals(2.0 + 100 / 3.6 / a, band.startSeconds, eps)

        val eighth = runs.of(AccelerationRunKind.EighthMile).single()
        val eighthTime = distanceTime(201.168, a)
        assertEquals(eighthTime, eighth.timeSeconds, eps)
        assertEquals(eighthTime * a * 3.6, assertNotNull(eighth.trapSpeedKmh), eps)
        val quarter = runs.of(AccelerationRunKind.QuarterMile).single()
        assertEquals(distanceTime(402.336, a), quarter.timeSeconds, eps)
        assertEquals(distanceTime(402.336, a) * a * 3.6, assertNotNull(quarter.trapSpeedKmh), eps)

        // No acceleration channel: the peak comes from the change in speed.
        assertEquals(a / StandardGravity, assertNotNull(zeroTo100.peakG), eps)
        assertTrue(runs.none { it.lowQuality })
    }

    @Test
    fun `a launch between samples is interpolated back to zero speed`() {
        val a = 4.0
        val launch = 3.013
        val runs = findAccelerationRuns(session(25.0, 12.0, kmh = launchKmh(launch, a)))
        val run = runs.of(AccelerationRunKind.ZeroTo100Kmh).single()
        assertEquals(launch, run.startSeconds, eps)
        assertEquals(100 / 3.6 / a, run.timeSeconds, eps)
    }

    @Test
    fun `a one foot rollout starts the timer once the car has moved a foot`() {
        val a = 5.0
        val runs = findAccelerationRuns(session(25.0, 20.0, kmh = launchKmh(2.0, a)), OneFootRolloutMeters)
        val rollout = distanceTime(OneFootRolloutMeters, a)
        val quarter = runs.of(AccelerationRunKind.QuarterMile).single()
        assertEquals(2.0 + rollout, quarter.startSeconds, eps)
        assertEquals(distanceTime(402.336 + OneFootRolloutMeters, a) - rollout, quarter.timeSeconds, eps)
        val zeroTo100 = runs.of(AccelerationRunKind.ZeroTo100Kmh).single()
        assertEquals(100 / 3.6 / a - rollout, zeroTo100.timeSeconds, eps)
    }

    @Test
    fun `no standing run without a second at a standstill`() {
        val runs = findAccelerationRuns(session(25.0, 20.0, kmh = launchKmh(0.6, 5.0)))
        assertTrue(runs.none { it.kind.standing })
        assertEquals(1, runs.of(AccelerationRunKind.From100To200Kmh).size)
    }

    @Test
    fun `a flicker of speed while standing does not start the run`() {
        val profile = launchKmh(4.0, 5.0)
        val runs = findAccelerationRuns(session(25.0, 20.0) { t -> if (t == 1.6) 1.4 else profile(t) })
        assertEquals(4.0, runs.of(AccelerationRunKind.ZeroTo100Kmh).single().startSeconds, eps)
    }

    @Test
    fun `braking before the target ends the run without a time`() {
        // 0 to 90 km/h, 10 km/h off, then on to 250 km/h.
        val a = 5.0
        val brake = 2.0 + 25 / a
        val runs = findAccelerationRuns(
            session(25.0, 30.0) { t ->
                when {
                    t < 2.0 -> 0.0
                    t < brake -> (t - 2.0) * a * 3.6
                    t < brake + 1.0 -> 90.0 - (t - brake) * 10.0
                    else -> min(250.0, 80.0 + (t - brake - 1.0) * a * 3.6)
                }
            },
        )
        assertTrue(runs.none { it.kind.standing })
        // The band run starts later, in the second acceleration, so it still counts.
        assertEquals(100 / 3.6 / a, runs.of(AccelerationRunKind.From100To200Kmh).single().timeSeconds, eps)
    }

    @Test
    fun `a lift of under 3 kmh keeps the run going`() {
        val a = 5.0
        val lift = 2.0 + 20 / a
        val runs = findAccelerationRuns(
            session(25.0, 20.0) { t ->
                when {
                    t < 2.0 -> 0.0
                    t < lift -> (t - 2.0) * a * 3.6
                    t < lift + 0.5 -> 72.0 - (t - lift) * 4.0
                    else -> 70.0 + (t - lift - 0.5) * a * 3.6
                }
            },
        )
        val expected = lift + 0.5 + 30 / 3.6 / a - 2.0
        assertEquals(expected, runs.of(AccelerationRunKind.ZeroTo100Kmh).single().timeSeconds, eps)
    }

    @Test
    fun `braking between 100 and 200 kmh ends the band run`() {
        val a = 5.0
        val runs = findAccelerationRuns(
            session(25.0, 40.0) { t ->
                when {
                    t < 2.0 -> 80.0
                    t < 2.0 + 70 / 3.6 / a -> 80.0 + (t - 2.0) * a * 3.6
                    t < 2.0 + 70 / 3.6 / a + 1.0 -> 150.0 - (t - 2.0 - 70 / 3.6 / a) * 20.0
                    else -> min(205.0, 130.0 + (t - 3.0 - 70 / 3.6 / a) * a * 3.6)
                }
            },
        )
        assertTrue(runs.of(AccelerationRunKind.From100To200Kmh).isEmpty())
    }

    @Test
    fun `a rolling 100 to 200 run counts from its crossing of 100`() {
        val a = 4.0
        val runs = findAccelerationRuns(
            session(25.0, 40.0) { t -> if (t < 1.0) 80.0 else min(210.0, 80.0 + (t - 1.0) * a * 3.6) },
        )
        val run = runs.single()
        assertEquals(AccelerationRunKind.From100To200Kmh, run.kind)
        assertEquals(1.0 + 20 / 3.6 / a, run.startSeconds, eps)
        assertEquals(100 / 3.6 / a, run.timeSeconds, eps)
    }

    @Test
    fun `a short gap inside a run marks it low quality`() {
        val a = 5.0
        val runs = findAccelerationRuns(session(25.0, 20.0, holes = listOf(4.01..4.49), kmh = launchKmh(2.0, a)))
        val run = runs.of(AccelerationRunKind.ZeroTo100Kmh).single()
        assertEquals(100 / 3.6 / a, run.timeSeconds, eps)
        assertEquals(setOf(RunQualityIssue.Gap), run.quality)
        // A run that is over before the gap is not affected.
        val clean = findAccelerationRuns(session(25.0, 20.0, holes = listOf(9.01..9.49), kmh = launchKmh(2.0, a)))
        assertTrue(clean.of(AccelerationRunKind.ZeroTo60Mph).single().quality.isEmpty())
    }

    @Test
    fun `a loss of signal ends the run`() {
        val runs = findAccelerationRuns(session(25.0, 20.0, holes = listOf(4.01..5.6), kmh = launchKmh(2.0, 5.0)))
        assertTrue(runs.none { it.kind.standing })
    }

    @Test
    fun `a missing speed sample ends the run like a loss of signal`() {
        val profile = launchKmh(2.0, 5.0)
        val runs = findAccelerationRuns(session(25.0, 20.0) { t -> if (t == 4.0) Double.NaN else profile(t) })
        assertTrue(runs.none { it.kind.standing })
        // The band run, after the missing sample, still counts.
        assertEquals(100 / 3.6 / 5.0, runs.of(AccelerationRunKind.From100To200Kmh).single().timeSeconds, eps)
    }

    @Test
    fun `GNSS under 10 Hz marks runs low quality but keeps exact times`() {
        val a = 5.0
        val runs = findAccelerationRuns(session(5.0, 20.0, kmh = launchKmh(2.0, a)))
        val run = runs.of(AccelerationRunKind.ZeroTo100Kmh).single()
        assertEquals(100 / 3.6 / a, run.timeSeconds, eps)
        assertEquals(setOf(RunQualityIssue.LowRate), run.quality)
    }

    @Test
    fun `poor horizontal accuracy marks runs low quality`() {
        val times = (0..200).map { it / 10.0 }
        val accuracy = TelemetryChannel(
            "hacc",
            times.toDoubleArray(),
            times.map { if (it in 4.0..4.2) 2.6 else 0.5 }.toDoubleArray(),
            "m",
        )
        val runs = findAccelerationRuns(
            session(
                25.0,
                20.0,
                extra = mapOf("hacc" to accuracy),
                aliases = mapOf(Aliases.ACCURACY to "hacc"),
                kmh = launchKmh(2.0, 5.0),
            ),
        )
        assertEquals(setOf(RunQualityIssue.PoorAccuracy), runs.of(AccelerationRunKind.ZeroTo100Kmh).single().quality)
        assertTrue(runs.of(AccelerationRunKind.From100To200Kmh).single().quality.isEmpty())
    }

    @Test
    fun `peak G comes from the acceleration channel when there is one`() {
        val times = (0..2000).map { it / 100.0 }
        val forward = TelemetryChannel(
            "accel_forward",
            times.toDoubleArray(),
            times.map { if (it == 3.0) 0.8 else 0.5 }.toDoubleArray(),
            "g",
        )
        val runs = findAccelerationRuns(
            session(
                25.0,
                20.0,
                extra = mapOf("accel_forward" to forward),
                aliases = mapOf(Aliases.LONGITUDINAL_ACCELERATION to "accel_forward"),
                kmh = launchKmh(2.0, 5.0),
            ),
        )
        assertEquals(0.8, assertNotNull(runs.of(AccelerationRunKind.ZeroTo100Kmh).single().peakG), eps)
        assertEquals(0.5, assertNotNull(runs.of(AccelerationRunKind.From100To200Kmh).single().peakG), eps)
    }

    @Test
    fun `runs are listed best first within each kind`() {
        val runs = findAccelerationRuns(
            session(25.0, 60.0) { t ->
                when {
                    t < 2.0 -> 0.0
                    t < 12.0 -> min(120.0, (t - 2.0) * 4.0 * 3.6)
                    t < 30.0 -> max(0.0, 120.0 - (t - 12.0) * 20.0)
                    else -> min(120.0, (t - 30.0) * 6.0 * 3.6)
                }
            },
        )
        val times = runs.of(AccelerationRunKind.ZeroTo100Kmh).map { it.timeSeconds }
        assertEquals(2, times.size)
        assertEquals(100 / 3.6 / 6.0, times[0], eps)
        assertEquals(100 / 3.6 / 4.0, times[1], eps)
        val order = runs.map { it.kind.ordinal }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `a quicker run with a quality issue still comes first`() {
        val times = (0..600).map { it / 10.0 }
        // Poor accuracy during the second, quicker launch only.
        val accuracy = TelemetryChannel(
            "hacc",
            times.toDoubleArray(),
            times.map { if (it in 31.0..32.0) 3.0 else 0.5 }.toDoubleArray(),
            "m",
        )
        val runs = findAccelerationRuns(
            session(25.0, 60.0, extra = mapOf("hacc" to accuracy), aliases = mapOf(Aliases.ACCURACY to "hacc")) { t ->
                when {
                    t < 2.0 -> 0.0
                    t < 12.0 -> min(120.0, (t - 2.0) * 4.0 * 3.6)
                    t < 30.0 -> max(0.0, 120.0 - (t - 12.0) * 20.0)
                    else -> min(120.0, (t - 30.0) * 6.0 * 3.6)
                }
            },
        )
        val zeroTo100 = runs.of(AccelerationRunKind.ZeroTo100Kmh)
        assertEquals(2, zeroTo100.size)
        assertEquals(100 / 3.6 / 6.0, zeroTo100[0].timeSeconds, eps)
        assertEquals(setOf(RunQualityIssue.PoorAccuracy), zeroTo100[0].quality)
        assertTrue(zeroTo100[1].quality.isEmpty())
    }

    @Test
    fun `no speed channel means no runs`() {
        assertTrue(findAccelerationRuns(TelemetrySession(emptyMap(), emptyMap())).isEmpty())
    }
}
