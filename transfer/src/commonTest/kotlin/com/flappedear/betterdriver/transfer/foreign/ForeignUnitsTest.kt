package com.flappedear.betterdriver.transfer.foreign

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Units of imported speed and acceleration, per column (review v1.34.1 F11, F12): a VBO speed column
 * whose header says knots is converted from knots, and each acceleration axis is converted by its own
 * unit in both the VBO and the RaceChrono CSV reader.
 */
class ForeignUnitsTest {
    private class Points : ImportSink {
        val received = ArrayList<ImportPoint>()
        override fun extras(columns: List<ExtraColumn>) = Unit
        override fun point(p: ImportPoint) {
            received += p
        }
        override fun skipped() = Unit
    }

    private fun lines(text: String): LineReader {
        val chars = text.toCharArray()
        var pos = 0
        return LineReader { buf ->
            if (pos >= chars.size) {
                -1
            } else {
                val n = minOf(buf.size, chars.size - pos)
                chars.copyInto(buf, 0, pos, pos + n)
                pos += n
                n
            }
        }
    }

    private fun vbo(speedHeader: String, accelUnits: List<String>): ImportPoint {
        val text = buildString {
            appendLine("File created on 12/10/2026 @ 10:42:00")
            appendLine("[header]")
            for (h in listOf("time", "latitude", "longitude", speedHeader, "longacc", "latacc")) appendLine(h)
            appendLine("[channel units]")
            // One line per non-standard column, in column order: the speed (unless km/h) and both axes.
            if (speedHeader != "velocity kmh") appendLine("knots")
            accelUnits.forEach(::appendLine)
            appendLine("[column names]")
            appendLine("time lat long velocity longacc latacc")
            appendLine("[data]")
            appendLine("104200.00 +2700.00000 +00030.00000 60.000 -0.5 9.80665")
        }
        val sink = Points()
        VboReader(fallbackEpochDay = 0).read(lines(text), sink)
        return sink.received.single()
    }

    private fun csv(longUnit: String, latUnit: String): ImportPoint {
        val text = """
            Timestamp,Latitude,Longitude,Speed,Longitudinal acc,Lateral acc
            s,deg,deg,km/h,$longUnit,$latUnit
            1791801720.0,45.0,-0.5,100.0,${if (longUnit == "G") "-0.5" else "-4.903325"},${if (latUnit == "G") "1.0" else "9.80665"}
        """.trimIndent()
        val sink = Points()
        RaceChronoCsvReader.read(lines(text), sink)
        return sink.received.single()
    }

    private fun near(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected, was $actual")

    @Test
    fun `a VBO velocity column whose header says knots is stored in km per hour`() {
        near(111.12, vbo("velocity knots", listOf("g", "g")).speedKmh, "knots")
        near(60.0, vbo("velocity kmh", listOf("g", "g")).speedKmh, "km/h")
    }

    @Test
    fun `each VBO acceleration axis is converted by its own unit`() {
        val mixed = vbo("velocity kmh", listOf("g", "m/s²"))
        near(-0.5, mixed.accelForwardG, "longitudinal in g")
        near(1.0, mixed.accelLateralG, "lateral in m/s²")
    }

    @Test
    fun `each CSV acceleration axis is converted by its own unit in either order`() {
        csv("G", "m/s2").let {
            near(-0.5, it.accelForwardG, "long G")
            near(1.0, it.accelLateralG, "lat m/s2")
        }
        csv("m/s2", "G").let {
            near(-0.5, it.accelForwardG, "long m/s2")
            near(1.0, it.accelLateralG, "lat G")
        }
        csv("G", "G").let {
            near(-0.5, it.accelForwardG, "long G")
            near(1.0, it.accelLateralG, "lat G")
        }
        assertEquals(100.0, csv("G", "G").speedKmh)
    }
}
