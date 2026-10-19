@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.TrackJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The coach on the Silesia Ring replay built into the app (FBBD-237), as Arek drove it on the Demo
 * track on 19 Oct 2026 at Full: Arek's real laps, some out of the pit lane, some lifted off for
 * traffic or to cool down. Laps 6 and 13 start out of the pit lane at 16 and 49 km/h; laps 10 and
 * 16 lift off from race pace just past the line.
 */
class DemoReplayCoachTest {
    private val cues: List<Cue> by lazy {
        val dir = File("../app/src/main/assets/demo")
        val track = TrackJson.decode(File(dir, "silesia-ring.track.json").readText())
        val replay = SessionReplay.read(File(dir, "silesia-ring.replay").readText(), 0L)
        val engine = CueEngine(track, CoachSettings(verbosity = Verbosity.Full, audioDelayMillis = 120))
        (0L until Minutes * 60 * replay.rateHz).flatMap { n ->
            val t = n * 1_000_000L / replay.rateHz
            val values = replay.fix(n)
            val fix = SampleSet(t, values).toFix() ?: return@flatMap emptyList()
            val kmh = values.firstOrNull { it.channelId == GnssChannels.SPEED_KMH.id }?.value
            engine.update(CoachFix(t, fix.lat, fix.lon, kmh)).cues
        }
    }

    private val corner get() = cues.filter { it.kind == CueKind.Corner }

    @Test
    fun `getting up to speed out of the pit lane is not cooling down`() {
        assertTrue(corner.any { it.lap == 6 }, "lap 6 is coached: ${corner.map { it.lap }}")
    }

    @Test
    fun `no advice is measured against a lap driven cooling down`() {
        val from = corner.mapNotNull { it.before?.lap }
        assertTrue(from.none { it == 10 || it == 16 }, "advice from laps $from")
    }

    @Test
    fun `a lap time is said when the next lap is lifted off`() {
        val times = cues.filter { it.kind == CueKind.LapTime }.map { it.lap }
        assertTrue(9 in times && 16 in times, "lap times said for laps $times")
    }

    private companion object {
        const val Minutes = 42L
    }
}
