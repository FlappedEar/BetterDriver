package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.camera.FileCuts.Audio
import com.flappedear.betterdriver.camera.FileCuts.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileCutsTest {
    private val frameUs = 16_667L

    @Test
    fun framesBeforeTheFirstKeyframeAreDropped() {
        val cuts = FileCuts(cutBytes = 1_000)
        assertEquals(Video.Drop, cuts.video(0, 10, key = false, nextReady = false).action)
        assertEquals(Audio.Drop, cuts.audio(5_000, 4).action)
        assertEquals(Video.Start, cuts.video(frameUs, 100, key = true, nextReady = false).action)
        assertEquals(frameUs, cuts.baseUs)
        assertEquals(Audio.Drop, cuts.audio(frameUs - 1, 4).action)
        assertEquals(Audio.Current, cuts.audio(frameUs, 4).action)
    }

    @Test
    fun aFullFileAsksForAKeyframeOnceAndTheNextOneCuts() {
        val cuts = FileCuts(cutBytes = 300)
        cuts.video(0, 100, key = true, nextReady = false)
        assertFalse(cuts.video(frameUs, 100, key = false, nextReady = false).askKeyframe)
        val full = cuts.video(2 * frameUs, 100, key = false, nextReady = false)
        assertEquals(Video.Write, full.action)
        assertTrue("asked at 300 bytes", full.askKeyframe)
        assertFalse("asked once", cuts.video(3 * frameUs, 100, key = false, nextReady = true).askKeyframe)
        // A keyframe before the next file is known does not cut.
        assertEquals(Video.Write, cuts.video(4 * frameUs, 100, key = true, nextReady = false).action)
        val cut = cuts.video(5 * frameUs, 100, key = true, nextReady = true)
        assertEquals(Video.Cut, cut.action)
        assertEquals(5 * frameUs, cuts.baseUs)
        assertEquals(100L, cuts.bytes)
        assertFalse(cuts.due)
    }

    @Test
    fun soundBeforeTheCutGoesToTheFileThatEndedThenClosesIt() {
        val cuts = FileCuts(cutBytes = 100)
        cuts.video(0, 100, key = true, nextReady = true)
        cuts.video(frameUs, 10, key = false, nextReady = true)
        val cutUs = 2 * frameUs
        assertEquals(Video.Cut, cuts.video(cutUs, 10, key = true, nextReady = true).action)
        assertTrue(cuts.previousOpen)
        // Sound encoded a moment later but recorded before the keyframe belongs to the ended file.
        assertEquals(Audio.Previous, cuts.audio(cutUs - 1_000, 4).action)
        val after = cuts.audio(cutUs, 4)
        assertEquals(Audio.Current, after.action)
        assertTrue("the first sound after the cut closes the ended file", after.closePrevious)
        assertFalse(cuts.previousOpen)
        assertFalse(cuts.audio(cutUs + 21_000, 4).closePrevious)
    }

    @Test
    fun withoutSoundTheEndedFileClosesAfterASecondOfVideo() {
        val cuts = FileCuts(cutBytes = 100, closeAfterUs = 1_000_000)
        cuts.video(0, 100, key = true, nextReady = true)
        cuts.video(frameUs, 10, key = true, nextReady = true)
        assertTrue(cuts.previousOpen)
        assertFalse(cuts.video(500_000, 10, key = false, nextReady = true).closePrevious)
        assertTrue(cuts.video(frameUs + 1_000_000, 10, key = false, nextReady = true).closePrevious)
        assertFalse(cuts.previousOpen)
    }

    @Test
    fun aCutWhileTheFileBeforeIsStillOpenClosesItFirst() {
        val cuts = FileCuts(cutBytes = 10)
        cuts.video(0, 100, key = true, nextReady = true)
        cuts.video(frameUs, 100, key = true, nextReady = true)
        val second = cuts.video(2 * frameUs, 100, key = true, nextReady = true)
        assertEquals(Video.Cut, second.action)
        assertTrue(second.closePrevious)
    }

    @Test
    fun bothTracksCountTowardTheFile() {
        val cuts = FileCuts(cutBytes = 1_000)
        cuts.video(0, 600, key = true, nextReady = true)
        cuts.audio(1_000, 300)
        assertEquals(900L, cuts.bytes)
        assertFalse(cuts.due)
        cuts.audio(2_000, 100)
        assertTrue(cuts.due)
    }
}
