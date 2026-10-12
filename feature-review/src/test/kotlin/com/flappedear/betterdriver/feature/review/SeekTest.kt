package com.flappedear.betterdriver.feature.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a lap video seeks (FBBD-93), and when the review needs frames at all. */
class SeekTest {
    @Test
    fun firstFrameIsSoughtWhenTheExactFrameIsWanted() {
        // Media time 0: the start of a clip, as when the finger lifts at the start line.
        assertTrue(seekDue(0, null))
        assertTrue(seekDue(12_345, null))
    }

    @Test
    fun smallMovesAreLeftToThePlayer() {
        assertFalse(seekDue(0, 0))
        assertFalse(seekDue(1_020, 1_000))
        assertTrue(seekDue(1_021, 1_000))
        assertTrue(seekDue(979, 1_000))
    }

    @Test
    fun aSeekIsPendingUntilItIsSent() {
        // On its frame: nothing to do.
        assertFalse(seekPending(FollowStep(null, 1f), 1_000))
        // Sent there already, within the tolerance.
        assertFalse(seekPending(FollowStep(1_010, 1f), 1_000))
        // A new frame, or the exact frame again after a scrub.
        assertTrue(seekPending(FollowStep(2_000, 1f), 1_000))
        assertTrue(seekPending(FollowStep(1_000, 1f), null))
    }

    @Test
    fun theFrameLoopRunsOnlyWhileTheReviewMoves() {
        assertFalse(frameLoopNeeded(playing = false, scrubbing = false, seekPending = false))
        assertTrue(frameLoopNeeded(playing = true, scrubbing = false, seekPending = false))
        assertTrue(frameLoopNeeded(playing = false, scrubbing = true, seekPending = false))
        assertTrue(frameLoopNeeded(playing = false, scrubbing = false, seekPending = true))
    }
}
