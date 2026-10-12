package com.flappedear.betterdriver

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteTrackTextTest {
    @Test
    fun `the question names the track and says sessions stay`() {
        assertEquals(
            "Tor Poznań: its lines and coach reference will be removed from this phone. " +
                "Sessions recorded there stay. This can't be undone.",
            deleteTrackText("Tor Poznań"),
        )
    }
}
