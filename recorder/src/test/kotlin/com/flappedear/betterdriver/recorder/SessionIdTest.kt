package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Session folders are named in the phone's own time, as Arek read it in the car (FBBD-217). */
class SessionIdTest {
    @TempDir
    lateinit var tmp: File

    private val start = 1_791_875_474_495L // 2026-10-13T07:11:14.495Z

    @Test
    fun idStartsWithTheLocalDateAndTime() {
        val (id, dir) = SessionStore(tmp) { ZoneId.of("Europe/Warsaw") }.createSession(SessionInfo(start))
        assertTrue(id.startsWith("20261013-091114-"), id)
        assertEquals(id, dir.name)
        val (west, _) = SessionStore(tmp) { ZoneId.of("America/New_York") }.createSession(SessionInfo(start))
        assertTrue(west.startsWith("20261013-031114-"), west)
    }

    /** The order comes from the start instant, never from the id, so a change of zone cannot reorder sessions. */
    @Test
    fun listIsOrderedByTheStartNotTheName() {
        // The later session is named in a zone that makes its id sort first.
        val (early, _) = SessionStore(tmp) { ZoneId.of("Asia/Tokyo") }.createSession(SessionInfo(start))
        val (late, _) = SessionStore(tmp) { ZoneId.of("America/New_York") }.createSession(SessionInfo(start + 60_000))
        assertTrue(late < early)
        assertEquals(listOf(late, early), SessionStore(tmp).list().map { it.id })
    }
}
