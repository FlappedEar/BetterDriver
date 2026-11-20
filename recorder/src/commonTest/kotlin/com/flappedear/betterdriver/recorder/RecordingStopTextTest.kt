package com.flappedear.betterdriver.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingStopTextTest {
    @Test
    fun `a write failure never says the session is saved, review F9`() {
        val text = RecordingStopText.ended("error")!!
        assertFalse("saved" in text, text)
        assertTrue("recovered" in text, text)
        assertEquals("Storage full. The session is saved.", RecordingStopText.ended("storage_full"))
        assertNull(RecordingStopText.ended("user"))
    }
}
