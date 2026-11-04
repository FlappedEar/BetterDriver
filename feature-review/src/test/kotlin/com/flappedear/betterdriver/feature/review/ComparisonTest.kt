@file:Suppress("MagicNumber", "LongParameterList")

package com.flappedear.betterdriver.feature.review

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.LapTrace
import com.flappedear.betterdriver.timing.replay.TracePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** FBBD-93 on a straight 1,000 m "lap": A at 50 m/s (20 s), B at 40 m/s (25 s). */
class ComparisonTest {
    private fun lap(
        session: String,
        number: Int,
        metersPerSecond: Double,
        startMicros: Long,
        kind: LapKind = LapKind.Timed,
        video: SessionVideo? = null,
    ): ReviewLap {
        val seconds = LengthMeters / metersPerSecond
        val steps = (seconds * 10).toInt()
        val points = (0..steps).map { i ->
            val t = Math.round(i * seconds / steps * 1e6)
            val d = LengthMeters * i / steps
            TracePoint(startMicros + t, t, 0.0, 0.0, Xy(d, 0.0), d, metersPerSecond * 3.6)
        }
        val time = points.last().lapMicros
        return ReviewLap(
            LapTrace(session, Lap(number, kind, startMicros, startMicros + time, listOf(time)), points),
            "Session $session",
            video,
        )
    }

    @Test
    fun chartIsByPlaceAndPlaybackByTime() {
        val c = Comparison(lap("a", 2, 50.0, 100_000_000), lap("b", 3, 40.0, 900_000_000))
        // Scrubbing goes to when the later car is there, so the slower lap's end is reachable.
        assertEquals(12_500_000.0, c.microsAtDistance(500.0).toDouble(), 1_000.0)
        assertEquals(25_000_000.0, c.microsAtDistance(LengthMeters).toDouble(), 1_000.0)
        val last = c.series.last()
        assertEquals(LengthMeters, last.distanceMeters, 1e-9)
        assertEquals(5_000_000L, last.deltaMicros)
        assertEquals(180.0, last.speedA!!, 1e-9)
        assertEquals(144.0, last.speedB!!, 1e-9)
        // Both start at the line together; the review runs until the slower lap ends.
        assertEquals(25_000_000L, c.lengthMicros)
        assertEquals(0.0, c.gapMetersAt(0), 1e-9)
        // 10 s in: A at 500 m, B at 400 m.
        assertEquals(0.5, c.aFraction(10_000_000), 1e-3)
        assertEquals(0.4, c.bFraction(10_000_000), 1e-3)
        assertEquals(100.0, c.gapMetersAt(10_000_000), 0.5)
        // A finished at 20 s and waits at the line; B (880 m) is 120 m behind.
        assertEquals(20_000_000L, c.aMicros(22_000_000))
        assertEquals(22_000_000L, c.bMicros(22_000_000))
        assertEquals(120.0, c.gapMetersAt(22_000_000), 0.5)
        assertEquals(0.0, c.gapMetersAt(25_000_000), 0.5)
    }

    @Test
    fun videoTargetIsTheClipAtTheLapTime() {
        val start = 100_000_000L
        val clip = VideoClip("phone", "phone_0001.mp4", start - 2_000_000, start + 10_000_000)
        val video = SessionVideo("phone", listOf(clip, VideoClip("gopro_hero11", "g.mp4", 0, null)), File("v"))
        val l = lap("a", 2, 50.0, start, video = video)
        val target = l.videoAt(5_000_000)!!
        assertEquals("phone_0001.mp4", target.clip.file)
        assertEquals(7_000L, target.mediaMillis)
        assertNull("past the clip's end", l.videoAt(15_000_000))
        assertEquals(true, l.hasVideo)
        assertEquals(false, lap("b", 1, 50.0, start + 60_000_000, video = video).hasVideo)
    }

    @Test
    fun followSeeksWhenPausedAndCorrectsSpeedWhenPlaying() {
        assertEquals(FollowStep(1_000, 1f), followStep(900, 1_000, 1.0, playing = false))
        assertEquals(FollowStep(null, 1f), followStep(990, 1_000, 1.0, playing = false))
        assertEquals(FollowStep(5_000, 1.25f), followStep(1_000, 5_000, 1.25, playing = true))
        assertEquals(FollowStep(null, 1.25f), followStep(1_010, 1_000, 1.25, playing = true))
        // 200 ms ahead: slow down by a fifth until it is back.
        assertEquals(0.8f, followStep(1_200, 1_000, 1.0, playing = true).speed, 1e-6f)
        assertEquals(1.2f, followStep(800, 1_000, 1.0, playing = true).speed, 1e-6f)
    }

