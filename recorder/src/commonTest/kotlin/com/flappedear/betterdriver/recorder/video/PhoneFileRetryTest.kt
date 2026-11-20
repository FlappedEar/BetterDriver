package com.flappedear.betterdriver.recorder.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-441 and FBBD-442: the phone camera never gives up after failed files; it backs off while they fail. */
class PhoneFileRetryTest {
    @Test
    fun `the camera keeps starting files and backs off while they fail`() {
        val retry = PhoneFileRetry()
        assertTrue(retry.mayOpen(at = 0.0))
        // The 3 Nov iPhone pattern: files failing after 10 s to 4 min.
        retry.failed(startedAt = 0.0, now = 30.0)
        assertEquals(0.0, retry.wait)
        assertTrue(retry.mayOpen(at = 30.0), "the first file after a failure starts at once")
        retry.failed(startedAt = 30.0, now = 40.0)
        assertEquals(2.0, retry.wait)
        assertFalse(retry.mayOpen(at = 41.0))
        assertTrue(retry.mayOpen(at = 42.0))
        retry.failed(startedAt = 42.0, now = 45.0)
        assertEquals(4.0, retry.wait)
        retry.failed(startedAt = 49.0, now = 50.0)
        retry.failed(startedAt = 58.0, now = 59.0)
        retry.failed(startedAt = 75.0, now = 76.0)
        retry.failed(startedAt = 108.0, now = 109.0)
        assertEquals(PhoneFileRetry.LONGEST_WAIT, retry.wait, "the wait stops growing at 30 s")
        assertTrue(retry.mayOpen(at = 139.0), "a fourth or tenth or any file still starts")
        // A file that recorded for a minute ends the run: the next failure restarts at once.
        retry.failed(startedAt = 139.0, now = 139.0 + PhoneFileRetry.STEADY_SECONDS)
        assertEquals(1, retry.inARow)
        assertEquals(0.0, retry.wait)
        assertEquals(8, retry.total)
    }

    @Test
    fun `a hundred failures in a row never stop the camera`() {
        val retry = PhoneFileRetry()
        var now = 0.0
        repeat(100) {
            assertTrue(retry.mayOpen(at = now))
            retry.failed(startedAt = now, now = now + 1)
            now += 1 + retry.wait
        }
        assertEquals(100, retry.total)
        assertEquals(PhoneFileRetry.LONGEST_WAIT, retry.wait)
    }

    @Test
    fun `android camera faults raise the phone video alert`() {
        assertEquals(PhoneVideoAlert.RETRYING, PhoneVideoAlert.detail("Restarting"))
        assertEquals(PhoneVideoAlert.OFF, PhoneVideoAlert.detail("Camera error"))
        assertEquals(PhoneVideoAlert.OFF, PhoneVideoAlert.detail("Not supported"))
        // Recording or stopped on purpose (heat or storage have their own lines): no fault alert.
        assertNull(PhoneVideoAlert.detail("1080p · 60"))
        assertNull(PhoneVideoAlert.detail("Stopped: too hot"))
        assertNull(PhoneVideoAlert.detail(null))
    }
}
