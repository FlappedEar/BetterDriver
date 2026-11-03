package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.offersVideo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Video entry points only for a session with video that is not recording (FBBD-426). */
class VideoOfferTest {
    @Test
    fun aSessionWithVideoOffersIt() {
        assertTrue(offersVideo(hasVideo = true, recording = false))
    }

    @Test
    fun aSessionWithoutVideoOffersNone() {
        assertFalse(offersVideo(hasVideo = false, recording = false))
    }

    @Test
    fun aRecordingSessionOffersNoneYet() {
        assertFalse(offersVideo(hasVideo = true, recording = true))
        assertFalse(offersVideo(hasVideo = false, recording = true))
    }
}
