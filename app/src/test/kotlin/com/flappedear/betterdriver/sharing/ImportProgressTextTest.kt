package com.flappedear.betterdriver.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the import card says while files come in and when they are done (FBBD-314, UI-192 B, UI-193 A). */
class ImportProgressTextTest {
    @Test
    fun sizesUseDecimalUnits() {
        assertEquals("1.6 GB", sizeText(1_630_000_000))
        assertEquals("640 MB", sizeText(640_000_000))
        assertEquals("12 kB", sizeText(12_000))
        assertEquals("512 B", sizeText(512))
    }

    @Test
    fun bytesOfTheFileWithPercentOrJustReadWhenTheSizeIsUnknown() {
        assertEquals("640 MB of 1.6 GB · 40 %", bytesText(640_000_000, 1_600_000_000))
        assertEquals("312 MB read", bytesText(312_000_000, 0))
    }

    @Test
    fun timeLeftIsRounded() {
        assertNull(timeLeftText(null))
        assertEquals("a few seconds left", timeLeftText(4))
        assertEquals("about 25 s left", timeLeftText(21))
        assertEquals("about 1 min left", timeLeftText(70))
        assertEquals("about 3 min left", timeLeftText(160))
    }

    @Test
    fun oneFileEndsWithItsOwnMessage() {
        val s = summary(listOf(Outcome("a.bdsession", "Imported the session of 29 Aug 10:00.", false)), 1_000)
        assertEquals("Imported the session of 29 Aug 10:00.", s.text)
        assertFalse(s.failed)
        assertFalse(s.running)
    }

    @Test
    fun severalFilesEndWithACountAndOnlyTheFirstFailures() {
        val ok = Outcome("a.vbo", "Imported the VBO session of 29 Aug 10:00.", false)
        val bad = (1..5).map { Outcome("b$it.vbo", "Import failed: the file is damaged", true) }
        val good = summary(listOf(ok, ok), 1_630_000_000)
        assertEquals("Finished 2 files (1.6 GB). All came in.", good.text)
        val one = summary(listOf(ok, bad[0]), 0)
        assertEquals("Finished 2 files. 1 could not be imported:\nb1.vbo: Import failed: the file is damaged", one.text)
        assertFalse("red only when nothing came in", one.failed)
        val many = summary(listOf(ok) + bad, 0)
        assertEquals(
            listOf(
                "Finished 6 files. 5 could not be imported:",
                "b1.vbo: Import failed: the file is damaged",
                "b2.vbo: Import failed: the file is damaged",
                "b3.vbo: Import failed: the file is damaged",
                "and 2 more",
            ),
            many.text.lines(),
        )
        assertTrue(summary(bad.take(2), 0).failed)
    }
}
