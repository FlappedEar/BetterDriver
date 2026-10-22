package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.recording.CameraFinishWaitMs
import com.flappedear.betterdriver.recording.VideoCloseWaitMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit A13: the camera service's end after a stop, on a fake clock. A camera whose last file takes
 * longer than the old 5 s grace keeps the service (its foreground, its recorder link, and with them
 * the final report and the next session's start) until it is done, or until the timeout.
 */
class VideoEndTest {
    private var now = 0L
    private val due = mutableListOf<Pair<Runnable, Long>>()
    private val end = VideoEnd({ r, ms -> due += r to now + ms }, { r -> due.removeAll { it.first === r } })

    /** A camera that finishes only when told to, as a large MP4 that takes long to finish. */
    private var finish: (() -> Unit)? = null
    private val outcomes = mutableListOf<Boolean>()

    private fun advanceTo(ms: Long) {
        now = ms
        due.filter { it.second <= now }.forEach { (r, _) ->
            due.removeAll { it.first === r }
            r.run()
        }
    }

    private fun stop() = end.begin({ finished -> finish = finished }) { timedOut -> outcomes += timedOut }

    @Test
    fun slowFinishKeepsTheServiceUntilTheCameraIsDone() {
        stop()
        advanceTo(5_000)
        advanceTo(30_000)
        assertTrue("still waiting 30 s after the stop", end.waiting)
        assertEquals("the service has not ended", emptyList<Boolean>(), outcomes)

        // The report goes out before this callback (PhoneCamera), so the service still has the recorder.
        checkNotNull(finish).invoke()
        assertEquals("ended once, not timed out", listOf(false), outcomes)
        assertFalse(end.waiting)
        advanceTo(CameraFinishWaitMs + 1)
        assertEquals("the timeout does not end it again", listOf(false), outcomes)
    }

    @Test
    fun cameraThatNeverFinishesEndsAtTheTimeoutOnce() {
        stop()
        advanceTo(CameraFinishWaitMs - 1)
        assertEquals(emptyList<Boolean>(), outcomes)
        advanceTo(CameraFinishWaitMs)
        assertEquals("ended at the timeout", listOf(true), outcomes)
        checkNotNull(finish).invoke()
        assertEquals("a late finish is ignored", listOf(true), outcomes)
    }

    @Test
    fun nothingRecordingEndsAtOnce() {
        end.begin({ finished -> finished() }) { timedOut -> outcomes += timedOut }
        assertEquals(listOf(false), outcomes)
        assertTrue("no timeout left behind", due.isEmpty())
    }

    @Test
    fun abandonedWaitNeverEnds() {
        stop()
        end.abandon()
        advanceTo(CameraFinishWaitMs)
        checkNotNull(finish).invoke()
        assertEquals(emptyList<Boolean>(), outcomes)
    }

    @Test
    fun timeoutCoversTheEncodersLongestFinishAndTheRecorderWaitsLonger() {
        // WideEncoder.stop: up to 10 s for each encoder, then up to 60 s for a handed-over file.
        assertTrue(CameraFinishWaitMs > 2 * 10_000 + 60_000)
        assertTrue(VideoCloseWaitMs > CameraFinishWaitMs)
    }
}
