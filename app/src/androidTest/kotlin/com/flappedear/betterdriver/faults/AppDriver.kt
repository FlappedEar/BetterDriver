package com.flappedear.betterdriver.faults

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.RecorderState
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupStore
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.smoke.appProcessErrors
import java.io.File
import java.util.Properties

/**
 * Drives the app from a phase of a scripted emulator test (faults.sh, permissions.sh), as UiKillTest
 * does: the phases attach to the running app (`am instrument --no-restart`), so the app is opened from
 * this process and read with UiAutomator, which, unlike the Compose test rule, does not wait for the
 * always-animating track screen to go idle.
 */
class AppDriver {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app: Context = instrumentation.targetContext
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val store = SessionStore(File(app.filesDir, "sessions"))

    init {
        // UiAutomator waits for the screen to go quiet before each query, but the track screen updates
        // several times a second, so it never does; each query would wait out the whole timeout and
        // the 2 s banner timings (FBBD-64) could not be measured. Queries read the screen as it is.
        Configurator.getInstance().setWaitForIdleTimeout(0)
    }

    /** A clean start: no sessions, Demo GNSS as the receiver, a car, setup done so the app opens on Drive. */
    fun seedDemo(car: String) {
        store.root.deleteRecursively()
        var n = 0
        val setup = Setup()
            .addEquipment(EquipmentModel.DEMO_GNSS, newId = { "demo-${n++}" })
            .addCar(car, newId = { "car-${n++}" })
        SetupStore(File(app.filesDir, "setup.json")).save(setup)
        app.getSharedPreferences(AppPrefs.FileName, Context.MODE_PRIVATE).edit()
            .putBoolean("setup_done", true)
            .commit()
    }

    /**
     * Opens the app from this process: the runner's activity monitoring only sees activities started
     * once it runs, and the script's `am start` can leave the launcher in front.
     */
    fun openApp() {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), LoadTimeoutMs)
    }

    /** Taps the first view showing [text], waiting for it. */
    fun tap(text: String, timeoutMs: Long = LoadTimeoutMs) {
        val found = device.wait(Until.findObject(By.text(text)), timeoutMs)
        checkNotNull(found) { "no \"$text\" on screen. ${screen()}" }.click()
    }

    /** Whether a view shows [text] now. */
    fun shows(text: String): Boolean = device.hasObject(By.text(text))

    /**
     * The track screen with its status strip (GNSS) and recording clock (REC), and the recorder
     * reporting fixes. Android's first-time "Viewing full screen" hint is dismissed so it is not in
     * the way.
     */
    fun waitForTrackScreen() {
        val shown = device.wait(Until.hasObject(By.text("REC")), LoadTimeoutMs) == true &&
            device.hasObject(By.text("GNSS"))
        check(shown) { "no track screen. ${screen()}" }
        waitForRecorder("live GNSS") { it.live?.gnss == GnssState.Ok }
        // The state is the recorder's now, not the last one a lost binding left behind (FBBD-209).
        val seen = checkNotNull(recorder().live).elapsedMicros
        waitForRecorder("the live state to update") { (it.live?.elapsedMicros ?: Long.MIN_VALUE) > seen }
        device.findObject(By.text("Got it"))?.click()
    }

    /** Stops the session with the 3 s hold, anywhere on the track screen, as Arek does. */
    fun holdToStop() {
        val x = device.displayWidth / 2
        val y = device.displayHeight / 2
        device.swipe(x, y, x, y, HoldSteps)
    }

    /** The recorder's state as the UI process sees it now. */
    fun recorder(): RecorderState {
        var state = RecorderState()
        instrumentation.runOnMainSync { state = RecorderClient.get(app).state }
        return state
    }

    fun waitForRecorder(
        what: String,
        timeoutMs: Long = LoadTimeoutMs,
        condition: (RecorderState) -> Boolean,
    ): RecorderState {
        var state = RecorderState()
        waitFor(timeoutMs, what) {
            state = recorder()
            condition(state)
        }
        return state
    }

    fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out after $timeoutMs ms waiting for $what. ${screen()}" }
            Thread.sleep(PollMs)
        }
    }

    /** No process of the app (UI, recorder, camera, coach) crashed or stopped responding. */
    fun checkNoAppErrors() {
        val errors = appProcessErrors()
        check(errors.isEmpty()) { errors.joinToString("\n") }
    }

    /** What is on screen and what the recorder says, for a failure message. */
    fun screen(): String {
        val state = recorder()
        val texts = device.findObjects(By.textContains("")).mapNotNull { it.text }.take(MaxTexts)
        return "Front: ${device.currentPackageName}; texts: $texts; recording=${state.recording}, " +
            "live=${state.live?.let { "${it.sessionId} ${it.gnss} at ${it.elapsedMicros / MicrosPerSecond} s" }}, " +
            "connected=${state.connected}, error=${state.error}"
    }

    /** Small key-value files the phases hand on to the next phase. */
    fun saveProps(file: File, vararg values: Pair<String, String>) {
        val props = if (file.isFile) loadProps(file) else Properties()
        values.forEach { (k, v) -> props.setProperty(k, v) }
        file.outputStream().use { props.store(it, "scripted emulator test") }
    }

    fun loadProps(file: File): Properties = Properties().apply { file.inputStream().use { load(it) } }

    companion object {
        const val LoadTimeoutMs = 30_000L
        const val PollMs = 100L
        const val MaxTexts = 30
        const val MicrosPerSecond = 1_000_000L

        /** UiAutomator injects a swipe step about every 5 ms: 800 steps hold for about 4 s, past the 3 s. */
        const val HoldSteps = 800
    }
}
