package com.flappedear.betterdriver.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/** FBBD-470 (review F7): a camera process that dies mid-recording is noticed once. */
class CameraProcessWatchTest {
    private val deaths = mutableMapOf<String, () -> Unit>()
    private val unlinked = mutableListOf<String>()
    private var gone = 0
    private val watch = CameraProcessWatch<String>(
        link = { sender, died ->
            deaths[sender] = died
            ({ unlinked += sender })
        },
        post = { it() },
        gone = { gone++ },
    )

    @Test
    fun theCameraProcessDyingDuringARecordingIsNoticedOnce() {
        watch.reported("camera")
        watch.reported("camera") // every report names it; it is linked once
        assertEquals(setOf("camera"), deaths.keys)
        deaths.getValue("camera")()
        assertEquals(1, gone)
        assertEquals(listOf("camera"), unlinked)
        // A late second notice of the same death says nothing new.
        deaths.getValue("camera")()
        assertEquals(1, gone)
    }

    @Test
    fun aDeathAfterTheRecordingEndedIsNotAFault() {
        watch.reported("camera")
        watch.forget() // the recording ended; the camera process may exit as it likes
        deaths.getValue("camera")()
        assertEquals(0, gone)
        assertEquals(listOf("camera"), unlinked)
    }

    @Test
    fun aNewCameraProcessReplacesTheOldOne() {
        watch.reported("old")
        watch.reported("new")
        assertEquals(listOf("old"), unlinked)
        deaths.getValue("old")()
        assertEquals("the old process is no longer the session's camera", 0, gone)
        deaths.getValue("new")()
        assertEquals(1, gone)
    }

    @Test
    fun aReportWithoutASenderIsIgnored() {
        watch.reported(null)
        assertEquals(emptyMap<String, () -> Unit>(), deaths)
    }
}
