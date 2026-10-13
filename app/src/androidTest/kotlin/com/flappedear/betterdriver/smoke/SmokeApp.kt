package com.flappedear.betterdriver.smoke

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.tracks.Tracks
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import java.io.File

/** What the first-run checklist asks for, so the app opens on Drive with no permission dialog. */
fun appPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** The app under test, started on the seeded data, in the order the rules must run. */
typealias SmokeRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/**
 * The emulator smoke tests' setup (FBBD-161): grants the permissions the first-run checklist asks
 * for (so the app opens on Drive with no permission dialog), seeds the realistic data once per test
 * process, then starts the app. After each test it checks that no process of the app crashed or
 * stopped responding.
 */
class SmokeSetup {
    val compose: SmokeRule = createAndroidComposeRule<MainActivity>()

    val rules: TestRule = RuleChain
        .outerRule(GrantPermissionRule.grant(*appPermissions()))
        .around(Seeded)
        .around(NoAppErrors)
        .around(compose)

    /** Writes the fixtures before the app first reads its data in this process. */
    private object Seeded : ExternalResource() {
        private var done = false

        @Synchronized
        override fun before() {
            if (done) return
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext
            val assets = instrumentation.context.assets
            // A clean start, also when the tests run again on a device that kept the app's data.
            // The persisted setup (SetupRepo's files) too, or a rerun would open on the last run's setup.
            listOf("sessions", "tracks", "coach", "setup.json", "session-setup.json")
                .forEach { File(app.filesDir, it).deleteRecursively() }
            SmokeFixtures.seed(
                app.filesDir,
                vbo = { name -> assets.open(name).bufferedReader().use { it.readText() } },
                video = { assets.open("smoke/${SmokeFixtures.VideoFile}") },
            )
            // Setup is done, so the app opens on Drive as it does for Arek.
            app.getSharedPreferences(AppPrefs.FileName, Context.MODE_PRIVATE).edit()
                .putBoolean("setup_done", true)
                .commit()
            done = true
        }
    }

    /** Fails the test when a process of the app (UI, recorder, camera, coach) crashed or is not responding. */
    private object NoAppErrors : ExternalResource() {
        override fun after() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(ActivityManager::class.java)
            val errors = manager.processesInErrorState.orEmpty()
            check(errors.isEmpty()) {
                errors.joinToString("\n") { "${it.processName}: ${it.shortMsg}\n${it.longMsg}\n${it.stackTrace}" }
            }
        }
    }
}

/** Long enough for the slowest step on a CI emulator: reading and analysing a session. */
const val LoadTimeoutMs = 90_000L
private const val StepTimeoutMs = 20_000L

/**
 * How many nodes match [matcher] now, matched on the main thread (FBBD-208).
 *
 * Compose's own `onAllNodes(matcher).fetchSemanticsNodes()` (ui-test 1.9) collects the nodes on the
 * main thread but matches them on the test thread. Matching a node of the merged tree reads its
 * children's semantics, and when the screen has changed them since (a corner flag tapped, a card
 * laid out by a load that finished), that read runs the owner's snapshot observer on the test
 * thread while the main thread draws with it: "Detected multithreaded access to
 * SnapshotStateObserver". The polls below run all the time the screen changes, so they look the
 * nodes up and match them in one go on the main thread, after the same wait for idle.
 */
fun SmokeRule.countNodes(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false): Int {
    waitForIdle()
    var n = 0
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        n = composeRoots().sumOf { root ->
            root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = !useUnmergedTree).count(matcher::matches)
        }
    }
    return n
}

/** Whether a node matches [matcher] now, matched on the main thread ([countNodes]). */
fun SmokeRule.exists(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false): Boolean =
    countNodes(matcher, useUnmergedTree) > 0

/** Whether a node shows [text] (or contains it, with [substring]) now ([countNodes]). */
fun SmokeRule.showsText(text: String, substring: Boolean = false, useUnmergedTree: Boolean = false): Boolean =
    exists(hasText(text, substring = substring), useUnmergedTree)

/**
 * The app's Compose roots on screen now, every window (dialogs and popups are windows of their own),
 * as the Compose test rule finds them. Main thread.
 */
