@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.quality.QualityMetric
import com.flappedear.betterdriver.analysis.quality.Verdict
import com.flappedear.betterdriver.analysis.quality.coachReplayChecks
import com.flappedear.betterdriver.analysis.quality.replayCues
import com.flappedear.betterdriver.analysis.quality.replayLaps
import com.flappedear.betterdriver.analysis.quality.replayQuiet
import com.flappedear.betterdriver.coach.CoachFix
import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CoachSession
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.live.LiveTiming
import com.flappedear.betterdriver.timing.live.lapLineText
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.TrackJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Arek's Demo track drive on the computer: the Silesia Ring laps built into the app (his 27 Sep 2026
 * sessions, FBBD-237) driven once round, all 19 laps, with the coach at Full as he sets it, in Polish
 * and in English. Live timing and the coach run as the recorder and coach processes run them, fed every
 * 25 Hz fix as it arrives, and their events are checked as the drive-log reports check his logs
 * (FBBD-244, FBBD-242, FBBD-243, FBBD-245). The emulator replay (replay-drive.sh) checks the same in
 * the whole app, with its voice; this one runs every lap in seconds.
 *
 * Each run's laps and cues are written to build/reports/replay-drive, for the pull request's checks.
 */
class DemoDriveReplayTest {
    private val dir = File(System.getProperty("builtInReplayDir") ?: "../app/src/main/assets/demo")
    private val track = TrackJson.decode(File(dir, "silesia-ring.track.json").readText())

    /** The events one drive round the loop logs, as the recorder stores them. */
    private fun drive(language: CoachLanguage): List<RecorderEvent> {
        val replay = SessionReplay.read(File(dir, "silesia-ring.replay").readText(), 0L)
        val coach = CoachSession(CoachSettings(Verbosity.Full, language = language), track, null)
        val timing = LiveTiming(track)
        val events = mutableListOf<RecorderEvent>()
        for (n in 0 until LoopSeconds * replay.rateHz) {
            val t = n * 1_000_000L / replay.rateHz
            val values = replay.fix(n)
            val fix = SampleSet(t, values).toFix() ?: continue
            val before = timing.book.count
            val shown = timing.state.predictedMillis
            timing.update(fix)
            timing.book.laps.drop(before).forEach {
                events += RecorderEvent(t, EventKinds.LAP_LINE, lapLineText(it, shown))
            }
            val kmh = values.firstOrNull { it.channelId == GnssChannels.SPEED_KMH.id }?.value
            coach.onFix(CoachFix(t, fix.lat, fix.lon, kmh), t) { true }.events
                .forEach { events += RecorderEvent(it.tMicros, it.kind, it.text) }
        }
        return events
    }

    private fun check(language: CoachLanguage) {
        val events = drive(language)
        val metrics = coachReplayChecks(events, language.tag)
        report(language, events, metrics)
        // The built-in loop drops the stops in the pit lane (FBBD-237), so no lap ends there: laps 6 and 13
        // leave it at 16 and 49 km/h, which DemoReplayCoachTest checks are coached.
        for (m in metrics.filter { it.id != "coach.pit_out" }) {
            assertEquals(Verdict.PASS, m.verdict, "${m.label}: ${m.how}")
        }
    }

    @Test
    fun `the Polish coach on every lap of the built-in Silesia Ring drive`() = check(CoachLanguage.Polish)

    @Test
    fun `the English coach on every lap of the built-in Silesia Ring drive`() = check(CoachLanguage.English)

    /** Laps, cues and checks as a Markdown page, as the drive-log reports give them. */
    private fun report(language: CoachLanguage, events: List<RecorderEvent>, metrics: List<QualityMetric>) {
        val cues = replayCues(events)
        val quiet = replayQuiet(events)
        val text = buildString {
            appendLine("## Built-in Silesia Ring replay on the JVM, coach ${language.name}, Full")
            appendLine()
            appendLine("| Check | Value | Verdict | How |")
            appendLine("| --- | --- | --- | --- |")
            metrics.forEach {
                appendLine("| ${it.label} | ${it.value ?: "-"} ${it.unit} | ${it.verdict} | ${it.how} |")
            }
            appendLine()
            appendLine("| Lap | Kind | Time | Coach quiet | Cues |")
            appendLine("| --- | --- | --- | --- | --- |")
            for (lap in replayLaps(events)) {
                val said = cues.filter { it.lap == lap.number }.joinToString("; ") { it.text }
                val still = quiet.filter { it.lap == lap.number }.joinToString { it.reason }
                val time = "%.1f".format(lap.timeMillis / 1000.0)
                appendLine("| ${lap.number} | ${lap.kind} | $time s | $still | $said |")
            }
        }
        File("build/reports/replay-drive").apply { mkdirs() }.resolve("jvm-${language.tag}.md").writeText(text)
        println(text)
    }

    private companion object {
        /** The built-in replay's loop, 2828 s, once round. */
        const val LoopSeconds = 2_830L
    }
}
