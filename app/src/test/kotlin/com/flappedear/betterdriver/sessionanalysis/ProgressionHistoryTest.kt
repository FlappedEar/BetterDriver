package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.sessionanalysis.AddResult.Added
import com.flappedear.betterdriver.sessionanalysis.AddResult.Elsewhere
import com.flappedear.betterdriver.sessionanalysis.AddResult.Failed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which sessions the progression analyses when it opens (FBBD-181), and how an import is named. */
class ProgressionHistoryTest {
    /** Goes through [candidates] as the progression does; the sessions analysed, in order, and how many were added. */
    private fun goThrough(
        candidates: List<String>,
        current: Set<String> = emptySet(),
        tried: Set<String> = emptySet(),
        result: (String) -> AddResult = { AddResult.Added },
    ): Pair<List<String>, Int> {
        val analysed = mutableListOf<String>()
        val added = runBlocking {
            addNewest(
                candidates,
                current = { it in current },
                tried = { it in tried },
                add = { id ->
                    analysed += id
                    result(id)
                },
            )
        }
        return analysed to added
    }

    @Test
    fun `sessions without a current record are added, newest first, never twice, at most twenty`() {
        val sessions = (1..30).map { "s$it" }
        val (analysed, added) = goThrough(sessions, current = setOf("s1", "s3"), tried = setOf("s2"))
        assertEquals(MaxProgressionAdds, added)
        assertEquals(listOf("s4", "s5", "s6"), analysed.take(3))
        assertEquals(emptyList<String>(), goThrough(sessions, current = sessions.toSet()).first)
        assertEquals(listOf("s9") to 1, goThrough(listOf("s9")))
    }

    @Test
    fun `sessions at other tracks never use up the limits`() {
        // 130 newer sessions at another track, then 25 at this one: twenty of this track's are added.
        val other = (1..130).map { "o$it" }
        val here = (1..25).map { "t$it" }
        val (analysed, added) = goThrough(other + here, result = { if (it.startsWith("t")) Added else Elsewhere })
        assertEquals(MaxProgressionAdds, added)
        assertEquals(here.take(MaxProgressionAdds), analysed.filter { it.startsWith("t") })

        // Opened again with the other track's sessions tried already: the rest of this track's are reached.
        val (again, more) = goThrough(other + here, current = here.take(20).toSet(), tried = other.toSet())
        assertEquals(here.drop(20), again)
        assertEquals(5, more)
    }

    @Test
    fun `the scan stops after so many of the track's sessions, current or added`() {
        val kept = (1..MaxProgressionScan).map { "k$it" }
        assertEquals(emptyList<String>(), goThrough(kept + "old", current = kept.toSet()).first)
        assertEquals(listOf("old"), goThrough(kept.drop(1) + "old", current = kept.toSet()).first)
    }

    @Test
    fun `sessions that cannot be read or analysed count towards the limits`() {
        // 200 damaged sessions: one opening tries twenty, not all of them.
        val damaged = (1..200).map { "d$it" }
        val (analysed, tried) = goThrough(damaged, result = { Failed })
        assertEquals(damaged.take(MaxProgressionAdds), analysed)
        assertEquals(MaxProgressionAdds, tried)
    }

    @Test
    fun `how many more may be added is told before each, and none at the end`() {
        val heard = mutableListOf<Int>()
        runBlocking {
            addNewest(listOf("a", "b", "c"), { it == "b" }, { false }, { Added }, left = { heard += it })
        }
        assertEquals(listOf(2, 1, 0), heard)
    }

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
