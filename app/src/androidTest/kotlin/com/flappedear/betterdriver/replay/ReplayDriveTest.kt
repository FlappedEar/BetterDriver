package com.flappedear.betterdriver.replay

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.flappedear.betterdriver.analysis.quality.QualityMetric
import com.flappedear.betterdriver.analysis.quality.Verdict
import com.flappedear.betterdriver.analysis.quality.coachReplayChecks
import com.flappedear.betterdriver.analysis.quality.driveChecks
import com.flappedear.betterdriver.analysis.quality.readLog
import com.flappedear.betterdriver.analysis.quality.replayCues
import com.flappedear.betterdriver.analysis.quality.replayLaps
import com.flappedear.betterdriver.analysis.quality.replayQuiet
import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CoachReferenceStore
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.CoachSettingsStore
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.toCoachFix
import com.flappedear.betterdriver.coach.tracedLaps
import com.flappedear.betterdriver.debug.AppLog
import com.flappedear.betterdriver.faults.AppDriver
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recording.DemoReplay
import com.flappedear.betterdriver.recording.GnssRef
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupStore
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.smoke.appPermissions
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.uikill.UiKillPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

/**
 * Arek's Demo track drive, done by CI instead of by him (2026-10-19: "you should run these tests on your
 * own"): the Silesia Ring laps built into the app (his 27 Sep 2026 sessions) replayed through the whole
 * app on the emulator, recorder, live timing, coach process and voice, with the coach at Full in the
 * language replay-drive.sh asks for. Then the app's continuous log is checked as his shared logs are:
 * the drive checks (FBBD-183) and what the coach said lap by lap (FBBD-244, FBBD-242, FBBD-243,
 * FBBD-245). Run in phases by .github/scripts/replay-drive.sh.
 *
 * The drive is 10 minutes (Arek, 2026-10-20: "replay drive na 10 minut zamiast na 30"): the coach knows
 * the track from an earlier outing there, one drive round the same laps, as his 27 Sep sessions on his
 * phone, and learns 1 lap, so laps 3 and 4 are coached. ReplayDriveSubsetTest checks on the computer
 * that these laps make every coach check; DemoDriveReplayTest drives all 19 laps.
 *
 * What only his phone and car can show (camera frame rate, RaceBox link, headset delay) is not checked.
 */
@RunWith(AndroidJUnit4::class)
@UiKillPhase
class ReplayDriveTest {
    @get:Rule
    val permissions: TestRule = GrantPermissionRule.grant(*appPermissions())

    private val d = AppDriver()
    private val marker = File(d.app.filesDir, "replay-drive.properties")
    private val args = InstrumentationRegistry.getArguments()
    private val language = CoachLanguage.entries.firstOrNull { it.name == args.getString("language") }
        ?: CoachLanguage.Polish

    /**
     * A clean phone: no sessions, an empty log, the Demo track on the built-in Silesia Ring laps, coach at
     * Full learning [LearningLaps] lap, with what it knows of the track from an earlier outing there.
     */
    @Test
    fun seed() {
        marker.delete()
        d.store.root.deleteRecursively()
        var n = 0
        val setup = Setup()
            .addEquipment(EquipmentModel.DEMO_TRACK, newId = { "demo-${n++}" })
            .addCar(CarName, newId = { "car-${n++}" })
        SetupStore(File(d.app.filesDir, "setup.json")).save(setup)
        d.app.getSharedPreferences(AppPrefs.FileName, Context.MODE_PRIVATE).edit()
            .putBoolean("setup_done", true)
            .commit()
        DemoReplay.choose(d.app, DemoReplay.SilesiaRing)
        // The coach process reads its settings from this file at each session start (CoachProtocol).
        CoachSettingsStore(File(d.app.filesDir, "coach/settings.properties"))
            .write(CoachSettings(Verbosity.Full, language = language, learningLaps = LearningLaps))
        earlierOuting()
        AppLog.clear(d.app)
    }

    /**
     * The coach's reference for the Silesia Ring from one drive round the built-in laps, saved with its
     * track as the coach library keeps them, so the coach starts with the corners and targets known. It
     * names no session, as none is on the phone, so the library keeps it instead of rebuilding it.
     */
    private fun earlierOuting() {
        val asset = { name: String -> d.app.assets.open("demo/$name").use { it.readBytes().decodeToString() } }
        // Saving a track already on the phone raises its revision, and a reference fits one revision only:
        // it is built for the track as saved (Codex).
        val track = TrackStore(File(d.app.filesDir, "tracks")).save(TrackJson.decode(asset("silesia-ring.track.json")))
        val replay = SessionReplay.read(asset("silesia-ring.replay"), 0L)
        val fixes = (0 until LoopSeconds * replay.rateHz).asSequence().mapNotNull { n ->
            SampleSet(n * MicrosPerSecond / replay.rateHz, replay.fix(n)).toCoachFix()
        }
        val reference = checkNotNull(buildReference(track, listOf(SessionLaps("earlier", tracedLaps(track, fixes)))))
        CoachReferenceStore(File(d.app.filesDir, "coach/references")).save(reference.copy(sessions = emptyList()))
    }

