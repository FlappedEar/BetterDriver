package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The same drive stored twice (FBBD-427), decided the same on Android and iOS. */
class SessionCopiesTest {
    private val start = 1_787_990_409_000L
    private val vbo = ImportOrigin("VBO", "session_20260829_143725.vbo")

    private fun session(
        id: String,
        startMillis: Long = start,
        duration: Long? = 1_200_000_000L,
        imported: ImportOrigin? = vbo,
        state: SessionState = SessionState.FINISHED,
    ) = StoredSession(id, "/s/$id", SessionInfo(startMillis, imported = imported), state, duration)

    @Test
    fun sameStartAndLengthIsTheSameDrive() {
        val a = session("a")
        assertSame(a, SessionCopies.copyOf(listOf(a), start, 1_200_000_000L))
        assertNull(SessionCopies.copyOf(listOf(a), start + 1, 1_200_000_000L), "another start")
        assertNull(SessionCopies.copyOf(listOf(a), start, 1_200_000_001L), "another length")
        assertNull(SessionCopies.copyOf(listOf(a), start, null), "no length")
        assertNull(SessionCopies.copyOf(listOf(session("r", state = SessionState.RECORDING)), start, 1_200_000_000L))
    }

    @Test
    fun extraImportedCopiesGoAndOneStays() {
        val sessions = listOf(session("b"), session("a"), session("c"), session("other", startMillis = start + 60_000))
        assertEquals(listOf("b", "c"), SessionCopies.extras(sessions) { false }.map { it.id })
        assertEquals(emptyList(), SessionCopies.extras(listOf(session("a")), { false }), "the only copy stays")
    }

    @Test
    fun aRecordedSessionOrOneWithVideoIsNeverRemoved() {
        val recorded = session("z", imported = null)
        assertEquals(listOf("a"), SessionCopies.extras(listOf(session("a"), recorded)) { false }.map { it.id })
        // Two recorded sessions are never touched, whatever they share.
        assertEquals(emptyList(), SessionCopies.extras(listOf(recorded, session("y", imported = null))) { false })
        // The copy with video is kept; the plain one goes.
        val withVideo = SessionCopies.extras(listOf(session("a"), session("b"))) { it.id == "b" }
        assertEquals(listOf("a"), withVideo.map { it.id })
        // Both with video: both stay.
        assertEquals(emptyList(), SessionCopies.extras(listOf(session("a"), session("b"))) { true })
    }
}
