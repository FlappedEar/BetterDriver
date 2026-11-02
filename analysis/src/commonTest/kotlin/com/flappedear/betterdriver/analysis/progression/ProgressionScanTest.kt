package com.flappedear.betterdriver.analysis.progression

import com.flappedear.betterdriver.analysis.progression.AddResult.Added
import com.flappedear.betterdriver.analysis.progression.AddResult.Elsewhere
import com.flappedear.betterdriver.analysis.progression.AddResult.Failed
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which sessions the progression analyses when it opens (FBBD-181), on Android and iOS (FBBD-360). */
class ProgressionScanTest {
    /** Goes through [candidates] as the progression does; the sessions analysed, in order, and how many were added. */
    private fun goThrough(
        candidates: List<String>,
        current: Set<String> = emptySet(),
        tried: Set<String> = emptySet(),
        result: (String) -> AddResult = { AddResult.Added },
    ): Pair<List<String>, Int> {
        val analysed = mutableListOf<String>()
        val added = addNewest(
            candidates,
            current = { it in current },
            tried = { it in tried },
            add = { id ->
                analysed += id
                result(id)
            },
        )
        return analysed to added
    }

    @Test
    fun `sessions without a current record are added newest first never twice at most twenty`() {
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
    fun `the scan stops after so many of the track sessions current or added`() {
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
    fun `how many more may be added is told before each and none at the end`() {
        val heard = mutableListOf<Int>()
        addNewest(listOf("a", "b", "c"), { it == "b" }, { false }, { Added }, left = { heard += it })
        assertEquals(listOf(2, 1, 0), heard)
    }
}
