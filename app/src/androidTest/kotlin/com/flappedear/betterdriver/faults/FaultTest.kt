package com.flappedear.betterdriver.faults

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.os.Process
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.fake.FakeGnssEvents
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recording.DemoGnssHook
import com.flappedear.betterdriver.recording.GnssRef
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.smoke.appPermissions
import com.flappedear.betterdriver.uikill.UiKillPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

/**
 * Fault injection during a recording on the emulator (FBBD-34, FBBD-69, FBBD-43, FBBD-64, FBBD-63),
 * so no one has to do it by hand on the phone. Run in phases by .github/scripts/faults.sh, which
 * injects the faults Android only takes from outside (memory pressure, screen off) between them;
 * every phase attaches to the running app (`am instrument --no-restart`), as UiKillTest does.
 *
 * Demo GNSS sends the same fix for each fix number ([com.flappedear.betterdriver.recorder.fake.FakeGnss]),
 * so any lost, changed or repeated fix shows ([checkNoFixLost]), and it can be switched off and on
 * ([DemoGnssHook]) as a RaceBox that drops out.
 */
@RunWith(AndroidJUnit4::class)
@UiKillPhase
class FaultTest {
    @get:Rule
    val permissions: TestRule = GrantPermissionRule.grant(*appPermissions())

    private val d = AppDriver()
    private val marker = File(d.app.filesDir, "fault-test.properties")
    private val gnssOff = File(d.app.filesDir, DemoGnssHook.FileName)

    /** A clean start with Demo GNSS as the receiver. */
    @Test
    fun seed() {
        gnssOff.delete()
        marker.delete()
        d.seedDemo(CarName)
    }

    /**
     * START with Demo GNSS, then FBBD-63 on the track screen: the window keeps the screen on while
     * recording, and the status and navigation bars are hidden.
     */
    @Test
    fun startRecording() {
        d.openApp()
        d.tap("START")
        d.waitForTrackScreen()
        val live = checkNotNull(d.waitForRecorder("the recording to start") { it.recording }.live)
        checkTrackWindow()
        d.saveProps(marker, KeySession to live.sessionId)
        assertEquals(SessionState.RECORDING, d.store.list().single().state)
    }

    /**
     * After memory pressure on every process and the screen off for a while (faults.sh): the recorder
     * was not killed, the same session still records with its notification up, and not one fix was lost.
     */
    @Test
    fun survivedPressureAndScreenOff() {
        val id = session()
        val summary = d.store.list().single()
        assertEquals("one continuous session", id, summary.id)
        assertEquals("still recording", SessionState.RECORDING, summary.state)
        val notifications = d.app.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue(
            "no recording notification",
            notifications.any { it.notification.channelId == RecordingChannel && it.isOngoing },
        )
        d.openApp()
        d.waitForTrackScreen()
        assertEquals(id, d.recorder().live?.sessionId)

        val data = d.store.read(id)
        val fixes = checkNoFixLost(data)
        val spanMs = (fixes.last().tMicros - fixes.first().tMicros) / MicrosPerMilli
        val lengthMs = System.currentTimeMillis() - data.info.startUtcMillis
        Log.i(Tag, "${fixes.size} fixes over $spanMs ms of a $lengthMs ms recording")
        assertTrue("fixes span $spanMs ms of $lengthMs ms", spanMs >= lengthMs - SlackMs)
        d.checkNoAppErrors()
    }

    /**
     * FBBD-43 and FBBD-64 with Demo GNSS switched off and on [Cycles] times in one recording: the
     * GNSS-lost banner shows within 2 s and clears within 2 s of the return, recording carries on in
     * the same session, fixes are back within 5 s, each gap is in the session with its start, end and
     * reason, and no dialog shows.
     *
     * The 2 s are timed on the state the track screen draws its banner from ([RecorderClient]'s, in
     * this process), polled every 50 ms: UiAutomator reads the always-animating screen too slowly
     * (seconds a query on the CI emulator) to time it. On [UiCycles] it also checks, without a time
     * limit, that the banner is on screen and goes, and that no dialog is up.
     */
    @Test
    fun gnssLossAndReturn() {
        val id = session()
        d.openApp()
        d.waitForTrackScreen()
        relaunchKeepsLiveState()
        val backAt = mutableListOf<Long>()
        try {
            repeat(Cycles) { cycle ->
                backAt += lossCycle(cycle, id)
            }
        } finally {
            gnssOff.delete()
        }
        Thread.sleep(SettleMs)

        val sessions = d.store.list()
        assertEquals("one continuous session", listOf(id), sessions.map { it.id })
        assertEquals(SessionState.RECORDING, sessions.single().state)
        val data = d.store.read(id)
        checkGaps(data, backAt)
        d.checkNoAppErrors()
    }

