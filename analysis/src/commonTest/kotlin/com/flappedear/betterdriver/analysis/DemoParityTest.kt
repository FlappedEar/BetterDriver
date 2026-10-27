package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.line.linePlan
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The demo sessions analysed by the shared core give the same laps and line plan top 3 on the JVM
 * (Android) and on the iOS simulator (FBBD-330): the same test with the same expected values runs on
 * both. The app's built-in replays are driven once round their loop and read as a recorded session.
 */
class DemoParityTest {
    private fun summary(asset: String): String {
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
        val session = telemetrySessionOf(data, track)
        val analysis = analyzeSession(session, identity = track.identity)
        val laps = analysis.laps.timedLaps.joinToString(" ") { "${it.number}:${fixed(it.durationSeconds, 3)}" }
        val best = analysis.theoreticalBest?.totalSeconds?.let { fixed(it, 3) }
        val line = checkNotNull(analyzeLine(analysis, session)) { "no line analysis of $asset" }
        val plan = linePlan(line, checkNotNull(analysis.axis).origin, track.id, asset, StartUtcMillis, null)
        val focus = plan.focus().joinToString(" ") { "${it.name}:${fixed(it.gainSeconds, 3)}" }
        return "laps $laps | best ${analysis.axisLapNumber} | theoretical $best | focus $focus"
    }

    @Test
    fun `Jastrzab demo gives the same laps and line plan on every platform`() {
        val laps = listOf(
            "144.477", "142.519", "138.714", "141.850", "145.216", "143.247", "148.387", "138.544",
            "137.706", "130.494", "124.058", "124.254", "123.142", "124.203", "115.928", "113.295",
            "115.509", "109.906", "112.583", "132.558", "129.022", "115.476", "116.271",
        ).mapIndexed { i, t -> "${i + 1}:$t" }.joinToString(" ")
        assertEquals(
            // Worked out on the JVM; iOS must give the same.
            "laps $laps | best 18 | theoretical 108.201 | focus Corner 11:0.281 Corner 1:0.112 Corner 5:0.000",
            summary("jastrzab"),
        )
    }

    @Test
    fun `Silesia Ring demo gives the same laps and line plan on every platform`() {
        val laps = listOf(
            "144.459", "142.205", "146.890", "148.106", "166.542", "141.871", "155.273", "138.431",
            "170.325", "146.484", "134.374", "156.137", "133.997", "133.775", "199.797", "138.082",
            "146.555", "137.357",
        ).mapIndexed { i, t -> "${i + 1}:$t" }.joinToString(" ")
        assertEquals(
            "laps $laps | best 14 | theoretical 131.615 | focus Corner 12:0.408 Corner 2:0.253 Corner 9:0.199",
            summary("silesia-ring"),
        )
    }

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
        const val RateHz = 10
        const val MicrosPerSecond = 1_000_000L
    }
}
