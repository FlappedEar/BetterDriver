package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.review.pickerSession
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.lapMark
import com.flappedear.betterdriver.timing.live.shownLapNumber
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FBBD-450: the out lap is no lap 1. The video overlay, the video's lap list, the lap review and the
 * track screen number laps as the session analysis does (the first full lap from the start/finish
 * line is lap 1), on the built-in demos, on the JVM (Android) and the iOS simulator alike.
 */
class LapNumbersTest {
    private fun demo(asset: String): Pair<SessionData, Track> {
        val track = TrackJson.decode(demoAsset("$asset.track.json"))
        val replay = SessionReplay.read(demoAsset("$asset.replay"), StartUtcMillis, RateHz)
        val loopFixes = demoAsset("$asset.replay").lineSequence().last { it.isNotBlank() }
            .substringBefore(' ').toDouble().times(RateHz).toLong()
        val sets = (0 until loopFixes).map { SampleSet(it * MicrosPerSecond / RateHz, replay.fix(it)) }
        val info = SessionInfo(StartUtcMillis, track = track.id, channels = GnssChannels.ALL)
        val data = SessionData(
            asset, info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets, emptyList(),
            null, 0, 0,
        )
        return data to track
    }

    private fun check(asset: String) {
        val (data, track) = demo(asset)
        val replayed = replayLaps(data.id, data.sampleSets, track).laps
        val analysis = analyzeSession(telemetrySessionOf(data, track), identity = track.identity)
        val origin = data.sampleSets.minOf { it.tMicros }

        // The replay's first lap runs from session start to the first line crossing: the out lap.
        val first = replayed.first().lap
        assertEquals(1, first.number)
        assertEquals(LapKind.Out, first.kind)
        assertEquals("OUT", lapMark(first.number, first.kind))

        // Every lap the analysis numbers has the same number on the video and in the lap review.
        var matched = 0
        for (lap in analysis.laps.timedLaps) {
            val start = origin + (lap.startTelemetryTime * MicrosPerSecond).toLong()
            val same = replayed.firstOrNull { abs(it.lap.startMicros - start) < SameLapMicros } ?: continue
            assertEquals(lap.number, shownLapNumber(same.lap.number), "$asset lap from ${lap.startTelemetryTime} s")
            matched++
        }
        assertTrue(matched >= analysis.laps.timedLaps.size - 1, "$asset: $matched of ${analysis.laps.timedLaps.size}")

        // The session's best lap has the same name in the analysis and in the lap picker.
        val best = analysis.laps.timedLaps[checkNotNull(analysis.laps.fastestLapIndex)]
        val picker = checkNotNull(pickerSession(data, track, "Demo", null))
        val pickedBest = picker.laps.single { it.number == picker.bestNumber }
        assertEquals("Lap ${best.number}", pickedBest.name, asset)
        assertEquals("Out lap", picker.laps.first().name)
        // Opened on video, a picked lap keeps its name (FBBD-450).
        assertEquals(pickedBest.name, pickedBest.onVideo().name)
    }

    @Test
    fun `Jastrzab demo laps have the analysis numbers everywhere`() = check("jastrzab")

    @Test
    fun `Silesia Ring demo laps have the analysis numbers everywhere`() = check("silesia-ring")

    private companion object {
        /** Both find the same line crossing to within a fix or two. */
        const val SameLapMicros = 500_000L
        const val StartUtcMillis = 1_790_000_000_000L
        const val RateHz = 10
        const val MicrosPerSecond = 1_000_000L
    }
}
