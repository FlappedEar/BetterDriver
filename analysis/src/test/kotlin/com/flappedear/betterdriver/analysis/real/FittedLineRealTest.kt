@file:Suppress("MagicNumber") // Speeds and widths of the check.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.timing.edit.LapPath
import com.flappedear.betterdriver.timing.edit.MinFittedLineWidthMeters
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.Gate
import com.flappedear.betterdriver.timing.track.DefaultLineWidthMeters
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FBBD-438 on Arek's real Jastrząb and Silesia sessions: a line placed in the track editor where
 * RaceChrono's line is, from the session's own drive, is narrower than the old 30 m and still
 * catches every pass at racing speed that RaceChrono's line (20 to 75 m wide) catches.
 */
class FittedLineRealTest {
    /** The drive as the editor gets it, with times: these RaceChrono files are 10 Hz. */
    private fun drive(fixes: List<RealCorpus.VboFix>) =
        LapPath(fixes.map { GeoPoint(it.lat, it.lon) }, fixes.indices.map { it * 100_000L })

    /** Indices of fixes after which the drive crosses [line] forwards at [minKmh] or more. */
    private fun passes(path: LapPath, kmh: List<Double>, line: TrackLine, minKmh: Double): List<Int> {
        val gate = Gate(line, path.frame)
        val xy = path.points.map(path.frame::toXy)
        return (0 until xy.size - 1).filter { i ->
            kmh[i] >= minKmh && gate.crossing(xy[i], i.toLong(), xy[i + 1], (i + 1).toLong()) != null
        }
    }

    @Test
    fun `lines fitted to the passes catch every racing lap RaceChrono's lines catch`() {
        val rows = mutableListOf<String>()
        val startWidths = mutableListOf<Double>()
        for (name in RealCorpus.JASTRZAB_DAY + RealCorpus.SILESIA_DAY) {
            val fixes = RealCorpus.fixes(name)
            val path = drive(fixes)
            val kmh = fixes.map { it.kmh }
            for (rc in RealCorpus.raceChronoLines(name)) {
                val reference = RealCorpus.trackLine(rc)
                val fitted = assertNotNull(path.lineAt(rc.first), "$name ${rc.name}: no line")
                val want = passes(path, kmh, reference, 60.0)
                val got = passes(path, kmh, fitted, 60.0)
                rows += "$name ${rc.name}: ${"%.1f".format(fitted.widthMeters)} m " +
                    "(RaceChrono ${"%.0f".format(reference.widthMeters)} m), ${got.size}/${want.size} passes"
                assertTrue(want.isNotEmpty(), "$name ${rc.name}: RaceChrono's line catches nothing")
                // The same passes: lines a few metres apart along the track cross a fix or two apart.
                assertEquals(want.size, got.size, rows.last())
                want.zip(got).forEach { (w, g) -> assertTrue(kotlin.math.abs(w - g) <= 10, rows.last()) }
                if (rc.type == "Start") startWidths += fitted.widthMeters
                assertTrue(fitted.widthMeters in MinFittedLineWidthMeters - 0.01..DefaultLineWidthMeters + 0.01)
            }
        }
        println(rows.joinToString("\n"))
        // Start/finish lines sit on a straight where the laps run close together: well under 30 m.
        assertEquals(9, startWidths.size)
        // Silesia's pit lane runs 18 m from the straight at 16 to 49 km/h: never part of a start line
        // fitted on a racing pass (FBBD-454). With it, the 27 Sep 13:00 line was 18.4 m wide.
        assertTrue(startWidths.all { it <= 16.0 }, startWidths.joinToString())
        assertTrue(startWidths.all { it <= 20.0 }, startWidths.joinToString())
    }

    @Test
    fun `a line fitted on the day's last session catches the racing laps of every session that day`() {
        for (day in listOf(RealCorpus.JASTRZAB_DAY, RealCorpus.SILESIA_DAY)) {
            // The editor places lines on the newest session's drive.
            val newest = RealCorpus.fixes(day.last())
            val placedOn = drive(newest)
            for (rc in RealCorpus.raceChronoLines(day.last())) {
                val fitted = assertNotNull(placedOn.lineAt(rc.first))
                for (name in day) {
                    val fixes = RealCorpus.fixes(name)
                    val path = drive(fixes)
                    val kmh = fixes.map { it.kmh }
                    val want = passes(path, kmh, RealCorpus.trackLine(rc), 60.0).size
                    val got = passes(path, kmh, fitted, 60.0).size
                    assertEquals(want, got, "$name ${rc.name}: line fitted on ${day.last()}, ${fitted.widthMeters} m")
                }
            }
        }
    }
}
