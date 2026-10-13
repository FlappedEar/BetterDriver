package com.flappedear.betterdriver.smoke

import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.feature.review.Comparison
import com.flappedear.betterdriver.feature.review.LapReviewProbe
import com.flappedear.betterdriver.feature.review.ReviewChartTag
import com.flappedear.betterdriver.feature.review.ReviewLap
import com.flappedear.betterdriver.feature.review.ReviewLoad
import com.flappedear.betterdriver.review.ReviewLaps
import com.flappedear.betterdriver.timing.replay.LapTrace
import com.flappedear.betterdriver.timing.replay.replayLaps
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The lap review with two laps' videos from two sessions on the emulator (FBBD-24, FBBD-93,
 * FBBD-169), on [ReviewFixtures]' frame-numbered videos: what each player shows is read off its
 * surface (PixelCopy) and compared with the frame the session data puts at the review's cursor.
 *
 * - After a tap on the delta chart at 5 places along the lap, both players show the frame of their
 *   lap at the cursor within 1 frame (the wanted moment lies at most one frame outside the frame
 *   on screen), and each seek settles within 300 ms (FBBD-24).
 * - After play and pause at 5 places, the same holds (FBBD-93, FBBD-24).
 * - A paused, settled review runs no display frames (FBBD-169).
 */
