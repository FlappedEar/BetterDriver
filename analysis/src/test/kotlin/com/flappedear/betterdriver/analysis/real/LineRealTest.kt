@file:Suppress("MagicNumber") // Arek's lap times and where on the lap his time is.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LineAnalysis
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.LineThresholds
import com.flappedear.betterdriver.analysis.line.analyzeLine
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
 * day (16:06, 1:49.91) against the fastest pass through each corner from any of the six. Checked
 * against a separate prototype on the same files (UI review round 42): about 1 s to find, most of
 * it in the last complex, where the faster passes take the long left about 3 m tighter.
 */
class LineRealTest {
    @TempDir
    lateinit var tmp: File

    private val track = RealCorpus.jastrzabTrack()

    private fun line(): LineAnalysis {
        val store = SessionStore(File(tmp, "sessions"))
        val sessions = RealCorpus.JASTRZAB_DAY.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
            name.substringAfter("2026-08-29-").take(4) to telemetrySessionOf(store.read(id), track)
        }
        val session = sessions.single { it.first == "1606" }.second
        val others = sessions.filter { it.first != "1606" }
            .map { (time, s) -> LineSession(s, deriveSourceLapSession(s), time) }
        return assertNotNull(analyzeLine(analyzeSession(session), session, others))
    }

    @Test
    fun `the time to find adds up from every session's laps, most of it in the last complex`() {
        val line = line()
        assertEquals(109.91, line.referenceLapSeconds, 0.01)
        assertEquals(6, line.sessions)
        assertTrue(line.laps >= 20, "laps ${line.laps}")
        assertTrue(line.corners.size >= 8, "corners ${line.corners.size}")
        assertTrue(line.corners.all { it.gainSeconds >= 0.0 })
        // Prototype: about 1.1 s over its own corner windows; the app's windows tile the lap.
        assertTrue(line.totalGainSeconds in 0.8..2.0, "total ${line.totalGainSeconds}")
        // The last complex (from about 1700 m of the ~2030 m lap) holds the most.
        val last = line.corners
            .filter { it.startMeters >= 1650.0 || it.endMeters < it.startMeters }
            .sumOf { it.gainSeconds }
        assertTrue(last >= 0.5 && last >= 0.4 * line.totalGainSeconds, "last complex $last of ${line.totalGainSeconds}")
        // Targets come from other sessions too, not only this one.
        assertTrue(line.corners.any { it.target.source.isNotEmpty() })
        // A corner where this lap is already the fastest gets no advice.
        line.corners.filter { it.gainSeconds == 0.0 }.forEach { assertTrue(!it.advice.any, it.name) }
    }

    @Test
    fun `the corner with most to find says where, and every line sits on the road the laps used`() {
        val line = line()
        val top = line.ranked.first()
        assertTrue(top.gainSeconds >= 0.3, "${top.name} ${top.gainSeconds}")
        assertTrue(top.turnsLeft, top.name)
        val tighter = assertNotNull(top.advice.tighterMeters, "${top.name} ${top.advice}")
        assertTrue(tighter in 2.0..5.0, "${top.name} tighter $tighter")
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
}
