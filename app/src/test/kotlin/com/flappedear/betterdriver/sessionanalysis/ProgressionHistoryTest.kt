package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.coaching.originOf
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a session is tried again for the progression (FBBD-181), and how an import is named. */
class ProgressionHistoryTest {
    @Test
    fun `a session tried while a restore started is tried again, one tried in peace is not`() {
        val tried = mutableSetOf<String>()
        var epoch = 4L
        // A restore swaps the data while the session is analysed: its record was not written.
        assertTrue(tryOnce("t/s1", tried, 4L, { epoch }) { epoch = 6L; true })
        assertFalse("t/s1" in tried)
        // Taken while a restore ran: never written either.
        tryOnce("t/s2", tried, 5L, { 5L }) { false }
        assertFalse("t/s2" in tried)
        tryOnce("t/s3", tried, 6L, { epoch }) { true }
        assertTrue("t/s3" in tried)
    }

    @Test
    fun `an imported session is named by its format and file, a recorded one by nothing`() {
        val imported = SessionInfo(startUtcMillis = 0, imported = ImportOrigin("VBO", "lap-12.vbo"))
        assertEquals("VBO lap-12.vbo", originOf(imported))
        assertNull(originOf(SessionInfo(startUtcMillis = 0)))
    }
}
