package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same drive stored twice (FBBD-427), decided the same on Android and iOS. */
class SessionCopiesTest {
    private val start = 1_787_990_409_000L
    private val vbo = ImportOrigin("VBO", "session_20260829_143725.vbo")
    private val same = { _: StoredSession, _: StoredSession -> true }

    private fun session(
        id: String,
        startMillis: Long = start,
        duration: Long? = 1_200_000_000L,
        imported: ImportOrigin? = vbo,
        state: SessionState = SessionState.FINISHED,
    ) = StoredSession(id, "/s/$id", SessionInfo(startMillis, imported = imported), state, duration)

    private fun extras(vararg s: StoredSession, video: (StoredSession) -> Boolean = { false }) =
        SessionCopies.extras(s.toList(), video, same).map { it.id }

    @Test
    fun extraImportedCopiesGoAndOneStays() {
        assertEquals(listOf("b", "c"), extras(session("b"), session("a"), session("c"), session("x", start + 60_000)))
        assertEquals(emptyList(), extras(session("a")), "the only copy stays")
        assertEquals(emptyList(), extras(session("a"), session("b", duration = 1_200_000_001L)), "another length")
        assertEquals(emptyList(), extras(session("a"), session("b", state = SessionState.RECORDING)), "recording")
    }

    @Test
    fun copiesThatHoldDifferentDataBothStay() {
        val rpm = listOf(ChannelDef(200, "Engine_RPM", "rpm", "import", 0))
        val withRpm = session("b").let { it.copy(info = it.info.copy(channels = rpm)) }
        assertEquals(emptyList(), extras(session("a"), withRpm), "other channels")
        val differ = SessionCopies.extras(listOf(session("a"), session("b")), { false }) { _, _ -> false }
        assertEquals(emptyList(), differ, "other data")
    }

    @Test
    fun aRecordedSessionOrOneWithVideoIsNeverRemoved() {
        val recorded = session("z", imported = null)
        assertEquals(listOf("a"), extras(session("a"), recorded))
        // Two recorded sessions are never touched, whatever they share.
        assertEquals(emptyList(), extras(recorded, session("y", imported = null)))
        // The copy with video is kept; the plain one goes.
        assertEquals(listOf("a"), extras(session("a"), session("b")) { it.id == "b" })
        // Both with video: both stay.
        assertEquals(emptyList(), extras(session("a"), session("b")) { true })
    }
}
