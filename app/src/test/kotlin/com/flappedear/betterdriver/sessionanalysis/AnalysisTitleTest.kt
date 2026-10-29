package com.flappedear.betterdriver.sessionanalysis

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class AnalysisTitleTest {
    // 2026-10-29 09:12 UTC.
    private val start = 1_793_265_120_000L

    @Test
    fun startTimeThenTrack() {
        assertEquals("09:12 · Jastrząb", analysisTitle(start, "Jastrząb", ZoneOffset.UTC, Locale.UK))
    }

    @Test
    fun startTimeAloneWithoutATrack() {
        assertEquals("09:12", analysisTitle(start, " ", ZoneOffset.UTC, Locale.UK))
    }

    @Test
    fun lapAnalysisWhileTheSessionIsUnknown() {
        assertEquals("Lap analysis", analysisTitle(null, null))
    }
}
