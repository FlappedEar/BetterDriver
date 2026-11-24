@file:Suppress("MagicNumber") // Arek's sessions and what they show.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.learning.BrakeToThrottleGaps
import com.flappedear.betterdriver.analysis.learning.GapComparisonTooFewCorners
import com.flappedear.betterdriver.analysis.learning.brakeToThrottleGaps
import com.flappedear.betterdriver.analysis.learning.logLines
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The learning coach's brake-to-throttle gap (FBBD-417) on Arek's nine FR-S sessions (Jastrząb
 * 29 Aug, Silesia Ring 27 Sep, RaceChrono with the car's brake and accelerator over OBD at 10 Hz).
 * At Jastrząb the same corner, Corner 9 (the brake let off about 1.6 to 1.8 km into the lap, before
 * the last complex where the line analysis finds most time, LineRealTest), comes out in four of
 * the five sessions with enough laps: 1.1 to 2.1 s off both pedals on nearly every lap, against
 * 0.4 to 0.9 s at the other corners. The thresholds are unvalidated heuristics (FBBD-420); this
 * fixes what they find today so a change to them is seen.
 */
class BrakeToThrottleGapRealTest {
    @TempDir
    lateinit var tmp: File

    private fun gaps(files: List<String>, track: Track): List<Pair<String, BrakeToThrottleGaps>> {
        val store = SessionStore(File(tmp, "sessions"))
        return files.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
            val session = telemetrySessionOf(store.read(id), track)
            name.substringAfter("-2026-").substringBefore("-racechrono") to brakeToThrottleGaps(analyzeSession(session))
        }
    }

    @Test
    fun `the gap is measured on every session, each brake counted once, and few corners are flagged`() {
        val all = gaps(RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack()) +
            gaps(RealCorpus.SILESIA_DAY, RealCorpus.silesiaTrack())
        assertEquals(9, all.size)
        for ((name, g) in all) {
            println("$name: " + g.logLines().joinToString(" | "))
            assertTrue(g.available, "$name ${g.unavailableReason}")
            assertTrue(g.observations.size <= 3, "$name ${g.observations.map { it.name }}")
            g.observations.forEach { assertTrue(it.text.startsWith("Observed in "), it.text) }
            // A brake let off on a lap belongs to one corner only.
            val releases = g.corners.flatMap { c -> c.passes.map { it.lapNumber to it.releaseTime } }
            assertEquals(releases.size, releases.toSet().size, name)
            // Every gap is a real time off both pedals, under the length of a straight.
            g.corners.flatMap { it.passes }.forEach { assertTrue(it.gapSeconds in 0.0..10.0, "$name ${it.gapSeconds}") }
        }
        // 17:20 had three timed laps: no corner has enough passes, nothing is claimed.
        val late = all.single { it.first == "08-29-1720" }.second
        assertEquals(GapComparisonTooFewCorners, late.comparisonReason)
        assertTrue(late.observations.isEmpty())
    }

    @Test
    fun `at Jastrzab the same corner before the last complex is flagged in four sessions`() {
        val day = gaps(RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack())
        val flagged = day.filter { (_, g) ->
            g.observations.any { o ->
                val corner = g.corners.single { it.segmentId == o.segmentId }
                o.name == "Corner 9" && corner.passes.all { it.releaseMeters in 1550.0..1800.0 }
            }
        }
        assertEquals(listOf("08-29-1000", "08-29-1129", "08-29-1303", "08-29-1606"), flagged.map { it.first })
        for ((name, g) in flagged) {
            val o = g.observations.single { it.name == "Corner 9" }
            assertTrue(o.medianSeconds in 1.0..2.5, "$name ${o.medianSeconds}")
            val most = o.passesWithGap >= 3 && o.passesWithGap * 4 >= o.usablePasses * 3
            assertTrue(most, "$name ${o.passesWithGap}/${o.usablePasses}")
        }
    }
}
