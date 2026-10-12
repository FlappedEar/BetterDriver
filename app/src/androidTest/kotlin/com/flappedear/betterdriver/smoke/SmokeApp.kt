package com.flappedear.betterdriver.smoke

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.shell.AppPrefs
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import java.io.File

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
        .outerRule(GrantPermissionRule.grant(*permissions()))
        .around(Seeded)
        .around(NoAppErrors)
        .around(compose)

    private fun permissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

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
            listOf("sessions", "tracks", "coach").forEach { File(app.filesDir, it).deleteRecursively() }
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

/** Waits until a node shows [text] (or contains it, with [substring]) and returns the first. */
fun SmokeRule.waitForText(
    text: String,
    substring: Boolean = false,
    timeoutMs: Long = StepTimeoutMs,
): SemanticsNodeInteraction {
    waitNamed("\"$text\"", timeoutMs) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    return onAllNodesWithText(text, substring = substring)[0]
}

/** Waits until a node matching [matcher] exists and returns the first. */
fun SmokeRule.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = StepTimeoutMs): SemanticsNodeInteraction {
    waitNamed(matcher.description, timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    return onAllNodes(matcher)[0]
}

/** Waits until no node shows [text]. */
fun SmokeRule.waitUntilGone(text: String, substring: Boolean = false, timeoutMs: Long = StepTimeoutMs) {
    waitNamed("no \"$text\"", timeoutMs) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()
    }
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
    pumpUntil(timeoutMs, "\"$text\"") {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    return onAllNodesWithText(text, substring = substring)[0]
}

/** With the manual clock: [waitFor]. */
fun SmokeRule.pumpUntilNode(matcher: SemanticsMatcher, timeoutMs: Long = StepTimeoutMs): SemanticsNodeInteraction {
    pumpUntil(timeoutMs, matcher.description) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    return onAllNodes(matcher)[0]
}

/** With the manual clock: Android's back. */
fun SmokeRule.pumpBack() {
    Espresso.pressBack()
    pumpFor(FrameMs * SettleFrames)
}

private const val FrameMs = 16L
private const val SettleFrames = 30
