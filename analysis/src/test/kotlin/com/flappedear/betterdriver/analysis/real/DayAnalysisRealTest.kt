@file:Suppress("MagicNumber") // Arek's lap times and where on the lap his time is.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.DayAnalysis
import com.flappedear.betterdriver.analysis.line.DayLoss
import com.flappedear.betterdriver.analysis.line.DayPlanStore
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.LineThresholds
import com.flappedear.betterdriver.analysis.line.ProofVerdict
import com.flappedear.betterdriver.analysis.line.PushLapShare
import com.flappedear.betterdriver.analysis.line.analyzeDay
import com.flappedear.betterdriver.analysis.line.dayPlan
import com.flappedear.betterdriver.analysis.line.dayProof
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The track day (FBBD-315) on Arek's two real days: six sessions at Jastrząb on 29 Aug 2026 and
 * three at Silesia Ring on 27 Sep 2026. Each day is read on its fastest lap's corners; what the
 * day's typical push lap loses there is where the next day can win time on every lap. Each day's
 * numbers are written to build/reports/replay-drive for the pull request's checks.
 */
class DayAnalysisRealTest {
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
        // The first file has no start time in its name: it is the day's first session.
        it.substringAfter("2026-09-27-").take(4).takeIf { t -> t.all(Char::isDigit) } ?: "first"
    }

    @Test
    fun `Jastrząb's day finds more time on a typical lap than on the best one, and names what to do`() {
        val day = assertNotNull(analyzeDay(jastrzab()))
        report("jastrzab-2026-08-29", day)
        assertEquals(6, day.sessions.size)
        assertEquals("1606", day.referenceSessionLabel)
        assertEquals(109.91, day.referenceLapSeconds, 0.01)
        checkDay(day)
        // The best lap leaves about 0.4 s (LineRealTest); a typical push lap loses far more, seconds.
        assertTrue(day.bestLapGainSeconds in 0.3..0.8, "best lap ${day.bestLapGainSeconds}")
        assertTrue(day.perLapGainSeconds >= 1.0, "per lap ${day.perLapGainSeconds}")
        // The typical lap is made of the typical passes: their losses add up to near its gap to the perfect lap.
        val gap = day.typicalLapSeconds - day.perfectLapSeconds
        val perLap = day.perLapGainSeconds
        assertTrue(kotlin.math.abs(gap - perLap) < 0.6 * gap, "gap $gap per lap $perLap")
        assertEquals(3, day.focus().size)
    }

    @Test
    fun `Silesia's three sessions make a day too`() {
        val day = assertNotNull(analyzeDay(silesia()))
        report("silesia-2026-09-27", day)
        assertEquals(3, day.sessions.size)
        checkDay(day)
        assertTrue(day.focus().isNotEmpty())
    }

    @Test
    fun `one session is no day`() {
        assertNull(analyzeDay(jastrzab().take(1)))
    }

    @Test
    fun `the afternoon proves what the morning's focus came to, corner by corner`() {
        val all = jastrzab()
        var identity: TrackRevision? = null
        val analyze = { s: LineSession ->
            analyzeSession(s.session, s.laps, identity = identity).also { identity = it.identity ?: identity }
        }
        val morning = assertNotNull(analyzeDay(all.take(3), analyze))
        val afternoon = assertNotNull(analyzeDay(all.drop(3), analyze))
        assertTrue(morning.identityRevision != null && morning.identityRevision == afternoon.identityRevision)
        // The morning is kept as its day's plan, and the afternoon measured against it.
        val plan = dayPlan(morning, "jastrzab", "2026-08-28", "s1303", 1L)
        val store = DayPlanStore(File(tmp, "days").path)
        assertTrue(store.save(plan))
        assertTrue(!store.save(plan), "the same plan is not written again")
        assertEquals(plan, store.before("jastrzab", "2026-08-29"))
        assertNull(store.before("jastrzab", "2026-08-28"))
        // The coach works on the plan's focus: the day's corners with most per lap, with their targets.
        assertEquals(morning.focus().map { it.segmentId }, plan.plan.focus().map { it.cornerId })
        plan.plan.focus().forEach { assertTrue(it.target.line.isNotEmpty(), it.name) }
        val proof = assertNotNull(dayProof(plan, afternoon))
        assertEquals(morning.focus().size, proof.corners.size)
        proof.corners.forEach {
            assertTrue(it.verdict != ProofVerdict.NotComparable, it.name)
            assertTrue(afternoon.corners.any { c -> c.segmentId == it.cornerId && c.name == it.name }, it.name)
            assertNotNull(it.afterSeconds, it.name)
        }
        report("jastrzab-morning-afternoon", afternoon, proof.corners.joinToString("\n") {
            String.format(
                Locale.ROOT, "| %s | %.2f | %.2f | %.2f | %s |", it.name, it.beforeSeconds,
                it.afterSeconds, it.wonBackSeconds, it.verdict,
            )
        })
        // Without one corner identity nothing is compared.
        val other = afternoon.copy(identityRevision = null)
        assertTrue(dayProof(plan, other)!!.corners.all { it.verdict == ProofVerdict.NotComparable })
    }

    private fun checkDay(day: DayAnalysis) {
        val noise = LineThresholds().gainSeconds
        assertTrue(day.typicalLapSeconds in day.referenceLapSeconds..day.referenceLapSeconds * PushLapShare)
        day.sessions.forEach {
            assertTrue(it.pushLaps in 1..it.laps && it.paceLaps <= it.laps, it.label)
            assertTrue(it.typicalLapSeconds!! <= it.bestLapSeconds!! * PushLapShare + 1e-9, it.label)
        }
        for (c in day.corners) {
            assertTrue(c.perLapGainSeconds == 0.0 || c.perLapGainSeconds >= noise, c.name)
            assertTrue(c.bestLapGainSeconds == 0.0 || c.bestLapGainSeconds >= noise, c.name)
            assertTrue(c.target.seconds <= c.bestLap.seconds + 1e-9, c.name)
            assertEquals(day.sessions.map { it.label }, c.sessions.map { it.label })
            c.sessions.forEach { s ->
                if (s.bestSeconds != null) assertTrue(s.bestSeconds!! <= s.typicalSeconds!! + 1e-9, c.name)
            }
            if (c.loss == DayLoss.None) assertTrue(!c.advice.any || c.perLapGainSeconds == 0.0, c.name)
        }
        day.focus().forEach { assertTrue(it.perLapGainSeconds > 0.0 && it.advice.any, it.name) }
        assertEquals(day.ranked.first().perLapGainSeconds, day.corners.maxOf { it.perLapGainSeconds })
    }

    private fun report(name: String, day: DayAnalysis, proof: String? = null) {
        // With the loop reports, on the pull request's check summary.
        val dir = File("build/reports/replay-drive").apply { mkdirs() }
        val f = { v: Double? -> v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-" }
        val text = buildString {
            appendLine("# Track day $name (FBBD-315)")
            appendLine()
            appendLine(
                "Best lap ${f(day.referenceLapSeconds)} s " +
                    "(${day.referenceSessionLabel} lap ${day.referenceLapNumber}), " +
                    "perfect lap ${f(day.perfectLapSeconds)} s, typical lap at pace ${f(day.typicalLapSeconds)} s, " +
                    "typical over the day ${f(day.daySeconds)} s faster.",
            )
            appendLine(
                "Best lap leaves ${f(day.bestLapGainSeconds)} s; a typical lap loses ${f(day.perLapGainSeconds)} s.",
            )
            appendLine()
            appendLine("| Session | laps | push | at day's pace | best | typical |")
            appendLine("|---|---|---|---|---|---|")
            day.sessions.forEach {
                appendLine(
                    "| ${it.label} | ${it.laps} | ${it.pushLaps} | ${it.paceLaps} | ${f(it.bestLapSeconds)} | " +
                        "${f(it.typicalLapSeconds)} |",
                )
            }
            appendLine()
            appendLine(
                "| Corner | per lap | best lap | kind | target | typical | over day | advice | by session (typical) |",
            )
            appendLine("|---|---|---|---|---|---|---|---|---|")
            day.ranked.forEach { c ->
                val by = c.sessions.joinToString(" ") { "${it.label}:${f(it.typicalSeconds)}" }
                appendLine(
                    "| ${c.name} | ${f(c.perLapGainSeconds)} | ${f(c.bestLapGainSeconds)} | ${c.loss} | " +
                        "${f(c.target.seconds)} (${c.target.source} L${c.target.lapNumber}) | " +
                        "${f(c.typicalSeconds)} | " +
                        "${f(c.daySeconds)} | ${c.advice} | $by |",
                )
            }
            appendLine()
            appendLine("Focus: " + day.focus().joinToString { it.name })
            if (proof != null) {
                appendLine()
                appendLine("| Morning focus | typical before | after | won back | verdict |")
                appendLine("|---|---|---|---|---|")
                appendLine(proof)
            }
        }
        File(dir, "day-$name.md").writeText(text)
    }
}
