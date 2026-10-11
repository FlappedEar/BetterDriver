package com.flappedear.betterdriver.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteTextTest {
    @Test
    fun `sizes read in the largest fitting unit`() {
        assertEquals("1 KB", sizeText(10))
        assertEquals("820 KB", sizeText(820_400))
        assertEquals("350 MB", sizeText(350_900_000))
        assertEquals("2.4 GB", sizeText(2_400_000_000))
    }

    @Test
    fun `the question names the session, the video and the space freed`() {
        assertEquals(
            "Sat 11 Oct · Tor Poznań: its data and video (2.4 GB) will be removed from this phone. " +
                "Files still on a GoPro stay there. This can't be undone.",
            deleteText("Sat 11 Oct · Tor Poznań", 2_400_000_000, video = true),
        )
        assertEquals(
            "Sat 11 Oct: its data will be removed from this phone. " +
                "Files still on a GoPro stay there. This can't be undone.",
            deleteText("Sat 11 Oct", null, video = false),
        )
    }
}
