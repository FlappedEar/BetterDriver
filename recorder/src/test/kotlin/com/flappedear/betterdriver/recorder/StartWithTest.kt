package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Audit 30 Oct BD-03: the recorder service's start fails after the writer began (tracker, circuits,
 * timing, coach, end, device checks, carry-on, sources). A fault at each step stops the recording:
 * its writer ends, the session is finished and unlocked, and the next start works.
 */
@Timeout(60)
class StartWithTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()
    private val info = SessionInfo(
        startUtcMillis = gnss.startUtcMillis,
        devices = listOf("fake-gnss"),
        channels = GnssChannels.ALL,
    )

    @Test
    fun `a fault at any step of the start stops the started recording`() {
        val store = SessionStore(tmp)
        val steps = 8
        for (failAt in 0 until steps) {
            var undone: Recording? = null
            var started: Recording? = null
            val fault = assertFailsWith<IllegalStateException> {
                Recorder(store).startWith(info, undo = { undone = it.also { r -> r.stop("start_failed") } }) { rec ->
                    started = rec
                    for (step in 0 until steps) {
                        rec.submit(SampleSet(step * 40_000L, gnss.fix(step.toLong())))
                        check(step != failAt) { "fault at step $step" }
                    }
                }
            }
            assertEquals("fault at step $failAt", fault.message)
            val rec = checkNotNull(started)
            assertSame(rec, undone, "step $failAt: the started recording is undone")
            assertTrue(rec.isStopped, "step $failAt: its writer has stopped")
            assertEquals(SessionState.FINISHED, store.state(rec.dir), "step $failAt")
            assertEquals("start_failed", rec.finalStatus?.reason)
        }
        // No lock left behind: a new recording starts and ends normally.
        val next = Recorder(store).startWith(info, undo = { error("not undone") }) { it }
        assertTrue(next.stop())
        assertEquals(SessionState.FINISHED, store.state(next.dir))
    }

    @Test
    fun `a start that does not fail is not undone`() {
        var undone: Recording? = null
        val rec = Recorder(SessionStore(tmp)).startWith(info, undo = { undone = it }) { it }
        assertNull(undone)
        assertTrue(rec.stop())
    }
}
