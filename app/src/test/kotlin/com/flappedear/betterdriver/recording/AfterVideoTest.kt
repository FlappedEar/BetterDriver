package com.flappedear.betterdriver.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stop's report waits for the camera's last file (FBBD-247). */
class AfterVideoTest {
    private val posted = mutableListOf<Pair<Runnable, Long>>()
    private val after = AfterVideo({ r, ms -> posted += r to ms }, { r -> posted.removeAll { it.first === r } })
    private var reports = 0

    @Test
    fun runsAtOnceWithNoFileOpen() {
        after.whenClosed("s1") { reports++ }
        assertEquals(1, reports)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun waitsForTheLastFileToClose() {
        // Arek's 2026-10-19 replay: the 2.7K file closed 0.9 s after the session stopped.
        after.report("s1", RecorderProtocol.VIDEO_STARTED, "phone_0001.mp4")
        after.whenClosed("s1") { reports++ }
        assertEquals(0, reports)
        after.report("s1", RecorderProtocol.VIDEO_STOPPED, "phone_0001.mp4")
        assertEquals(1, reports)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun aFullFileHandedOverIsNoClose() {
        after.report("s1", RecorderProtocol.VIDEO_STARTED, "phone_0001.mp4")
        after.report("s1", RecorderProtocol.VIDEO_STOPPED, "phone_0001.mp4")
        after.report("s1", RecorderProtocol.VIDEO_STARTED, "phone_0002.mp4")
        after.whenClosed("s1") { reports++ }
        assertEquals(0, reports)
        after.report("s1", RecorderProtocol.VIDEO_STOPPED, "phone_0002.mp4")
        assertEquals(1, reports)
    }

    @Test
    fun aHandOverAfterTheStopWaitsForTheNextFile() {
        // The file fills up just as the session stops: its stop report comes before the next file's start.
        after.report("s1", RecorderProtocol.VIDEO_STARTED, "phone_0001.mp4")
        after.whenClosed("s1") { reports++ }
        after.report("s1", RecorderProtocol.VIDEO_STOPPED, "phone_0001.mp4", "file_full")
        assertEquals(0, reports)
        after.report("s1", RecorderProtocol.VIDEO_STARTED, "phone_0002.mp4")
        after.report("s1", RecorderProtocol.VIDEO_STOPPED, "phone_0002.mp4", "user")
        assertEquals(1, reports)
    }

    @Test
    fun aCameraThatNeverSaysIsWaitedOutOnce() {
        after.report("s1", RecorderProtocol.VIDEO_STARTED, "phone_0001.mp4")
        after.whenClosed("s1") { reports++ }
        val (run, ms) = posted.single()
        assertEquals(VideoCloseWaitMs, ms)
        run.run()
        assertEquals(1, reports)
        // A stop report after the wait runs nothing again.
        after.report("s1", RecorderProtocol.VIDEO_STOPPED, "phone_0001.mp4")
        assertEquals(1, reports)
    }

    @Test
    fun anotherSessionsFilesDoNotHoldIt() {
        after.report("s0", RecorderProtocol.VIDEO_STARTED, "phone_0001.mp4")
        after.whenClosed("s1") { reports++ }
        assertEquals(1, reports)
    }
}
