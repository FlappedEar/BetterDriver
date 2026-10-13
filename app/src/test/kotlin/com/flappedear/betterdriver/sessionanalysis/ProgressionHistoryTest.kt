package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which sessions the progression analyses when it opens (FBBD-181), and how an import is named. */
class ProgressionHistoryTest {
    private val sessions = (1..30).map { "s$it" }

    @Test
    fun `sessions without a current record are added, newest first, never twice, at most twenty`() {
        val todo = sessionsToAdd(sessions, current = { it in setOf("s1", "s3") }, tried = { it == "s2" })
        assertEquals(MaxProgressionAdds, todo.size)
        assertEquals(listOf("s4", "s5", "s6"), todo.take(3))
        assertEquals(emptyList<String>(), sessionsToAdd(sessions, current = { true }, tried = { false }))
        assertEquals(listOf("s9"), sessionsToAdd(listOf("s9"), current = { false }, tried = { false }))
    }

    @Test
    fun `an imported session is named by its format and file, a recorded one by nothing`() {
        val imported = SessionInfo(startUtcMillis = 0, imported = ImportOrigin("VBO", "lap-12.vbo"))
        assertEquals("VBO lap-12.vbo", originOf(imported))
        assertNull(originOf(SessionInfo(startUtcMillis = 0)))
    }
}
