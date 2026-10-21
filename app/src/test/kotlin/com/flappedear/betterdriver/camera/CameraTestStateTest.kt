package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.camera.CameraTestState.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraTestStateTest {
    @Test
    fun aRunningSecondPartReadsBack() {
        val state = CameraTestState(
            Status.Running,
            part = 2,
            partEndsAtMillis = 1_761_073_200_000,
            first = TestPart(57.23, 4.6),
            second = TestPart(59.9, 0.1),
        )
        assertEquals(state, CameraTestState.decode(state.encode()))
        assertEquals(false, state.withMp4)
    }

    @Test
    fun aFailureKeepsItsNoteOnOneLine() {
        val state = CameraTestState(Status.Failed, 1, note = "a session is\nrecording video")
        val back = CameraTestState.decode(state.encode())
        assertEquals(Status.Failed, back?.status)
        assertEquals("a session is recording video", back?.note)
        assertNull(back?.first)
    }

    @Test
    fun unreadableStateIsNone() {
        assertNull(CameraTestState.decode(""))
        assertNull(CameraTestState.decode("status=Paused\npart=1"))
        assertNull(CameraTestState.decode("status=Done"))
        assertNull("a broken part is left out", CameraTestState.decode("status=Done\npart=2\nfirst=57.1")?.first)
    }

    @Test
    fun partText() {
        assertEquals("camera 57.2 fps · skipped 3.1 %", TestPart(57.23, 3.06).text)
    }
}
