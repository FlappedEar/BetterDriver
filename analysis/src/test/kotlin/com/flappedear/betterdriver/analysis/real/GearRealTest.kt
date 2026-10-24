@file:Suppress("MagicNumber") // Arek's laps, the FR-S's gears and what they pull.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LineAnalysis
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.gear.FrsAutomatic
import com.flappedear.betterdriver.timing.track.Track
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The gear in each corner (FBBD-326) on Arek's FR-S days: Jastrząb (29 Aug 2026, six sessions) and
 * Silesia Ring (27 Sep 2026, three). The car is the 6-speed automatic; its engine speed against road
 * speed shows 2nd, 3rd and 4th, and at full throttle 2nd pulls harder than 3rd up to about 98 km/h
 * (the FA20's torque dips at 3500-4000 rpm, where 3rd sits at 70-80 km/h). At Silesia some corners
 * were taken in 3rd; at Jastrząb nearly every corner already is in 2nd.
 */
class GearRealTest {
    @TempDir
    lateinit var tmp: File

    private fun line(names: List<String>, track: Track, own: String, label: (String) -> String): LineAnalysis {
        val store = SessionStore(File(tmp, "sessions-" + System.nanoTime()))
        val sessions = names.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
            label(name) to telemetrySessionOf(store.read(id), track)
        }
        val session = sessions.single { it.first == own }.second
        val others = sessions.filter { it.first != own }
            .map { (time, s) -> LineSession(s, deriveSourceLapSession(s), time, "s$time") }
        return assertNotNull(analyzeLine(analyzeSession(session), session, others))
    }

    private fun silesia() = line(RealCorpus.SILESIA_DAY, RealCorpus.silesiaTrack(), "1454") {
        it.substringAfter("2026-09-27-").take(4).takeIf { t -> t.all(Char::isDigit) } ?: "first"
    }

    private fun jastrzab() = line(RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack(), "1606") {
        it.substringAfter("2026-08-29-").take(4)
    }

    @Test
    fun `the FR-S's automatic shows 2nd to 4th, and 2nd pulls harder than 3rd at Silesia's corner speeds`() {
        val box = assertNotNull(silesia().gearbox)
        assertEquals(FrsAutomatic.name, box.spec)
        val gears = box.gears.map { it.gear }
        assertTrue(listOf(2, 3, 4).all { it in gears }, "gears $gears")
        // The steps between the gears are the gearbox's own: rpm per road speed 2nd/3rd = 2.060/1.404.
        val second = box.gears.single { it.gear == 2 }
        val third = box.gears.single { it.gear == 3 }
        assertEquals(2.060 / 1.404, second.rpmPerMps / third.rpmPerMps, 0.02)
        // 215/45 R17 and a 4.1 final drive: 2.060 x 4.1 engine turns per wheel turn, about 72 rpm per km/h in 2nd.
        assertEquals(72.0 * 3.6, second.rpmPerMps, 8.0)
        assertTrue(box.maxRpm in 6500.0..7600.0, "max ${box.maxRpm}")
        for (kmh in listOf(60.0, 70.0, 80.0)) {
            val v = kmh / 3.6
            val p2 = assertNotNull(box.pullAt(2, v), "2nd at $kmh")
            val p3 = assertNotNull(box.pullAt(3, v), "3rd at $kmh")
            assertTrue(p2 >= p3 * 1.2, "at $kmh km/h 2nd ${p2 / 9.81} g, 3rd ${p3 / 9.81} g")
        }
        // 2nd is asked for while it has room to the exit: not at 80 km/h, already near 5800 rpm.
        assertEquals(2, box.lowerGear(3, 60 / 3.6))
        assertEquals(2, box.lowerGear(3, 70 / 3.6))
        assertEquals(null, box.lowerGear(3, 95 / 3.6))
    }

    @Test
    fun `Silesia's corners taken in 3rd get 2nd, with his own laps as the reason`() {
        val line = silesia()
        val geared = line.corners.filter { it.gear != null }
        assertTrue(geared.isNotEmpty(), "no corner gets a gear")
        geared.forEach { c ->
            val g = c.gear!!
            assertEquals(2, g.gear, c.name)
            assertEquals(3, g.from, c.name)
            assertTrue(g.apexKmh in 40.0..95.0, "${c.name} apex ${g.apexKmh}")
            assertTrue(g.lowerPullG >= g.higherPullG * 1.15, "${c.name} pull ${g.lowerPullG} vs ${g.higherPullG}")
            assertTrue(g.higherLaps in 1..g.laps, "${c.name} ${g.higherLaps} of ${g.laps}")
            if (g.lowerExitKmh != null && g.higherExitKmh != null) {
                val exits = "${c.name} exit ${g.lowerExitKmh} vs ${g.higherExitKmh}"
                assertTrue(g.lowerExitKmh!! > g.higherExitKmh!!, exits)
            }
            println("Silesia ${c.name} at ${c.startMeters.toInt()} m: ${g.from} -> ${g.gear}, apex ${g.apexKmh}, " +
                "${g.higherLaps}/${g.laps} laps, exit ${g.lowerExitKmh} vs ${g.higherExitKmh}, " +
                "pull ${g.lowerPullG} vs ${g.higherPullG} g, ${g.seconds} s")
        }
    }

    @Test
    fun `at Jastrząb nearly every corner is already in 2nd, the fast one before the line is not`() {
        val line = jastrzab()
        assertNotNull(line.gearbox)
        val geared = line.corners.filter { it.gear != null }
        geared.forEach { println("Jastrząb ${it.name} at ${it.startMeters.toInt()} m: ${it.gear}") }
        assertTrue(geared.size in 1..3, "geared ${geared.map { it.name }} of ${line.corners.size}")
        geared.forEach { c ->
            val g = c.gear!!
            assertEquals(2, g.gear, c.name)
            // Nothing asked for where 2nd would be near the top of the engine.
            assertTrue(abs(g.apexKmh) < 95.0, c.name)
            if (g.lowerExitKmh != null && g.higherExitKmh != null) {
                assertTrue(g.lowerExitKmh!! > g.higherExitKmh!!, c.name)
            }
        }
    }
}