    /**
     * Kills the recorder process mid-session (FBBD-34, FBBD-69): Android restarts it and a new session
     * carries on; the killed one is recovered and exports a valid VBO. At most 2 s lost: the whole gap,
     * from the last fix saved before the kill to the restarted recorder's first fix.
     */
    @Test
    fun recorderKill() {
        val id = session()
        d.openApp()
        d.waitForTrackScreen()
        val pid = recorderPid()
        val killedAt = System.currentTimeMillis()
        Process.killProcess(pid)

        val next = waitForCarryOn(id)
        val old = recovered(id)
        assertEquals(SessionState.RECOVERED, old.state)
        val fixes = checkNoFixLost(old)
        val lastFixAt = old.info.startUtcMillis + fixes.last().tMicros / MicrosPerMilli
        val lostMs = killedAt - lastFixAt
        val rows = checkVbo(old, fixes.size)
        val firstAgain = firstFixAt(next)
        // What the kill cost: from the last fix saved before it to the first fix after the restart.
        val gapMs = firstAgain - lastFixAt
        Log.i(Tag, "recorder killed: $gapMs ms without data ($lostMs ms lost before the kill, data again " +
            "${firstAgain - killedAt} ms after it), VBO $rows rows, carried on in $next")
        assertTrue("$lostMs ms of data lost before the kill", lostMs <= MaxLostMs)
        assertTrue(
            "$gapMs ms without data across the recorder kill ($lostMs ms before it, " +
                "${firstAgain - killedAt} ms after it to the restarted recorder's first fix)",
            gapMs <= MaxLostMs,
        )
        d.saveProps(marker, KeySession to next)
        d.checkNoAppErrors()
    }

    /**
     * Stops the session carried on after the kill with the hold, then records and stops [Cycles] short
     * sessions in a row (FBBD-34): each is finished cleanly, with every fix, and exports a valid VBO.
     */
    @Test
    fun stopTenTimes() {
        val carried = session()
        d.openApp()
        d.waitForTrackScreen()
        d.holdToStop()
        checkFinished(carried)

        val seen = mutableSetOf(carried)
        repeat(Cycles) { i ->
            // As a driver would: START once the last session is saved and the recorder has let it go.
            d.waitForRecorder("the recorder to be idle") { !it.recording && !it.starting }
            Thread.sleep(IdleMs)
            d.instrumentation.runOnMainSync { RecorderClient.get(d.app).start(CarName, "", GnssRef.Demo) }
            val live = checkNotNull(
                d.waitForRecorder("short session ${i + 1} to record") { s ->
                    val l = s.live
                    s.recording && l != null && l.sessionId !in seen && l.fixes >= ShortSessionFixes
                }.live,
            )
            seen += live.sessionId
            d.instrumentation.runOnMainSync { RecorderClient.get(d.app).stop() }
            checkFinished(live.sessionId)
        }
        val finished = d.store.list().filter { it.state == SessionState.FINISHED }
        assertEquals("finished sessions", Cycles + 1, finished.size)
        d.checkNoAppErrors()
    }

    /**
     * FBBD-209: the app opened again while it is on the track screen (a new task, as from the
     * notification) still follows the recorder once the activity it replaced has stopped. Android
     * stops that one when the new one goes idle, which the always-animating track screen never does,
     * so up to 10 s later. Its stop used to unbind the new one from the recorder too: the screen kept
     * the last state it got, GNSS "Ok", and never showed the banner (cycle 1 then timed out).
     */
    private fun relaunchKeepsLiveState() {
        val replaced = resumedActivity()
        d.openApp()
        d.waitForTrackScreen()
        d.waitFor(ReplacedStopTimeoutMs, "the replaced activity to stop") {
            var stage: Stage? = null
            d.instrumentation.runOnMainSync {
                // Unknown once destroyed: the monitor forgets it.
                stage = runCatching { ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(replaced) }
                    .getOrNull()
            }
            stage == null || stage == Stage.STOPPED || stage == Stage.DESTROYED
        }
        val seen = checkNotNull(d.recorder().live).elapsedMicros
        d.waitForRecorder("the live state to update after the relaunch", LiveUpdateTimeoutMs) {
            (it.live?.elapsedMicros ?: Long.MIN_VALUE) > seen
        }
    }

