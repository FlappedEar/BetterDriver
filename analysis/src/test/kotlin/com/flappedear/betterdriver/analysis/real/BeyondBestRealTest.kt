@file:Suppress("MagicNumber") // Arek's decelerations, distances and times.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.BeyondBest
import com.flappedear.betterdriver.analysis.line.BrakeRoomG
import com.flappedear.betterdriver.analysis.line.BrakeStepMeters
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.MinimumBrakeStepMeters
import com.flappedear.betterdriver.analysis.line.analyzeDay
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.line.beyondBest
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackRevision
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Targets beyond his own best (FBBD-312) on Arek's two real days. At Jastrząb his fastest lap
 * already matches his driven target in most corners, so the line plan has little left to ask; the
 * braking he did in his two hardest-braked corners says where the next braking points are. Each
 * day's numbers are written to build/reports/replay-drive for the pull request's checks.
 */
class BeyondBestRealTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }

    private fun sessions(files: List<String>, track: Track, label: (String) -> String): List<LineSession> =
        files.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
            val s = telemetrySessionOf(store.read(id), track)
            LineSession(s, deriveSourceLapSession(s), label(name), "s" + label(name))
        }

    private fun jastrzab() = sessions(RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack()) {
        it.substringAfter("2026-08-29-").take(4)
    }

    private fun silesia() = sessions(RealCorpus.SILESIA_DAY, RealCorpus.silesiaTrack()) {
        it.substringAfter("2026-09-27-").take(4).takeIf { t -> t.all(Char::isDigit) } ?: "first"
    }

    @Test
    fun `at Jastrząb the best lap has little left to its own target, and the braking says where next`() {
        val day = assertNotNull(analyzeDay(jastrzab()))
        val beyond = assertNotNull(day.beyondBest())
        report("jastrzab-2026-08-29", beyond)
        // Most corners have nothing left against the driven target: the case this story is for.
        assertTrue(beyond.corners.size >= day.corners.size / 2, "${beyond.corners.size} of ${day.corners.size}")
        // His car stops at about 0.9 g there (RaceChrono's own longacc-calc reaches -0.89 g on his best session).
        assertTrue(beyond.limits.brakeG in 0.75..1.0, "brake ${beyond.limits.brakeG}")
        assertEquals(2, beyond.limits.brakeCorners.size)
        assertTrue(beyond.ranked.size >= 3, "corners with room ${beyond.ranked.map { it.name }}")
        check(beyond)
    }

    @Test
    fun `at Silesia the driven targets still come first`() {
        val day = assertNotNull(analyzeDay(silesia()))
        val beyond = assertNotNull(day.beyondBest())
        report("silesia-2026-09-27", beyond)
        assertTrue(beyond.limits.brakeG in 0.6..1.0, "brake ${beyond.limits.brakeG}")
        // Half the corners still lose to his own target there; the rest he brakes in near his limit.
        assertTrue(day.corners.count { it.bestLapGainSeconds > 0.0 } >= day.corners.size / 3)
        check(beyond)
    }

    @Test
    fun `the line plan of one session reads the same way`() {
        val all = jastrzab()
        val best = all.single { it.label == "1606" }
        val line = assertNotNull(analyzeLine(analyzeSession(best.session, best.laps), best.session, all - best))
        val beyond = assertNotNull(line.beyondBest())
        assertTrue(beyond.corners.all { c -> line.corners.single { it.segmentId == c.segmentId }.gainSeconds == 0.0 })
        check(beyond)
    }

    /**
     * The limits are what he had done by then, so they are low while he learns: the morning's were
     * passed in the afternoon in braking and in grip, and every corner the morning gave a braking
     * point got faster. A target from the car's own limit would be higher; this one is safe to give.
     */
    @Test
    fun `the morning's limits were passed in the afternoon`() {
        val all = jastrzab()
        var identity: TrackRevision? = null
        val analyze = { s: LineSession ->
            analyzeSession(s.session, s.laps, identity = identity).also { identity = it.identity ?: identity }
        }
        val morningDay = assertNotNull(analyzeDay(all.take(3), analyze))
        val afternoonDay = assertNotNull(analyzeDay(all.drop(3), analyze))
        val morning = assertNotNull(morningDay.beyondBest())
        val afternoon = assertNotNull(afternoonDay.beyondBest())
        report("jastrzab-morning", morning)
        assertTrue(afternoon.limits.brakeG > morning.limits.brakeG, "${morning.limits} ${afternoon.limits}")
        assertTrue(afternoon.limits.apexG > morning.limits.apexG, "${morning.limits} ${afternoon.limits}")
        for (c in morning.ranked) {
            val later = afternoonDay.corners.firstOrNull { it.segmentId == c.segmentId } ?: continue
            assertTrue(later.target.seconds < c.target.seconds, c.name)
        }
    }

    private fun check(beyond: BeyondBest) {
        for (c in beyond.corners) {
            val room = c.brake ?: continue
            assertTrue(room.peakG <= beyond.limits.brakeG - BrakeRoomG + 1e-9, c.name)
            assertTrue(room.laterMeters in MinimumBrakeStepMeters..BrakeStepMeters, c.name)
            assertTrue(room.fullLaterMeters >= room.laterMeters, c.name)
            // A step is worth something, never most of the corner.
            assertTrue(room.seconds > 0.0 && room.seconds < 0.1 * c.target.seconds, "${c.name} ${room.seconds}")
            assertTrue(room.fullSeconds >= room.seconds, c.name)
            assertTrue(c.passes >= 2, c.name)
        }
    }

    private fun report(name: String, beyond: BeyondBest) {
        val dir = File("build/reports/replay-drive").apply { mkdirs() }
        val f = { v: Double -> String.format(Locale.ROOT, "%.2f", v) }
        val text = buildString {
            appendLine("# Beyond his best $name (FBBD-312)")
            appendLine()
            val limits = beyond.limits
            appendLine(
                "Track limits: braking ${f(limits.brakeG)} g (${limits.brakeCorners.joinToString()}), " +
                    "grip at the slowest point ${f(limits.apexG)} g (${limits.apexCorners.joinToString()}). " +
                    "Steps together: ${f(beyond.stepSeconds)} s.",
            )
            appendLine()
            appendLine(
                "| Corner | passes | entry→min km/h | brake peak g | avg g | step m | step s | full m | full s | " +
                    "apex g |",
            )
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            beyond.corners.sortedByDescending { it.brake?.seconds ?: -1.0 }.forEach { c ->
                val d = c.dynamics
                val b = c.brake
                appendLine(
                    "| ${c.name} | ${c.passes} | ${f(d.entryKmh)}→${f(d.minimumKmh)} | ${f(d.peakBrakeG)} | " +
                        "${f(d.averageBrakeG)} | ${b?.laterMeters?.let(f) ?: "-"} | ${b?.seconds?.let(f) ?: "-"} | " +
                        "${b?.fullLaterMeters?.let(f) ?: "-"} | ${b?.fullSeconds?.let(f) ?: "-"} | ${f(c.apexG)} |",
                )
            }
        }
        File(dir, "beyond-$name.md").writeText(text)
    }
}
