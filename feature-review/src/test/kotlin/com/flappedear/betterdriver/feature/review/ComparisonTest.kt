@file:Suppress("MagicNumber", "LongParameterList")

package com.flappedear.betterdriver.feature.review

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.LapTrace
import com.flappedear.betterdriver.timing.replay.TracePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
