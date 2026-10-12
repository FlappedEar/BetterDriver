package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.runs.AccelerationRun
import com.flappedear.betterdriver.analysis.runs.AccelerationRunKind
import com.flappedear.betterdriver.analysis.runs.RunQualityIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccelerationUiTest {
    private fun run(
        kind: AccelerationRunKind,
        start: Double,
        seconds: Double,
        quality: Set<RunQualityIssue> = emptySet(),
    ) = AccelerationRun(kind, start, start + seconds, if (kind.distanceMeters != null) 150.0 else null, 0.9, quality)

    @Test
    fun `no runs, no card`() {
        assertNull(accelerationUi(emptyList()))
    }

    @Test
    fun `runs are grouped by kind with the best first and why a time is less certain`() {
        val ui = accelerationUi(
            listOf(
                run(AccelerationRunKind.ZeroTo100Kmh, 10.0, 4.2),
                run(AccelerationRunKind.ZeroTo100Kmh, 80.0, 4.5, setOf(RunQualityIssue.Gap, RunQualityIssue.LowRate)),
                run(AccelerationRunKind.EighthMile, 10.0, 7.7),
            ),
        )!!
        assertEquals(listOf("0–100 km/h", "1/8 mile"), ui.kinds.map { it.label })
        val zeroTo100 = ui.kinds[0]
        assertEquals(4.2, zeroTo100.best.seconds, 1e-9)
        assertEquals(2, zeroTo100.count)
        assertEquals("GPS under 10 Hz, GPS gap", zeroTo100.others.single().note)
        assertEquals(80.0, zeroTo100.others.single().atSeconds, 1e-9)
        assertEquals(150.0, ui.kinds[1].best.trapKmh!!, 1e-9)
        assertNull(zeroTo100.best.trapKmh)
    }

    @Test
    fun `at most ten runs of a kind are listed`() {
        val ui = accelerationUi((0 until 14).map { run(AccelerationRunKind.ZeroTo60Mph, it * 60.0, 4.0 + it / 10.0) })!!
        assertEquals(MaxRunsPerKind - 1, ui.kinds.single().others.size)
        assertEquals(14, ui.kinds.single().count)
    }

    @Test
    fun `a quicker flagged run stays the best, with its note`() {
        val ui = accelerationUi(
            listOf(
                run(AccelerationRunKind.ZeroTo100Kmh, 10.0, 4.0, setOf(RunQualityIssue.PoorAccuracy)),
                run(AccelerationRunKind.ZeroTo100Kmh, 80.0, 4.4),
            ),
        )!!
        val kind = ui.kinds.single()
        assertEquals(4.0, kind.best.seconds, 1e-9)
        assertEquals("GPS accuracy over 2 m", kind.best.note)
        assertEquals("", kind.others.single().note)
    }
}
