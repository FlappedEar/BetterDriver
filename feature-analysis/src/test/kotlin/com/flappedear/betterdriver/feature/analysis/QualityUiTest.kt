package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.quality.Basis
import com.flappedear.betterdriver.analysis.quality.QualityMetric
import com.flappedear.betterdriver.analysis.quality.QualityReport
import com.flappedear.betterdriver.analysis.quality.QualitySection
import com.flappedear.betterdriver.analysis.quality.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityUiTest {
    private fun metric(
        label: String,
        value: Double?,
        unit: String,
        verdict: Verdict,
        basis: Basis = Basis.MEASURED,
        criterion: String? = null,
    ) = QualityMetric(label.lowercase(), label, value, unit, verdict, basis, criterion, "")

    @Test
    fun `the card counts the checks and lists the failures with their keys`() {
        val report = QualityReport(
            sessionId = "s",
            sessionState = "FINISHED",
            computedAtUtcMillis = 0,
            sections = listOf(
                QualitySection(
                    "GNSS",
                    listOf(
                        metric("Sample rate", 24.93, "Hz", Verdict.PASS),
                        metric("Longest gap", 412.0, "ms", Verdict.FAIL, criterion = "<= 200 ms (FBBD-8, FBBD-46)"),
                        metric("Link drops", 0.0, "", Verdict.INFO),
                        metric("Longest reconnect", null, "", Verdict.NOT_MEASURED, Basis.NONE),
                    ),
                ),
                QualitySection(
                    "Timing",
                    listOf(
                        metric("Suspect laps", 2.0, "", Verdict.FAIL, Basis.DERIVED),
                        metric("Position noise parked", 0.214, "m", Verdict.PASS),
                    ),
                ),
            ),
        )
        val ui = qualityUi(report)
        assertEquals(2, ui.passed)
        assertEquals(2, ui.failed)
        assertEquals(1, ui.notMeasured)
        assertEquals(
            listOf(
                QualityRow("GNSS", "Longest gap", "412 ms", "FBBD-8, FBBD-46", derived = false),
                QualityRow("Timing", "Suspect laps", "2", "", derived = true),
            ),
            ui.failures,
        )
    }

    @Test
    fun `values read at a sensible precision`() {
        assertEquals("3", valueText(3.0, ""))
        assertEquals("2.5", valueText(2.5, ""))
        assertEquals("99.5 %", valueText(99.5, "%"))
        assertEquals("100 %", valueText(100.0, "%"))
        assertEquals("-53.9 ms", valueText(-53.9, "ms"))
        assertEquals("1.5 s", valueText(1.5, "s"))
    }
}