@RunWith(AndroidJUnit4::class)
class ReviewSyncTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @Before
    fun seed() = SmokeData.seedReview()

    @After
    fun reset() = SmokeData.reset()

    @Test
    fun sideBySideVideosStayInSyncAndAPausedReviewIsIdle() {
        val a = bestLap(ReviewFixtures.SyncCarA)
        val b = bestLap(ReviewFixtures.SyncCarB)
        val c = Comparison(ReviewLap(a, ReviewFixtures.SyncCarA, null), ReviewLap(b, ReviewFixtures.SyncCarB, null))
        val frames = SurfaceFrames(app.activity)
        try {
            review(a, b, c, frames)
        } finally {
            frames.close()
        }
    }

    private fun review(a: LapTrace, b: LapTrace, c: Comparison, frames: SurfaceFrames) {
        app.openSessions()
        // The review keeps the laps it read earlier in the process and picks its track when it
        // first shows them, so it reads the new sessions before it opens.
        val laps = ReviewLaps.get(InstrumentationRegistry.getInstrumentation().targetContext)
        laps.load()
        app.waitNamed("the lap review to read the sync sessions", LoadTimeoutMs) {
            (laps.state as? ReviewLoad.Ready)?.tracks?.firstOrNull()?.id == ReviewFixtures.SyncRingId
        }
        // It opens on the lap picker with nothing picked: A is the newest session's best lap, B the
        // other session's.
        app.waitForText("Compare laps").performClick()
        pickLap(a)
        pickLap(b)
        // The review runs a frame loop while it moves, so the clock is advanced by hand while it shows.
        app.withManualClock {
            app.onNode(hasText("Compare") and hasClickAction()).performClick()
            app.pumpUntilText("Change laps", timeoutMs = LoadTimeoutMs)
            app.pumpUntilText("A  ${lapName(a)}")
            app.pumpUntilText("B  ${lapName(b)}")
            val players = Players(frames, a, b)
            // Opening: both at the start line. Not timed: the decoders start here.
            players.waitInSync(0L, "opening the review", SystemClock.uptimeMillis(), OpenTimeoutMs)

            // Seeks at other places first, checked but not timed: this debuggable build runs the
            // seek path interpreted until the JIT has seen it (a release build is installed
            // compiled), and on the emulator the first seeks took 400 ms, the fifth under 100.
            WarmUpFractions.forEach { f -> scrubTo(players, c, f) }
            val settles = ScrubFractions.map { f -> scrubTo(players, c, f) }
            check(settles.max() <= SeekSettleMs) {
                "a seek took longer than $SeekSettleMs ms to settle: ${settles.joinToString()} ms (FBBD-24)"
            }

            scrubTo(players, c, PlayFrom)
            var cursor = LapReviewProbe.cursorMicros
            repeat(PlayStops) { stop ->
                app.pumpUntilText("Play").performClick()
                playFor(PlayMs)
                app.pumpUntilText("Pause").performClick()
                app.pumpUntilText("Play")
                app.pumpFor(FrameMs * SettleFrames)
                val now = LapReviewProbe.cursorMicros
                check(now > cursor + MinPlayedMicros) {
                    "play ${stop + 1} did not move the cursor: $cursor → $now µs"
                }
                cursor = now
                players.waitInSync(cursor, "pause ${stop + 1} after play", SystemClock.uptimeMillis(), PauseTimeoutMs)
            }

            // FBBD-169: paused and on its frames, the review asks for no frames at all.
            app.pumpFor(FrameMs * SettleFrames)
            val before = LapReviewProbe.frames
            app.pumpFor(IdleCheckMs)
            val ran = LapReviewProbe.frames - before
            check(ran == 0L) { "the paused lap review ran $ran frames in $IdleCheckMs ms (FBBD-169)" }
            // And both players still show the frame they stopped on.
            players.waitInSync(cursor, "still paused", SystemClock.uptimeMillis(), PauseTimeoutMs)

            // To the picker, which runs no frame loop, before the clock runs by itself again.
            app.pumpBack()
            app.pumpUntilText("Compare laps")
        }
    }

    /** Taps [lap]'s row in the lap picker, into the slot that is next. */
    private fun pickLap(lap: LapTrace) {
        val row = hasText("Lap ${lap.lap.number}") and hasText(formatLapTime(lap.lap.timeMillis)) and hasClickAction()
        app.waitFor(hasScrollToNodeAction() and hasAnyDescendant(hasText("Compare")), LoadTimeoutMs)
            .performScrollToNode(row)
        app.onNode(row).performClick()
    }

    /**
     * Plays [ms] of the review's own clock, which moves the cursor, a display frame at a time. Not
     * [pumpFor]'s [ms] of wall time: on the emulator a frame of the playing review takes several
     * times its 16 ms, so 1.2 s of wall time moved the cursor 160 ms.
     */
    private fun playFor(ms: Long) = repeat((ms / FrameMs).toInt()) {
        app.mainClock.advanceTimeByFrame()
        Thread.sleep(FrameMs)
    }

    /**
     * Taps the chart at [fraction] of lap A's distance; returns how long both players took to show
     * the cursor's frames.
     *
     * Timed to when each player put its frame on screen (the release time it reports to
     * [LapReviewProbe]), not to when a picture copy first read it: each look at the two surfaces
     * takes the emulator tens of ms, which is the test's time, not the review's. A player that put
     * nothing new on screen since the tap counts no time, and with no release time to go by the
     * time of the copy counts.
     */
    private fun scrubTo(players: Players, c: Comparison, fraction: Double): Long {
        val cursor = c.microsAtDistance(fraction * c.a.trace.lengthMeters)
        val chart = app.onNodeWithTag(ReviewChartTag).performScrollTo()
        // The scroll (if any) and the last seek run their course before the timed tap.
        app.pumpFor(FrameMs * SettleFrames)
        val startNanos = System.nanoTime()
        val start = SystemClock.uptimeMillis()
        chart.performTouchInput { click(Offset((fraction * width).toFloat(), centerY)) }
        settleAtFrameRate()
        val what = "a tap at ${(fraction * Percent).toInt()} % of the lap"
        val polled = players.waitInSync(cursor, what, start, SeekTimeoutMs)
        val shown = LapReviewProbe.cursorMicros
        check(abs(shown - cursor) <= CursorToleranceMicros) {
            "a tap at $fraction put the cursor at $shown µs, the lap alignment says $cursor µs"
        }
        val released = (0 until Lanes).map { LapReviewProbe.lastReleaseNanos(it) }
        if (released.all { it == 0L }) return polled
        return released.maxOf { (it - startNanos).coerceAtLeast(0L) } / NanosPerMilli
    }

    /**
     * Runs display frames at the display's pace, nothing in between, until the review asks for none
     * (it stops its frame loop once both players are on their frames, FBBD-169), for at most
     * [SeekTimeoutMs].
     *
     * A seek takes the review several display frames: the tap's down seeks to a key frame, its
     * lift seeks to the exact frame once the frame clock says the last seek is far enough back, and
     * the frame after the seek watches it land. On a device those come every 16 ms. With the
     * manual clock they come only when the test runs them, and [Players.waitInSync] runs one per
     * pair of picture copies, tens of ms each on the emulator. That put the test's own pace into the
     * timed seek (FBBD-208: 303 ms against the 300 ms limit, the others 85–265 ms), so the timed
     * seek runs its frames here first and the picture copies only check where the players ended up.
     */
    private fun settleAtFrameRate() {
        val end = SystemClock.uptimeMillis() + SeekTimeoutMs
        var last = LapReviewProbe.frames
        var quiet = 0
        while (quiet < QuietFrames && SystemClock.uptimeMillis() < end) {
            app.mainClock.advanceTimeByFrame()
            Thread.sleep(FrameMs)
            val now = LapReviewProbe.frames
            if (now == last) quiet++ else quiet = 0
            last = now
        }
    }

    /**
     * The lap review's two players, A above B: the frame each shows against the frame its lap's data
     * puts at a cursor (time since the start line; a lap that has ended stays on its last frame).
     */
    private inner class Players(private val frames: SurfaceFrames, private val a: LapTrace, private val b: LapTrace) {
        private var views: Pair<SurfaceView, SurfaceView>? = null

        /**
         * Pumps frames until both players show [cursor]'s frames within 1 frame; returns the ms since
         * [start].
         */
        fun waitInSync(cursor: Long, what: String, start: Long, timeoutMs: Long): Long {
            val wantA = expectedFrame(a, cursor)
            val wantB = expectedFrame(b, cursor)
            while (true) {
                // The display frame first: on a device the next one comes within a frame of the tap,
                // and the review only seeks on a frame.
                app.mainClock.advanceTimeByFrame()
                Thread.sleep(FrameMs)
                val (va, vb) = views ?: surfaces()
                val fa = frames.frameOf(va)
                val fb = frames.frameOf(vb)
                if (shows(fa, wantA) && shows(fb, wantB)) {
                    return SystemClock.uptimeMillis() - start
                }
                if (SystemClock.uptimeMillis() - start > timeoutMs) {
                    val wants = "lap A shows frame $fa, its data wants %.1f; lap B shows $fb, wants %.1f"
                    throw AssertionError(
                        "$what: ${wants.format(wantA, wantB)} (cursor $cursor µs, after $timeoutMs ms)",
                    )
                }
            }
        }

        private fun surfaces(): Pair<SurfaceView, SurfaceView> {
            val found = frames.surfaces()
            check(found.size == 2) { "the lap review shows ${found.size} video surfaces, not 2" }
            return (found[0] to found[1]).also { views = it }
        }
    }

    private fun bestLap(car: String): LapTrace {
        val id = SmokeData.sessionOf(car)
        val laps = replayLaps(id, SmokeData.store().read(id).sampleSets, ReviewFixtures.syncRing)
        return checkNotNull(laps.best) { "the session of $car has no timed lap" }
    }

    private companion object {
        /** Places along lap A the chart is tapped at (FBBD-24: 5 landmarks or more). */
        val ScrubFractions = listOf(0.1, 0.3, 0.5, 0.7, 0.9)

        /** Untimed seeks before them, between them so none lands where a timed one does. */
        val WarmUpFractions = listOf(0.2, 0.4, 0.6, 0.8, 0.95)

        /** Where playback starts: far enough from the end that both laps still run after every stop. */
        const val PlayFrom = 0.15
        const val PlayStops = 5
        const val PlayMs = 1_200L
        const val MinPlayedMicros = 500_000L

        const val SeekSettleMs = 300L
        const val SeekTimeoutMs = 5_000L
        const val OpenTimeoutMs = 20_000L
        const val PauseTimeoutMs = 5_000L
        const val IdleCheckMs = 1_500L
        const val CursorToleranceMicros = 1_000L
        const val FrameMs = 16L
        const val SettleFrames = 10

        /** Display frames the review ran none in: it has stopped its frame loop. */
        const val QuietFrames = 3
        const val Percent = 100
        const val MicrosPerMilli = 1_000L
        const val NanosPerMilli = 1_000_000L

        /** Lap A's and lap B's players. */
        const val Lanes = 2
        const val MillisPerSecond = 1_000.0

        /** "Lap 7 · 1:41.89", as the review names a lap. */
        fun lapName(l: LapTrace) = "Lap ${l.lap.number} · ${formatLapTime(l.lap.timeMillis)}"

        /** Whether the frame [shown] (null: unreadable) is within 1 frame of [want]. */
        fun shows(shown: Int?, want: Double) = shown != null && framesOff(shown, want) <= 1.0

        /**
         * How many frames [want] (a frame position, fractional) lies outside the frame [shown], which
         * is on screen from [shown] up to [shown] + 1: 0 when it falls in it.
         */
        fun framesOff(shown: Int, want: Double): Double = when {
            want < shown -> shown - want
            want >= shown + 1 -> want - shown - 1
            else -> 0.0
        }

        /**
         * The frame of [lap]'s video at [cursor]: its session time (the clip starts with the session),
         * whole milliseconds as the player is sent there, at the fixture's frame rate.
         */
        fun expectedFrame(lap: LapTrace, cursor: Long): Double {
            val sessionMicros = lap.lap.startMicros + cursor.coerceIn(0L, lap.lap.timeMicros)
            return sessionMicros / MicrosPerMilli * ReviewFixtures.Fps / MillisPerSecond
        }
    }
}
