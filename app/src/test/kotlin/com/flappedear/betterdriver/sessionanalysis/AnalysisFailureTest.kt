package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.feature.analysis.AnalysisState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The lap analysis of a session never ends in a spinner that does not stop or the app closing, and
 * writes its numbers as the rest of the app does (1.0 cleanup).
 */
class AnalysisFailureTest {
    @Test
    fun `an analysis that throws says so on its screen`() {
        val state = analysedOr<AnalysisState>("s", { it }) { error("a corner with no samples") }
        assertEquals(AnalysisState.Unavailable(NotAnalysedText), state)
    }

    @Test
    fun `an analysis that works is shown as made`() {
        val made = AnalysisState.Unavailable("No lap counts")
        assertEquals(made, analysedOr<AnalysisState>("s", { it }) { made })
    }

    @Test
    fun `a loop length is written with a point whatever the phone's language`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"))
            assertEquals("2.41", kmText(2_412.0))
            assertEquals("0.95", kmText(950.0))
        } finally {
            Locale.setDefault(before)
        }
    }
}
