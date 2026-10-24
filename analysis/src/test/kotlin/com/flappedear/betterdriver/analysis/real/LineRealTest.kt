@file:Suppress("MagicNumber") // Arek's lap times and where on the lap his time is.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LineAnalysis
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.LinePlanStore
import com.flappedear.betterdriver.analysis.line.LineThresholds
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.line.linePlan
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Line analysis (FBBD-306) on Arek's six Jastrząb sessions of 29 Aug 2026: his fastest lap of the
 * day (16:06, 1:49.91) against the second fastest pass through each corner from any of the six
 * (the coach's target rule, FBBD-177). Against the single fastest passes a separate prototype found
 * about 1 s (UI review round 42); a time driven twice leaves about 0.4 s, most of it in the last complex.
 */
class LineRealTest {
    @TempDir
    lateinit var tmp: File

    private val track = RealCorpus.jastrzabTrack()

    private fun line(): LineAnalysis = read().second

    private fun read(): Pair<SessionAnalysis, LineAnalysis> {
        val store = SessionStore(File(tmp, "sessions"))
        val sessions = RealCorpus.JASTRZAB_DAY.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
            name.substringAfter("2026-08-29-").take(4) to telemetrySessionOf(store.read(id), track)
        }
        val session = sessions.single { it.first == "1606" }.second
        val others = sessions.filter { it.first != "1606" }
            .map { (time, s) -> LineSession(s, deriveSourceLapSession(s), time, "s$time") }
        val analysis = analyzeSession(session)
        return analysis to assertNotNull(analyzeLine(analysis, session, others))
    }

    @Test
    fun `the time to find adds up from every session's laps, most of it in the last complex`() {
        val line = line()
        assertEquals(109.91, line.referenceLapSeconds, 0.01)
        assertEquals(6, line.sessions)
        assertTrue(line.laps >= 20, "laps ${line.laps}")
        assertTrue(line.corners.size >= 8, "corners ${line.corners.size}")
        assertTrue(line.corners.all { it.gainSeconds >= 0.0 })
        // Against the second fastest pass of each corner (a time driven twice, as the coach aims at),
        // with less than 0.05 s left out as timing noise: about 0.4 s, most of it in the long left
        // of the last complex (from about 1850 m of the ~2030 m lap), the rest at the first corner.
        assertTrue(line.totalGainSeconds in 0.3..0.8, "total ${line.totalGainSeconds}")
        val last = line.corners
            .filter { it.startMeters >= 1650.0 || it.endMeters < it.startMeters }
            .sumOf { it.gainSeconds }
        assertTrue(last >= 0.2 && last >= 0.5 * line.totalGainSeconds, "last complex $last of ${line.totalGainSeconds}")
        // A gain under the timing noise is none.
        line.corners.forEach { assertTrue(it.gainSeconds == 0.0 || it.gainSeconds >= LineThresholds().gainSeconds) }
        // A corner where this lap is already the fastest gets no advice.
        line.corners.filter { it.gainSeconds == 0.0 }.forEach { assertTrue(!it.advice.any, it.name) }
    }

    @Test
    fun `the corner with most to find says what to do, and every line sits on the road the laps used`() {
        val line = line()
        val top = line.ranked.first()
        assertTrue(top.gainSeconds >= 0.25, "${top.name} ${top.gainSeconds}")
        assertTrue(top.turnsLeft, top.name)
        // The faster pass there turns later and is back on the throttle sooner.
        assertTrue(top.advice.any, "${top.name} ${top.advice}")
        for (c in line.corners) {
            assertTrue(c.road.isNotEmpty() && c.reference.line.isNotEmpty() && c.target.line.isNotEmpty(), c.name)
            // Every difference given as advice is beyond what the receiver repeats.
            c.advice.tighterMeters?.let { assertTrue(abs(it) >= LineThresholds().lineMeters) }
            c.advice.brakeMeters?.let { assertTrue(abs(it) >= LineThresholds().placeMeters) }
            val band = c.road.associateBy { it.progressMeters }
            for (p in c.reference.line + c.target.line) {
                val b = band[p.progressMeters]
                val o = p.offsetMeters
                if (b == null || o == null) continue
                val inside = o <= b.leftMeters + 1e-6 && o >= b.rightMeters - 1e-6
                assertTrue(inside, "${c.name} at ${p.progressMeters}: $o")
            }
        }
    }

    @Test
    fun `the plan keeps the same targets, joins them into the perfect lap and names the focus`() {
        val (analysis, line) = read()
        val plan = linePlan(line, analysis.axis!!.origin, "jastrzab", "s1606", 1L, identityRevision = null)
        val store = LinePlanStore(File(tmp, "lines"))
        assertTrue(store.save(plan))
        assertEquals(plan, store.get("jastrzab"))
        assertTrue(!store.save(plan), "the same plan is not written again")
        // An older session analysed again keeps its own plan; the track's is still the latest session's.
        assertTrue(store.save(plan.copy(sessionId = "older", sessionStartUtcMillis = 0L)))
        assertEquals("s1606", store.get("jastrzab")!!.sessionId)
        assertEquals(listOf("older", "s1606"), store.plans("jastrzab").map { it.sessionId })
        // Each corner knows this session's fastest pass through it, never slower than its best lap's.
        plan.corners.forEach { assertTrue(it.sessionBestSeconds!! <= it.reference.seconds + 1e-9, it.name) }
        // The same numbers as the analysis, corner by corner.
        assertEquals(line.totalGainSeconds, plan.totalGainSeconds, 1e-9)
        assertEquals(line.ranked.first().name, plan.focus().first().name)
        // Driven targets with something to do, then braking points beyond his best (FBBD-312).
        assertTrue(plan.focus().all { (it.gainSeconds > 0.0 && it.advice.any) || it.brakeRoom != null })
        assertTrue(plan.corners.all { it.cornerId == null }, "no CornerIds without the track's identity")
        // A target from another session names it.
        plan.corners.map { it.target }.filter { it.sessionLabel.isNotEmpty() }
            .forEach { assertEquals("s" + it.sessionLabel, it.sessionId) }
        val perfect = plan.perfectLap()
        assertEquals(line.referenceLapSeconds - line.totalGainSeconds, perfect.seconds, 1e-9)
        assertEquals(plan.corners.size, perfect.pieces.size)
        // One line round the lap, with no point twice where the windows meet.
        val progress = perfect.points.map { it.progressMeters }
        assertEquals(progress.size, progress.zipWithNext().count { (a, b) -> abs(a - b) > 0.01 } + 1)
        assertTrue(perfect.points.size > 900, "points ${perfect.points.size}")
        perfect.pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.toIndex, b.fromIndex) }
        // Brake, slowest point and throttle where the target has them, at the corner's own speed.
        val top = plan.corners.first { it.name == plan.focus().first().name }
        assertNotNull(top.target.apex?.speedKmh)
    }
}