    /**
     * The drive, in one test: the recording started with the Demo track as the receiver, as START does,
     * with the app in front, [minutes] on the track screen in real time, then stopped as the 3 s hold
     * does and the session saved. The recorder starts the coach process itself; START and the hold on
     * screen are covered by the smoke tests. One test, because the runner waits for the app's main thread
     * to go idle before each test: a stop phase after the drive waited 8 min there on every run.
     */
    @Test
    fun drive() {
        d.openApp()
        d.instrumentation.runOnMainSync { RecorderClient.get(d.app).start(CarName, "", GnssRef.DemoTrack) }
        val live = checkNotNull(
            d.waitForRecorder("the recording with live GNSS", StartTimeoutMs) {
                it.recording && it.live?.gnss == GnssState.Ok
            }.live,
        )
        val id = live.sessionId
        d.saveProps(marker, KeySession to id)
        val minutes = args.getString("minutes")?.toLongOrNull() ?: DefaultMinutes
        for (left in minutes downTo 1) {
            Log.i(Tag, "replaying; $left min left")
            Thread.sleep(MillisPerMinute)
        }
        assertEquals("one recording all along", id, d.recorder().live?.sessionId)
        d.instrumentation.runOnMainSync { RecorderClient.get(d.app).stop() }
        d.waitFor(SaveTimeoutMs, "the session to be saved") {
            d.store.list().any { it.id == id && it.state == SessionState.FINISHED }
        }
        // The report is worked out after the stop (FBBD-247), and the log written out.
        Thread.sleep(SettleMs)
    }

    /**
     * The checks of the recording from the continuous log, as for a log Arek shares, written to
     * files/replay-drive.md for the script. Fails when a coach check fails or the app crashed.
     */
    @Test
    fun check() {
        val id = session()
        val lines = AppLog.files(d.app).flatMap { readLog(it) }
        val report = driveChecks(lines).singleOrNull { it.sessionId == id }
        val events = d.store.read(id).events
        val coach = coachReplayChecks(events, language.tag)
        File(d.app.filesDir, ReportName).writeText(markdown(report?.metrics.orEmpty(), coach, events))
        // A drive past the learning laps says corner cues; none means the coach or its voice did not run.
        check(replayCues(events).any { it.kind == "Corner" }) {
            "no corner cue said; coach problems: " +
                events.filter { it.kind == "coach_problem" }.take(MaxProblems).joinToString { it.text }
        }
        checkNotNull(report) { "the log has no recording $id" }
        // A 10-minute drive is checked only if the coach coached its laps 3 and 4: without its earlier
        // outing it would still be learning, and its checks would pass by not being measured.
        val silent = coach.single { it.id == "coach.silent_laps" }
        check(silent.verdict != Verdict.NOT_MEASURED) { "${silent.label}: ${silent.how}" }
        // The drive checks count too, except those that measure the emulator's own screen, start-up and
        // speech engine rather than the app: a phone's values come from Arek's logs.
        val drive = report.metrics.filterNot { m ->
            m.id in EmulatorChecks || EmulatorPrefixes.any { m.id.startsWith(it) }
        }
        val failed = (coach + drive).filter { it.verdict == Verdict.FAIL }
        check(failed.isEmpty()) { failed.joinToString("\n") { "${it.label}: ${it.how}" } }
        d.checkNoAppErrors()
    }

    private fun markdown(
        drive: List<QualityMetric>,
        coach: List<QualityMetric>,
        events: List<RecorderEvent>,
    ) = buildString {
        fun table(metrics: List<QualityMetric>) {
            appendLine("| Check | Value | Verdict | How |")
            appendLine("| --- | --- | --- | --- |")
            metrics.forEach {
                val value = it.value?.let { v -> "%.1f".format(v) } ?: "-"
                appendLine("| ${it.label} | $value ${it.unit} | ${it.verdict} | ${it.how.replace('|', '/')} |")
            }
        }
        appendLine("## Emulator replay of the built-in Silesia Ring laps, coach ${language.name}, Full")
        appendLine()
        appendLine("### Coach, lap by lap")
        appendLine()
        table(coach)
        appendLine()
        val cues = replayCues(events)
        val quiet = replayQuiet(events)
        appendLine("| Lap | Kind | Time | Coach quiet | Cues |")
        appendLine("| --- | --- | --- | --- | --- |")
        for (lap in replayLaps(events)) {
            val said = cues.filter { it.lap == lap.number }.joinToString("; ") { it.text }
            val still = quiet.filter { it.lap == lap.number }.joinToString { it.reason }
            val time = "%.1f".format(lap.timeMillis / MillisPerSecond)
            appendLine("| ${lap.number} | ${lap.kind} | $time s | $still | $said |")
        }
        appendLine()
        appendLine("### Drive checks from the continuous log (emulator: hardware values are not the phone's)")
        appendLine()
        table(drive)
    }.also { Log.i(Tag, it) }

    private fun session(): String =
        checkNotNull(d.loadProps(marker).getProperty(KeySession)) { "run start first" }

    private companion object {
        const val Tag = "ReplayDriveTest"
        const val CarName = "Replay car"
        const val KeySession = "session"
        const val ReportName = "replay-drive.md"
        const val SaveTimeoutMs = 60_000L
        const val StartTimeoutMs = 90_000L
        const val SettleMs = 8_000L
        const val MillisPerMinute = 60_000L
        const val MicrosPerSecond = 1_000_000L

        /** replay-drive.sh's REPLAY_MINUTES when it gives none. */
        const val DefaultMinutes = 10L

        /** The coach's learning laps: with the earlier outing known, lap 3 is coached (ReplayDriveSubsetTest). */
        const val LearningLaps = 1

        /** The built-in replay's loop, 2828 s, once round: the earlier outing. */
        const val LoopSeconds = 2_830L
        const val MillisPerSecond = 1000.0
        const val MaxProblems = 5

        /** Drive checks of the emulator's hardware: start-up time, speech engine delay, frame times. */
        val EmulatorChecks = setOf("app.cold_start", "coach.tts", "coach.headset", "soak.jank")

        /** Frame times, and the cue delays per sound path (coach.delay_live, coach.total_live, ...). */
        val EmulatorPrefixes = listOf("ui.", "coach.delay", "coach.total")
    }
}
