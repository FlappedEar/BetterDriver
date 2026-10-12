package com.flappedear.betterdriver.smoke

import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        // The review runs a frame loop while it moves, so the clock is advanced by hand while it shows.
        app.withManualClock {
            app.waitForText("Compare laps").performClick()
            app.pumpUntilText("Change laps", timeoutMs = LoadTimeoutMs)
            // It opens on the newest session's best lap (A) against the track's fastest other lap (B's).
            app.pumpUntilText("A  ${lapName(a)}")
            app.pumpUntilText("B  ${lapName(b)}")
            val players = Players(frames, a, b)
            // Opening: both at the start line. Not timed: the decoders start here.
            players.waitInSync(0L, "opening the review", SystemClock.uptimeMillis(), OpenTimeoutMs)

            val settles = ScrubFractions.map { f -> scrubTo(players, c, f) }
            check(settles.max() <= SeekSettleMs) {
                "a seek took longer than $SeekSettleMs ms to settle: ${settles.joinToString()} ms (FBBD-24)"
            }

            scrubTo(players, c, PlayFrom)
            var cursor = LapReviewProbe.cursorMicros
            repeat(PlayStops) { stop ->
                app.pumpUntilText("Play").performClick()
                app.pumpFor(PlayMs)
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

    /**
     * Taps the chart at [fraction] of lap A's distance; returns how long both players took to show
     * the cursor's frames.
     */
    private fun scrubTo(players: Players, c: Comparison, fraction: Double): Long {
        val cursor = c.microsAtDistance(fraction * c.a.trace.lengthMeters)
        val chart = app.onNodeWithTag(ReviewChartTag).performScrollTo()
        // The scroll (if any) and the last seek run their course before the timed tap.
        app.pumpFor(FrameMs * SettleFrames)
        val start = SystemClock.uptimeMillis()
        chart.performTouchInput { click(Offset((fraction * width).toFloat(), centerY)) }
        val what = "a tap at ${(fraction * Percent).toInt()} % of the lap"
        val settled = players.waitInSync(cursor, what, start, SeekTimeoutMs)
        val shown = LapReviewProbe.cursorMicros
        check(abs(shown - cursor) <= CursorToleranceMicros) {
            "a tap at $fraction put the cursor at $shown µs, the lap alignment says $cursor µs"
        }
        return settled
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
                app.mainClock.advanceTimeByFrame()
                Thread.sleep(FrameMs)
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
        const val Percent = 100
        const val MicrosPerMilli = 1_000L
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