private fun composeRoots(): List<ViewRootForTest> {
    val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        WindowInspector.getGlobalWindowViews()
    } else {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).map { it.window.decorView }
    }
    val roots = mutableListOf<ViewRootForTest>()
    fun visit(view: View) {
        if (view is ViewRootForTest) {
            if (view.isAttachedToWindow && view.isLifecycleInResumedState) roots += view
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
    }
    views.forEach(::visit)
    return roots
}

/** Waits until a node shows [text] (or contains it, with [substring]) and returns the first. */
fun SmokeRule.waitForText(
    text: String,
    substring: Boolean = false,
    timeoutMs: Long = StepTimeoutMs,
): SemanticsNodeInteraction {
    waitNamed("\"$text\"", timeoutMs) { showsText(text, substring) }
    return onAllNodesWithText(text, substring = substring)[0]
}

/** Waits until a node matching [matcher] exists and returns the first. */
fun SmokeRule.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = StepTimeoutMs): SemanticsNodeInteraction {
    waitNamed(matcher.description, timeoutMs) { exists(matcher) }
    return onAllNodes(matcher)[0]
}

/** Waits until no node shows [text]. */
fun SmokeRule.waitUntilGone(text: String, substring: Boolean = false, timeoutMs: Long = StepTimeoutMs) {
    waitNamed("no \"$text\"", timeoutMs) { !showsText(text, substring) }
}

/** [waitUntil] that says what it waited for when it times out. */
fun SmokeRule.waitNamed(what: String, timeoutMs: Long = StepTimeoutMs, condition: () -> Boolean) {
    try {
        waitUntil(timeoutMs, condition)
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("Timed out after $timeoutMs ms waiting for $what", e)
    }
}

/** A tab, of a tab row or the bottom bar, named [title]. */
fun tab(title: String): SemanticsMatcher =
    hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

/** Opens the tab or bottom-bar destination [title]. */
fun SmokeRule.openTab(title: String) {
    waitFor(tab(title)).performClick()
    waitForIdle()
}

/** Opens Sessions from the bottom bar and waits for the seeded sessions. */
fun SmokeRule.openSessions() {
    openTab("Sessions")
    waitForText(SmokeFixtures.VideoCar, substring = true)
}

/** Scrolls the Sessions list to the session of [car] and opens its lap analysis. */
fun SmokeRule.openSession(car: String) {
    onNode(hasScrollToNodeAction()).performScrollToNode(hasText(car, substring = true))
    // The card's detail line; a tap there opens the card.
    onNodeWithText(car, substring = true, useUnmergedTree = true).performClick()
    waitForText("Lap analysis")
}

/** Android's back, as the system back gesture. */
fun SmokeRule.back() {
    Espresso.pressBack()
    waitForIdle()
}

/**
 * Runs [block] with the test's frame clock under manual control, for the lap review: its player
 * asks for every frame (CompareView's frame loop), so Compose never goes idle there and the
 * automatic clock would wait for it forever. Inside, wait with the pump helpers, which advance the
 * clock a frame at a time, and leave the lap review before [block] ends.
 */
fun <T> SmokeRule.withManualClock(block: () -> T): T {
    mainClock.autoAdvance = false
    try {
        return block()
    } finally {
        // Lets the screen that was left go before the clock runs by itself again.
        pumpFor(FrameMs * SettleFrames)
        mainClock.autoAdvance = true
    }
}

/** With the manual clock: advances frames until [condition] holds. */
fun SmokeRule.pumpUntil(timeoutMs: Long = StepTimeoutMs, what: String = "a condition", condition: () -> Boolean) {
    val end = SystemClock.uptimeMillis() + timeoutMs
    while (!condition()) {
        if (SystemClock.uptimeMillis() > end) throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
        mainClock.advanceTimeByFrame()
        Thread.sleep(FrameMs)
    }
}

/** With the manual clock: advances frames for about [ms] of real time, as the screen would run. */
fun SmokeRule.pumpFor(ms: Long) {
    val end = SystemClock.uptimeMillis() + ms
    while (SystemClock.uptimeMillis() < end) {
        mainClock.advanceTimeByFrame()
        Thread.sleep(FrameMs)
    }
}

/** With the manual clock: [waitForText]. */
fun SmokeRule.pumpUntilText(
    text: String,
    substring: Boolean = false,
    timeoutMs: Long = StepTimeoutMs,
): SemanticsNodeInteraction {
    pumpUntil(timeoutMs, "\"$text\"") { showsText(text, substring) }
    return onAllNodesWithText(text, substring = substring)[0]
}

