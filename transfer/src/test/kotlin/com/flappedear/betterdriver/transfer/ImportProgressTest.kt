@file:Suppress("MagicNumber") // Test sizes and times.

package com.flappedear.betterdriver.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Progress of an import of one or several files (FBBD-314, UI-192 B, UI-193 A). */
class ImportProgressTest {
    private var now = 0L
    private val second = 1_000_000_000L

    @Test
    fun `one file counts its bytes against its size and never passes it`() {
        val p = ImportProgress(listOf(1_000L)) { now }
        p.start(0)
        p.bytes(400)
        assertEquals(0.4f, p.snapshot().fileFraction)
        assertEquals(0.4f, p.snapshot().totalFraction)
        p.bytes(700)
        val s = p.snapshot()
        assertEquals(1_000L, s.fileDone)
        assertEquals(1f, s.fileFraction)
        assertEquals(1f, s.totalFraction)
    }

    @Test
    fun `several files share one total, and a file that stopped half way counts whole`() {
        val p = ImportProgress(listOf(100L, 300L, 600L)) { now }
        p.start(0)
        p.bytes(50) // failed half way
        p.start(1)
        p.bytes(150)
        val s = p.snapshot()
        assertEquals(1, s.file)
        assertEquals(3, s.files)
        assertEquals(0.5f, s.fileFraction)
        assertEquals(250L, s.totalDone)
        assertEquals(1_000L, s.totalSize)
        assertEquals(0.25f, s.totalFraction)
    }

    @Test
    fun `an unknown size gives bytes read and a total by files done, with no time left`() {
        val p = ImportProgress(listOf(500L, 0L)) { now }
        p.start(1)
        repeat(10) {
            now += second / 2
            p.bytes(100)
        }
        val s = p.snapshot()
        assertNull(s.fileFraction)
        assertEquals(1_000L, s.fileDone)
        assertEquals(0L, s.totalSize)
        assertEquals(0.5f, s.totalFraction)
        assertNull(s.secondsLeft)
    }

    @Test
    fun `time left comes from the reading speed and leaves pauses out`() {
        val p = ImportProgress(listOf(1_000_000L, 1_000_000L)) { now }
        p.start(0)
        p.bytes(0)
        assertNull(p.snapshot().secondsLeft, "not enough read yet")
        repeat(4) {
            now += second
            p.bytes(100_000) // 100 kB/s
        }
        assertEquals(16L, p.snapshot().secondsLeft) // 1.6 MB left
        now += 60 * second // waiting for a keep-or-replace answer
        p.start(1)
        p.bytes(100_000)
        assertEquals(9L, p.snapshot().secondsLeft, "the pause does not slow the estimate")
    }

    @Test
    fun `changes are worth showing once per tenth of a percent or half a second`() {
        val p = ImportProgress(listOf(1_000_000L)) { now }
        p.start(0)
        assertTrue(p.bytes(10))
        assertFalse(p.bytes(10), "same tenth of a percent, too soon")
        assertTrue(p.bytes(1_000))
        now += second / 2
        assertTrue(p.bytes(1), "half a second later")
    }

    @Test
    fun `several files go in oldest session first by the time in their names`() {
        val names = listOf(
            "session_20260829_160659_demo_video.bdsession",
            "lap.vbo",
            "session_20260829_100009_jastrzab.vbo",
            "session_20260829_172004_jastrzab.vbo",
            "other.csv",
            "session_20260829-130317.vbo",
        )
        assertEquals(listOf(2, 5, 0, 3, 1, 4), ImportOrder.oldestFirst(names))
    }
}
