package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.quality.Basis
import com.flappedear.betterdriver.analysis.quality.QualityMetric
import com.flappedear.betterdriver.analysis.quality.QualityReport
import com.flappedear.betterdriver.analysis.quality.QualitySection
import com.flappedear.betterdriver.analysis.quality.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityUiTest {
    private fun metric(label: String, value: Double?, unit: String, verdict: Verdict, basis: Basis = Basis.MEASURED) =
        QualityMetric(label.lowercase(), label, value, unit, verdict, basis, null, "")

    @Test
    fun `checked values are listed failures first, information is left out and unchecked criteria named`() {
        val report = QualityReport(
            sessionId = "s",
            sessionState = "FINISHED",
            computedAtUtcMillis = 0,
            sections = listOf(
                QualitySection(
                    "GNSS",
                    listOf(
                        metric("Sample rate", 24.93, "Hz", Verdict.PASS),
                        metric("Longest gap", 412.0, "ms", Verdict.FAIL),
                        metric("Link drops", 0.0, "", Verdict.INFO),
                        metric("Longest reconnect", null, "", Verdict.NOT_MEASURED, Basis.NONE),
                    ),
                ),
                QualitySection(
                    "Timing",
                    listOf(
                        metric("Suspect laps", 0.0, "", Verdict.PASS, Basis.DERIVED),
                        metric("Position noise parked", 0.214, "m", Verdict.PASS),
                    ),
                ),
            ),
        )
        val ui = qualityUi(report)
        assertEquals(3, ui.passed)
        assertEquals(1, ui.failed)
        assertEquals(
            listOf(
                QualityRow("GNSS", "Longest gap", "412 ms", pass = false, derived = false),
                QualityRow("GNSS", "Sample rate", "24.9 Hz", pass = true, derived = false),
                QualityRow("Timing", "Suspect laps", "0", pass = true, derived = true),
                QualityRow("Timing", "Position noise parked", "0.21 m", pass = true, derived = false),
            ),
            ui.rows,
        )
        assertEquals(listOf("Longest reconnect"), ui.notMeasured)
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