/** With the manual clock: [waitFor]. */
fun SmokeRule.pumpUntilNode(matcher: SemanticsMatcher, timeoutMs: Long = StepTimeoutMs): SemanticsNodeInteraction {
    pumpUntil(timeoutMs, matcher.description) { exists(matcher) }
    return onAllNodes(matcher)[0]
}

/** With the manual clock: Android's back. */
fun SmokeRule.pumpBack() {
    Espresso.pressBack()
    pumpFor(FrameMs * SettleFrames)
}

private const val FrameMs = 16L
private const val SettleFrames = 30

/**
 * The seeded data, the same for every test in the process: [reset], after a test that adds to it,
 * takes away what it added (sessions, tracks, Demo GNSS), also when the test failed half way.
 */
object SmokeData {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessionsDir get() = File(app.filesDir, "sessions")
    private val tracksDir get() = File(app.filesDir, "tracks")

    /** The sessions on disk now. */
    fun sessions(): List<SessionSummary> = SessionStore(sessionsDir).list()

    /** The saved tracks on disk now. */
    fun tracks(): List<Track> = TrackStore(tracksDir).all()

    /** Saves [track] as the track editor would, and has the app read the tracks again. */
    fun saveTrack(track: Track) {
        TrackStore(tracksDir).save(track)
        Tracks.get(app).refresh()
    }

    /** [SmokeFixtures.seedOld], then the app reads the sessions and tracks again. */
    fun seedOld() {
        SmokeFixtures.seedOld(app.filesDir, ::corpus)
        Sessions.get(app).refresh()
        Tracks.get(app).refresh()
    }

    /** [ReviewFixtures.seed], then the app reads the sessions and tracks again. */
    fun seedReview() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        ReviewFixtures.seed(app.filesDir) { name -> assets.open(name) }
        Sessions.get(app).refresh()
        Tracks.get(app).refresh()
    }

    /** The session store the app reads, for a test that checks what a screen shows against the data. */
    fun store(): SessionStore = SessionStore(sessionsDir)

    /** The id of the session of [car]. */
    fun sessionOf(car: String): String = sessions().first { it.info.car == car }.id

    /** A file of the analysis parity corpus, as text. */
    fun corpus(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }

    /** Writes [text] to a file in the app's cache, for a stubbed file picker to hand over. */
    fun cacheFile(name: String, text: String): File = File(app.cacheDir, name).apply { writeText(text) }

    fun reset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val recorder = RecorderClient.get(app)
            if (recorder.state.recording || recorder.state.starting) recorder.stop()
        }
        val end = SystemClock.uptimeMillis() + LoadTimeoutMs
        while (sessions().any { it.state == SessionState.RECORDING } && SystemClock.uptimeMillis() < end) {
            Thread.sleep(PollMs)
        }
        sessions().filter { it.info.car !in SmokeFixtures.Cars }.forEach { it.dir.deleteRecursively() }
        val tracks = TrackStore(tracksDir)
        tracks.all().filter { it.id !in SmokeFixtures.TrackIds }.forEach { tracks.delete(it.id) }
        instrumentation.runOnMainSync {
            SetupRepo.get(app).update { s ->
                val demo = s.equipment.filter { it.model == EquipmentModel.DEMO_GNSS }
                demo.fold(s) { acc, e -> acc.removeEquipment(e.id) }
            }
        }
        Sessions.get(app).refresh()
        Tracks.get(app).refresh()
    }

    private const val PollMs = 200L
}

/** The app's processes running now, by what follows the package name: "" for the UI, ":recorder" and so on. */
fun appProcesses(): Set<String> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = context.getSystemService(ActivityManager::class.java)
    return manager.runningAppProcesses.orEmpty()
        .map { it.processName }
        .filter { it.startsWith(context.packageName) }
        .map { it.removePrefix(context.packageName) }
        .toSet()
}

/** The app's processes that crashed or stopped responding, with Android's reason. */
fun appProcessErrors(): List<String> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = context.getSystemService(ActivityManager::class.java)
    return manager.processesInErrorState.orEmpty().map { "${it.processName}: ${it.shortMsg}" }
}