    private fun resumedActivity(): Activity {
        var activity: Activity? = null
        d.instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull()
        }
        return checkNotNull(activity) { "no resumed activity. ${d.screen()}" }
    }

    /** One drop and return; returns when Demo GNSS was switched back on, in wall-clock ms. */
    private fun lossCycle(cycle: Int, id: String): Long {
        val n = cycle + 1
        val onScreen = n in UiCycles
        gnssOff.createNewFile()
        val offAt = System.currentTimeMillis()
        val shownMs = waitMs("the GNSS-lost banner's state, cycle $n") { gnssIs(GnssState.Lost) } - offAt
        if (onScreen) {
            val seenMs = waitMs("the banner on screen, cycle $n", ScreenTimeoutMs) { d.shows(LostBanner) } - offAt
            checkNoDialog()
            Log.i(Tag, "cycle $n: banner read on screen by UiAutomator after $seenMs ms")
        }
        Thread.sleep(LostForMs)
        gnssOff.delete()
        val backAt = System.currentTimeMillis()
        val clearedMs = waitMs("the banner's state to clear, cycle $n") { gnssIs(GnssState.Ok) } - backAt
        if (onScreen) {
            val goneMs = waitMs("the banner to go, cycle $n", ScreenTimeoutMs) { !d.shows(LostBanner) } - backAt
            checkNoDialog()
            Log.i(Tag, "cycle $n: banner gone on screen for UiAutomator after $goneMs ms")
        }
        Log.i(Tag, "cycle $n: banner after $shownMs ms, cleared after $clearedMs ms")
        assertTrue("cycle $n: the banner showed after $shownMs ms", shownMs <= BannerLimitMs)
        assertTrue("cycle $n: the banner cleared after $clearedMs ms", clearedMs <= BannerLimitMs)
        assertEquals("the recording changed", id, d.recorder().live?.sessionId)
        Thread.sleep(SettleMs)
        return backAt
    }

    /** The GNSS state the track screen shows now. */
    private fun gnssIs(state: GnssState) = d.recorder().live?.gnss == state

    /**
     * Each drop is in the session with its start and end and a reason, fixes came back within 5 s of
     * each return, and no fix was lost while Demo GNSS was on.
     */
    private fun checkGaps(data: SessionData, backAt: List<Long>) {
        val off = switchedOff(data)
        assertEquals("drops in the session", Cycles, off.size)
        off.forEach { (drop, back) ->
            assertEquals(FakeGnssEvents.REASON_SWITCHED_OFF, drop.text)
            assertNotNull("a drop at ${drop.tMicros} us with no end", back)
        }
        val fixes = checkNoFixLost(data)
        assertEquals("gaps in the fixes", Cycles, fixGaps(fixes).size)
        backAt.forEachIndexed { i, wall ->
            val wallOf = { f: DemoFix -> data.info.startUtcMillis + f.tMicros / MicrosPerMilli }
            val first = checkNotNull(fixes.firstOrNull { wallOf(it) >= wall }) {
                "cycle ${i + 1}: no fix after Demo GNSS came back"
            }
            val resumeMs = wallOf(first) - wall
            Log.i(Tag, "cycle ${i + 1}: fixes again after $resumeMs ms")
            assertTrue("cycle ${i + 1}: fixes again after $resumeMs ms", resumeMs <= ResumeLimitMs)
        }
    }

    /** FBBD-63: keep-screen-on on the window and the track screen's view, and no system bars. */
    private fun checkTrackWindow() {
        val end = System.currentTimeMillis() + AppDriver.LoadTimeoutMs
        var problems = listOf("not checked")
        while (problems.isNotEmpty()) {
            check(System.currentTimeMillis() < end) { "FBBD-63 on the track screen: $problems. ${d.screen()}" }
            d.instrumentation.runOnMainSync { problems = trackWindowProblems() }
            if (problems.isNotEmpty()) Thread.sleep(AppDriver.PollMs)
        }
    }

    private fun trackWindowProblems(): List<String> {
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .singleOrNull() ?: return listOf("no resumed activity")
        val window = activity.window
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        return listOfNotNull(
            "no FLAG_KEEP_SCREEN_ON".takeIf { (window.attributes.flags and KeepScreenOn) == 0 },
            "no view keeps the screen on".takeIf { !window.decorView.keepsScreenOn() },
            "status bar shown".takeIf { insets?.isVisible(WindowInsetsCompat.Type.statusBars()) != false },
            "navigation bar shown".takeIf { insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false },
        )
    }

    private fun View.keepsScreenOn(): Boolean =
        keepScreenOn || (this is ViewGroup && (0 until childCount).any { getChildAt(it).keepsScreenOn() })

    /**
     * FBBD-64: no dialog in front of the track screen. A dialog is a window of its own, so the app has
     * one window and it still shows the track screen's top line: the recording clock, or the banner.
     */
    private fun checkNoDialog() {
        val windows = d.device.findObjects(By.pkg(d.app.packageName).depth(0)).size
        assertEquals("app windows (a dialog is one more)", 1, windows)
        check(d.shows("REC") || d.shows(LostBanner)) { "no track screen: ${d.screen()}" }
    }

    /** Polls [condition] every [FastPollMs] and returns the wall-clock ms it first held. */
    private fun waitMs(what: String, timeoutMs: Long = EventTimeoutMs, condition: () -> Boolean): Long {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what. ${d.screen()}" }
            Thread.sleep(FastPollMs)
        }
        return System.currentTimeMillis()
    }

    private fun recorderPid(): Int {
        val manager = d.app.getSystemService(ActivityManager::class.java)
        val name = "${d.app.packageName}:recorder"
        val recorder = manager.runningAppProcesses.orEmpty().singleOrNull { it.processName == name }
        return checkNotNull(recorder) { "no recorder process" }.pid
    }

    /** Waits for the restarted recorder's new session, which names [killed] as the one it carries on. */
    private fun waitForCarryOn(killed: String): String {
        var next: String? = null
        d.waitFor(CarryOnTimeoutMs, "the restarted recorder's session") {
            next = d.store.list().firstOrNull { s ->
                s.id != killed && s.state == SessionState.RECORDING &&
                    d.store.read(s.dir, samples = false).events.any {
                        it.kind == EventKinds.RECORDER_RESTART && it.text == "previous=$killed"
                    }
            }?.id
            next != null
        }
        return checkNotNull(next)
    }

    /** The wall-clock ms of the first fix session [id] recorded, waiting for it. */
    private fun firstFixAt(id: String): Long {
        var at: Long? = null
        d.waitFor(CarryOnTimeoutMs, "a fix in $id") {
            val data = d.store.read(id)
            at = demoFixes(data).firstOrNull()?.let { data.info.startUtcMillis + it.tMicros / MicrosPerMilli }
            at != null
        }
        return checkNotNull(at)
    }

    /** The killed session once recovered: the UI recovers it when the recorder dies; else it is done here. */
    private fun recovered(id: String): SessionData {
        val byUi = runCatching {
            d.waitFor(RecoverTimeoutMs, "the UI to recover $id") { stateOf(id) == SessionState.RECOVERED }
        }
        if (byUi.isFailure) d.store.recoverInterrupted()
        return d.store.read(id)
    }

    /** [id] finished cleanly, with every fix and a valid VBO. */
    private fun checkFinished(id: String) {
        d.waitFor(StopTimeoutMs, "$id to finish") { stateOf(id) == SessionState.FINISHED }
        val data = d.store.read(id)
        assertNotNull("$id has no end record", data.end)
        val fixes = checkNoFixLost(data)
        checkVbo(data, fixes.size)
    }

    private fun stateOf(id: String) = d.store.state(d.store.dir(id))

    private fun session(): String =
        checkNotNull(d.loadProps(marker).getProperty(KeySession)) { "run startRecording first" }

    private companion object {
        const val Tag = "FaultTest"
        const val CarName = "Fault test car"
        const val KeySession = "session"
        const val RecordingChannel = "recording"
        const val LostBanner = "GNSS signal lost"
        const val KeepScreenOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        /** FBBD-43 and FBBD-64: 10 tries each way. */
        const val Cycles = 10

        /** The cycles that also read the screen: the first and the last. */
        val UiCycles = setOf(1, Cycles)
        const val ScreenTimeoutMs = 60_000L
        const val LostForMs = 1_000L
        const val SettleMs = 1_500L
        const val IdleMs = 1_000L
        const val BannerLimitMs = 2_000L
        const val ResumeLimitMs = 5_000L
        const val MaxLostMs = 2_000L
        const val EventTimeoutMs = 10_000L
        const val FastPollMs = 50L
        const val CarryOnTimeoutMs = 60_000L
        const val RecoverTimeoutMs = 15_000L
        const val StopTimeoutMs = 30_000L

        /** Android stops a replaced activity at the latest 10 s after the new one resumed. */
        const val ReplacedStopTimeoutMs = 30_000L

        /** The recorder sends its state every 250 ms. */
        const val LiveUpdateTimeoutMs = 2_000L

        /** Two seconds of Demo GNSS. */
        const val ShortSessionFixes = 50L

        /** The receiver's start after the session opens, in ms. */
        const val SlackMs = 3_000L
        const val MicrosPerMilli = 1_000L
    }
}
