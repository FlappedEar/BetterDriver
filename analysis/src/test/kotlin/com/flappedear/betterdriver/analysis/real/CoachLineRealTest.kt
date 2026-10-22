@file:Suppress("MagicNumber") // Arek's laps, corners and the receiver's repeatability.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LineAnalysis
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.coach.CoachReference
import com.flappedear.betterdriver.coach.LineToleranceMeters
import com.flappedear.betterdriver.coach.PickupToleranceMeters
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.TracedLap
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.coachFixes
import com.flappedear.betterdriver.coach.tracedLaps
import com.flappedear.betterdriver.coach.tighterBy
import com.flappedear.betterdriver.coach.unusable
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The live coach's line and throttle measurement (FBBD-307) on Arek's six Jastrząb sessions of
 * 29 Aug 2026, at 10 Hz as RaceChrono recorded them: every pass gets its line at the apex, the
 * coach has a corner where the analysis finds the most time (the last complex, taken on a lift),
 * and on the same two laps the coach's line and throttle point (from the OBD pedal) differences
 * match the line analysis (FBBD-306).
 */
class CoachLineRealTest {
    @TempDir
    lateinit var tmp: File

    private val track = RealCorpus.jastrzabTrack()

    private class Day(val reference: CoachReference, val laps: Map<String, List<TracedLap>>, val line: LineAnalysis)

    private fun day(): Day {
        val store = SessionStore(File(tmp, "sessions"))
        val data = RealCorpus.JASTRZAB_DAY.associate { name ->
            name.substringAfter("2026-08-29-").take(4) to
                store.read(RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id)
        }
        val laps = data.mapValues { (_, d) -> tracedLaps(track, d.coachFixes()) }
        val reference = assertNotNull(buildReference(track, laps.map { (k, l) -> SessionLaps(k, l) }))
        val sessions = data.mapValues { (_, d) -> telemetrySessionOf(d, track) }
        val session = sessions.getValue("1606")
        val others = sessions.filter { it.key != "1606" }
            .map { (k, s) -> LineSession(s, deriveSourceLapSession(s), k) }
        val line = assertNotNull(analyzeLine(analyzeSession(session), session, others))
        return Day(reference, laps, line)
    }

    @Test
    fun `the coach measures the line and the throttle and agrees with the line analysis on the same laps`() {
        val day = day()
        val map = day.reference.map()
        val all = day.laps.values.flatten()
        val passes = all.filter { it.lap.countsForBest }
            .flatMap { map.passes(it.trace, it.lap.number) }
            .filterNot { it.unusable() }
        assertTrue(passes.size >= 150, "passes ${passes.size}")
        assertTrue(passes.all { it.apexOffsetMeters != null }, "every pass has its line")
        // On the road: corner 1 is a wide hairpin where Arek's lines differ by up to 19 m.
        assertTrue(passes.all { abs(it.apexOffsetMeters!!) < 25.0 }, "offsets ${passes.map { it.apexOffsetMeters }}")

        // The last complex, where the analysis finds the most time, is taken on a lift: the coach has
        // a corner there now (before FBBD-307 it had none from 1768 m to the line).
        val complex = day.line.corners.maxBy { it.gainSeconds }
        assertTrue(complex.startMeters > 1800.0, "most time at ${complex.name} from ${complex.startMeters}")
        val lift = assertNotNull(map.corners.firstOrNull { it.apexAt in complex.startMeters..complex.endMeters })
        assertTrue(!lift.braked, "corner at ${lift.apexAt} is a lift corner")

        // The same two laps the analysis compares in each corner, measured by the coach's own meter.
        fun lap(seconds: Double) = all.single { abs(it.lap.timeMicros / 1e6 - seconds) < 0.06 }
        val compared = day.line.corners.mapNotNull { line ->
            val tighter = line.advice.tighterMeters ?: return@mapNotNull null
            val c = map.corners.firstOrNull { it.apexAt in line.startMeters..line.endMeters } ?: return@mapNotNull null
            val ref = lap(line.reference.lapSeconds)
            val target = lap(line.target.lapSeconds)
            val before = map.passes(ref.trace, ref.lap.number).single { it.corner == c.number }
            val after = map.passes(target.trace, target.lap.number).single { it.corner == c.number }
            Triple(line.name, tighter, assertNotNull(tighterBy(before, after, c.turn)))
        }
        // With the second-fastest target (FBBD-177, #311) the analysis asks for a line change in one corner here.
        assertTrue(compared.isNotEmpty(), "compared $compared")
        // Within what the receiver repeats: the coach would ask for the same line the analysis shows.
        assertTrue(compared.all { (_, a, c) -> abs(a - c) <= LineToleranceMeters }, "analysis vs coach $compared")

        // Where the pedal goes back on, read from OBD on both: the same difference on the same laps.
        val pickups = day.line.corners.mapNotNull { line ->
            val c = map.corners.firstOrNull { it.apexAt in line.startMeters..line.endMeters } ?: return@mapNotNull null
            val analysis = line.reference.pickupMeters?.let { r -> line.target.pickupMeters?.minus(r) }
                ?: return@mapNotNull null
            val ref = lap(line.reference.lapSeconds)
            val target = lap(line.target.lapSeconds)
            val before = map.passes(ref.trace, ref.lap.number).single { it.corner == c.number }
            val after = map.passes(target.trace, target.lap.number).single { it.corner == c.number }
            Triple(line.name, analysis, assertNotNull(after.pickupAt) - assertNotNull(before.pickupAt))
        }
        assertTrue(pickups.size >= 6, "compared $pickups")
        assertTrue(pickups.all { (_, a, c) -> abs(a - c) <= PickupToleranceMeters }, "analysis vs coach $pickups")
        // The last complex: earlier on the throttle by more than the receiver repeats (about 5 m against
        // the second-fastest target), which the speed alone did not show.
        val last = pickups.single { it.first == complex.name }
        assertTrue(last.third < -PickupToleranceMeters, "last complex $last")
    }
}
