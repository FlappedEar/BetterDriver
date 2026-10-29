package com.flappedear.betterdriver.uikill

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.RecorderState
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupStore
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.smoke.appPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

/**
 * Kills the UI process during a recording (FBBD-72): the recording carries on in the recorder's
 * process, the UI reconnects to it, and the session is exactly what an uninterrupted run gives.
 *
 * Three phases, run by .github/scripts/ui-kill.sh on the emulator. [seed] writes the setup (Demo
 * GNSS as the receiver). The script starts the app's process, and [startRecording] attaches to it
 * (`am instrument --no-restart`, so the app and its recorder are never force-stopped), opens Drive,
 * presses START and leaves the session recording. The script opens the app on the track screen,
 * SIGKILLs the UI process (as Android does to free memory), checks the recorder process lives on,
 * waits, and starts the app again; [reconnectAndStop] attaches to it, opens it, checks it shows the
 * running session, stops it with the 3 s hold and checks every fix of the whole session is there.
 * Demo GNSS sends the same fix for each index ([FakeGnss]), so any lost or repeated fix shows.
 */
@RunWith(AndroidJUnit4::class)
@UiKillPhase
class UiKillTest {
    @get:Rule
    val permissions: TestRule = GrantPermissionRule.grant(*appPermissions())

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val sessions = File(app.filesDir, "sessions")
    private val marker = File(app.filesDir, "ui-kill-test.properties")
    private val gnss = FakeGnss()

    @Test
    fun seed() {
        // A clean start: no sessions, Demo GNSS as the receiver, setup done so the app opens on Drive.
        sessions.deleteRecursively()
        marker.delete()
        var n = 0
        val setup = Setup()
            .addEquipment(EquipmentModel.DEMO_GNSS, newId = { "demo-${n++}" })
            .addCar(CarName, newId = { "car-${n++}" })
        SetupStore(File(app.filesDir, "setup.json").path).save(setup)
        app.getSharedPreferences(AppPrefs.FileName, Context.MODE_PRIVATE).edit()
            .putBoolean("setup_done", true)
            .commit()
    }

    @Test
    fun startRecording() {
        openApp()
        val start = device.wait(Until.findObject(By.text("START")), LoadTimeoutMs)
        checkNotNull(start) { "Drive shows no START. ${screen()}" }.click()
        waitForTrackScreen()
        val live = waitForRecorder("the recording to start") { it.recording }.live!!
        // Some data before the kill, written well before it.
        Thread.sleep(BeforeKillMs)

        Properties().apply {
            setProperty(KeySession, live.sessionId)
            setProperty(KeyStart, live.startUtcMillis.toString())
            marker.outputStream().use { store(it, "FBBD-72 UI-process kill test") }
        }
        assertEquals(SessionState.RECORDING, SessionStore(sessions).list().single().state)
    }

    @Test
    fun reconnectAndStop() {
        val props = Properties().apply { marker.inputStream().use { load(it) } }
        val id = checkNotNull(props.getProperty(KeySession)) { "run startRecording first" }
        val startWall = props.getProperty(KeyStart).toLong()
        val store = SessionStore(sessions)
        // The session is still the recorder's: its process kept the lock through the kill.
        val before = store.list().single()
        assertEquals(id, before.id)
        assertEquals("still recording after the UI was killed", SessionState.RECORDING, before.state)

        // The app, opened again, reconnects to the running session and shows it on the track screen.
        openApp()
        waitForTrackScreen()
        val live = waitForRecorder("the UI to reconnect") { it.recording }.live!!
        assertEquals("reconnected to another session", id, live.sessionId)

        // Stop with the 3 s hold, anywhere on the track screen, as Arek does.
        val x = device.displayWidth / 2
        val y = device.displayHeight / 2
        val holdWall = System.currentTimeMillis()
        device.swipe(x, y, x, y, HoldSteps)
        val finished = waitFor(StopTimeoutMs, "the session to finish") {
            store.list().singleOrNull()?.state == SessionState.FINISHED
        }
        check(finished)

        val data = store.read(id)
        assertEquals(SessionState.FINISHED, data.state)
        val fixes = data.sampleSets.filter { s -> s.values.any { it.channelId == GnssChannels.UTC_MS.id } }
            .sortedBy { it.tMicros }
        // Every fix Demo GNSS sent, in order, none missing or repeated, across the kill.
        fixes.forEachIndexed { i, s -> assertEquals("fix $i", gnss.fix(i.toLong()), s.values) }
        // And the session recorded the whole time: the recorder stamps each fix as it arrives, and no
        // two are far apart, across the kill and the time with no UI, up to the hold that stops it.
        // Fixes come at 25 Hz, so a gap of a second would be a stall.
        val arrivals = fixes.map { it.tMicros }
        val maxGapMs = arrivals.zipWithNext { a, b -> b - a }.maxOrNull()?.div(MicrosPerMilli) ?: 0
        val spanMs = (arrivals.last() - arrivals.first()) / MicrosPerMilli
        val lengthMs = holdWall - startWall
        Log.i(Tag, "${fixes.size} fixes over $spanMs ms of $lengthMs ms before the hold, largest gap $maxGapMs ms")
        assertTrue("a $maxGapMs ms gap between fixes", maxGapMs < MaxGapMs)
        assertTrue("fixes span $spanMs ms of the $lengthMs ms before the hold", spanMs >= lengthMs - SlackMs)

        // The VBO is the one an uninterrupted recording of the same fixes gives.
        val reference = uninterrupted(data, fixes.size)
        assertEquals(vbo(reference).withoutSessionId(reference.id), vbo(data).withoutSessionId(id))
    }