    @Test
    fun theDriftCorrectionMovesInStepsSoTheRateIsNotSetEveryFrame() {
        // FBBD-429: drift that creeps by a few ms a frame keeps the same speed, so the player's
        // rate is not set again on every display frame.
        val speeds = (100..140).map { followStep(1_000 + it.toLong(), 1_000, 1.0, playing = true).speed }.distinct()
        assertEquals(listOf(0.9f, 0.88f, 0.86f), speeds.map { Math.round(it * 100) / 100f })
        speeds.forEach { assertEquals(0f, (it * 100 - Math.round(it * 100)), 1e-4f) }
        // Just outside the deadband it still corrects, the smallest step.
        assertEquals(0.96f, followStep(1_031, 1_000, 1.0, playing = true).speed, 1e-6f)
        assertEquals(1.04f, followStep(969, 1_000, 1.0, playing = true).speed, 1e-6f)
        // At half speed the steps scale with it.
        assertEquals(0.4f, followStep(1_200, 1_000, 0.5, playing = true).speed, 1e-6f)
        // A new rate at most every half second, except when it starts playing or the speed changes.
        assertTrue(rateChangeDue(null, 0.9f, 0))
        assertFalse(rateChangeDue(1f, 0.9f, MinRateGapMillis - 1))
        assertTrue(rateChangeDue(1f, 0.9f, MinRateGapMillis))
        assertFalse(rateChangeDue(0.9f, 0.9f, 10_000))
    }

    @Test
    fun aPlayerThatJustStoppedSeeksItsExactFrame() {
        // FBBD-214: 10 ms from the target while it played, but it plays on until the pause lands.
        assertEquals(FollowStep(1_000, 1f), followStep(990, 1_000, 1.0, playing = false, justStopped = true))
        assertEquals(FollowStep(1_000, 1f), followStep(1_000, 1_000, 1.0, playing = false, justStopped = true))
        // Once sent there it stays put.
        assertEquals(FollowStep(null, 1f), followStep(1_000, 1_000, 1.0, playing = false))
        // Playing again, it does not seek for that.
        assertEquals(FollowStep(null, 1f), followStep(1_000, 1_000, 1.0, playing = true, justStopped = true))
    }

    @Test
    fun clockStopsAtTheEndOfTheLap() {
        assertEquals(1_500_000L, advance(1_000_000, 1_000_000_000, 0.5, 20_000_000))
        assertEquals(20_000_000L, advance(19_900_000, 1_000_000_000, 1.0, 20_000_000))
    }

    @Test
    fun lapIsFoundByKey() {
        val newest = ReviewSession("new", "Today", "", listOf(lap("new", 2, 45.0, 0), lap("new", 3, 48.0, 0)), 2)
        val track = ReviewTrack("t", "Track", listOf(newest))
        assertEquals(LapKey("new", 3), track.lap(LapKey("new", 3))!!.key)
        assertNull(track.lap(LapKey("new", 4)))
    }

    @Test
    fun lapKeysRoundTrip() {
        assertEquals(LapKey("20261011-120000-abcd", 7), LapKey.decode(LapKey("20261011-120000-abcd", 7).encode()))
        assertNull(LapKey.decode("nonsense"))
        assertNull(LapKey.decode(null))
    }

    private companion object {
        const val LengthMeters = 1_000.0
    }

    @Test
    fun thePlayerWithTheSoundLeavesSmallDriftAloneAndTheStatsLineSaysWhatPlayed() {
        // 100 ms off: the muted player corrects, the one with the sound does not (FBBD-429).
        assertEquals(0.9f, followStep(1_100, 1_000, 1.0, playing = true).speed, 1e-6f)
        assertEquals(
            FollowStep(null, 1f),
            followStep(1_100, 1_000, 1.0, playing = true, deadbandMillis = SoundDeadbandMillis),
        )
        val sound140 = followStep(1_140, 1_000, 1.0, playing = true, deadbandMillis = SoundDeadbandMillis)
        assertEquals(0.86f, sound140.speed, 1e-6f)
        // Beyond the drift limit both seek.
        val sound400 = followStep(1_400, 1_000, 1.0, playing = true, deadbandMillis = SoundDeadbandMillis)
        assertEquals(1_000L, sound400.seekMillis)

        val stats = LapReviewStats()
        assertEquals(null, stats.line(0))
        stats.frame(2_000)
        stats.frame(20_500)
        stats.rateChanged()
        stats.seekedWhilePlaying()
        stats.startedSynced()
        stats.drift(-45)
        stats.drift(12)
        assertEquals(
            "played 2.5 s: 2 frames, work mean 11.2 max 20.5 ms, 1 slow, 1 rate changes, " +
                "1 seeks while playing, 1 synced starts, max drift 45 ms",
            stats.line(2_500),
        )
        stats.reset()
        assertEquals(null, stats.line(1_000))
    }
}
