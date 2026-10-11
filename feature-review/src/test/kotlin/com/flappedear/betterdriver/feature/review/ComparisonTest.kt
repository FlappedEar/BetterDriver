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
    fun lapBFollowsLapAByPlace() {
        val c = Comparison(lap("a", 2, 50.0, 100_000_000), lap("b", 3, 40.0, 900_000_000))
        assertEquals(12_500_000.0, c.bMicrosAt(10_000_000).toDouble(), 1_000.0)
        assertEquals(1.25, c.bRateAt(10_000_000), 0.01)
        assertEquals(500.0, c.distanceAt(10_000_000), 0.5)
        assertEquals(10_000_000.0, c.aMicrosAtDistance(500.0).toDouble(), 1_000.0)
        val last = c.series.last()
        assertEquals(LengthMeters, last.distanceMeters, 1e-9)
        assertEquals(5_000_000L, last.deltaMicros)
        assertEquals(180.0, last.speedA!!, 1e-9)
        assertEquals(144.0, last.speedB!!, 1e-9)
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
    fun clockStopsAtTheEndOfTheLap() {
        assertEquals(1_500_000L, advance(1_000_000, 1_000_000_000, 0.5, 20_000_000))
        assertEquals(20_000_000L, advance(19_900_000, 1_000_000_000, 1.0, 20_000_000))
    }

    @Test
    fun defaultPairIsNewestBestAgainstFastestOther() {
        val newest = ReviewSession(
            "new", "Today", "",
            listOf(lap("new", 1, 30.0, 0, LapKind.Out), lap("new", 2, 45.0, 0), lap("new", 3, 48.0, 0)),
            3,
        )
        val older = ReviewSession("old", "Last week", "", listOf(lap("old", 2, 50.0, 0), lap("old", 3, 47.0, 0)), 2)
        val track = ReviewTrack("t", "Track", listOf(newest, older))
        assertEquals(LapKey("new", 3) to LapKey("old", 2), track.defaultPair())
        val alone = ReviewTrack("t", "Track", listOf(newest.copy(laps = newest.laps.take(2))))
        assertNull(alone.defaultPair())
        assertEquals(LapKey("new", 3), track.lap(LapKey("new", 3))!!.key)
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