    /** The same session recorded in one go, without a kill: [count] fixes, 25 a second. */
    private fun uninterrupted(data: SessionData, count: Int): SessionData {
        val clock = AtomicLong()
        val store = SessionStore(File(app.cacheDir, "ui-kill-reference").apply { deleteRecursively() })
        val recording = Recorder(store, nanoTime = clock::get).start(data.info)
        GnssChannels.ALL.forEach(recording::defineChannel)
        for (i in 0 until count) {
            recording.submit(SampleSet(i * MicrosPerSecond / gnss.rateHz, gnss.fix(i.toLong())))
        }
        check(recording.stop()) { "the reference recording did not finish" }
        return store.read(recording.id)
    }

    private fun vbo(data: SessionData): String =
        ByteArrayOutputStream().also { VboExporter.export(data, it) }.toString(Charsets.UTF_8.name())

    private fun String.withoutSessionId(id: String) = replace(id, "<session>")

    /**
     * Opens the app from this process, after the attach: the runner's activity monitoring only sees
     * activities started once it runs, and the script's `am start` can leave the launcher in front.
     */
    private fun openApp() {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), LoadTimeoutMs)
    }

    /**
     * The track screen with its status strip (GNSS) and recording clock (REC), and the recorder
     * reporting fixes. The strip's values are drawn as number text UiAutomator does not read, so the
     * GNSS state comes from the recorder. Android's first-time "Viewing full screen" hint is dismissed
     * so it is not in the way of the hold.
     */
    private fun waitForTrackScreen() {
        val shown = device.wait(Until.hasObject(By.text("REC")), LoadTimeoutMs) == true &&
            device.hasObject(By.text("GNSS"))
        check(shown) { "no track screen. ${screen()}" }
        waitForRecorder("live GNSS") { it.live?.gnss == GnssState.Ok }
        device.findObject(By.text("Got it"))?.click()
    }

    /** What is on screen and what the recorder says, for a failure message. */
    private fun screen(): String {
        var state = RecorderState()
        instrumentation.runOnMainSync { state = RecorderClient.get(app).state }
        val texts = device.findObjects(By.textContains("")).mapNotNull { it.text }.take(MaxTexts)
        return "Front: ${device.currentPackageName}; texts: $texts; recording=${state.recording}, " +
            "live=${state.live?.let { "${it.sessionId} ${it.gnss}" }}"
    }

    private fun waitForRecorder(what: String, condition: (RecorderState) -> Boolean): RecorderState {
        var state = RecorderState()
        waitFor(LoadTimeoutMs, what) {
            instrumentation.runOnMainSync { state = RecorderClient.get(app).state }
            condition(state)
        }
        return state
    }

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out after $timeoutMs ms waiting for $what" }
            Thread.sleep(PollMs)
        }
        return true
    }

    private companion object {
        const val CarName = "Kill test car"
        const val KeySession = "session"
        const val KeyStart = "start_utc_millis"
        const val LoadTimeoutMs = 30_000L
        const val StopTimeoutMs = 30_000L
        const val BeforeKillMs = 5_000L
        const val PollMs = 200L
        const val MaxTexts = 30

        /** UiAutomator injects a swipe step about every 5 ms: 800 steps hold for about 4 s, past the 3 s. */
        const val HoldSteps = 800

        /** The receiver's start after the session opens, in ms. */
        const val SlackMs = 3_000L
        const val MaxGapMs = 1_000L
        const val MicrosPerMilli = 1_000L
        const val Tag = "UiKillTest"
        const val MicrosPerSecond = 1_000_000L
    }
}
