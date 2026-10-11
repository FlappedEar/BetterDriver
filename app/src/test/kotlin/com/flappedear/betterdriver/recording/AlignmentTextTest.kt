package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class AlignmentTextTest {
    @Test
    fun describesHowAClipWasPlaced() {
        assertEquals("by GPS ±0.05 s", alignmentText(Alignment(AlignmentMethod.GPS_MATCH, 50_000)))
        assertEquals("by camera clock ±5 s", alignmentText(Alignment(AlignmentMethod.CAMERA_CLOCK, 5_000_000)))
    }
}
