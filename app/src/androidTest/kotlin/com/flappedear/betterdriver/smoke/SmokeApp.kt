package com.flappedear.betterdriver.smoke

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.toSize
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.sessionanalysis.SessionMenu
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.shell.ActionsLabel
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.shell.sessionRowTag
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

/**
 * Clicks the first node matching [matcher] through its click action, as TalkBack does, found and
 * clicked in one go on the main thread ([countNodes]).
 *
 * For a control on a screen that is still laid out by loads finishing in the background, which
 * the Compose test rule does not wait for: performClick() taps where the node was when it was
 * looked up, and a card that arrived above it in between moves it, so the tap lands elsewhere
 * (FBBD-208: AnalysisSmokeTest's coach flag, whose pick never went in 90 s).
 */
fun SmokeRule.clickAction(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false) {
    waitForIdle()
    var clicked: Boolean? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val node = composeRoots()
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = !useUnmergedTree) }
            .firstOrNull(matcher::matches)
        clicked = node?.let { it.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true }
    }
    checkNotNull(clicked) { "no node matches ${matcher.description}" }
    check(clicked == true) { "${matcher.description} did not take a click" }
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
        windowManagerViews()
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

/**
 * Every window's root view before Android 10, which has no WindowInspector: dialogs and popups are
 * windows of their own, not in an activity's decor view. Read from WindowManagerGlobal, as
 * Espresso's own root lookup does there.
 */
private fun windowManagerViews(): List<View> {
    val global = Class.forName("android.view.WindowManagerGlobal")
    val instance = global.getMethod("getInstance").invoke(null)
    val field = global.getDeclaredField("mViews").apply { isAccessible = true }
    val lock = global.getDeclaredField("mLock").apply { isAccessible = true }.get(instance)
    return synchronized(checkNotNull(lock)) { (field.get(instance) as List<*>).filterIsInstance<View>() }
}

/** Waits until a node shows [text] (or contains it, with [substring]) and returns the first. */
fun SmokeRule.waitForText(
    text: String,
    substring: Boolean = false,
    timeoutMs: Long = StepTimeoutMs,
): SmokeNode {
    waitNamed("\"$text\"", timeoutMs) { showsText(text, substring) }
    return SmokeNode(this, hasText(text, substring = substring))
}

/** Waits until a node matching [matcher] exists and returns the first. */
fun SmokeRule.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = StepTimeoutMs): SmokeNode {
    waitNamed(matcher.description, timeoutMs) { exists(matcher) }
    return SmokeNode(this, matcher)
}

/**
 * Scrolls the first node matching [matcher] into view and waits until it is shown. A screen whose cards
 * arrive from background loads can lay out again just after a scroll and push the node off screen, so
 * the scroll is repeated until the node is displayed.
 */
fun SmokeRule.scrollUntilShown(matcher: SemanticsMatcher, timeoutMs: Long = StepTimeoutMs): SmokeNode =
    waitFor(matcher, timeoutMs).performScrollTo(timeoutMs)

/**
 * The first node matching [matcher], looked up again for every step, and every step run on the main
 * thread (FBBD-422), as [countNodes] does for the polls.
 *
 * Compose's own SemanticsNodeInteraction reads the node's semantics on the test thread. A screen that
 * still lays out as its background loads finish (the analysis: progress, coaching history, data
 * quality cards) draws on the main thread at the same time, and both use the owner's snapshot
 * observer: "Detected multithreaded access to SnapshotStateObserver" in a draw pass, or later a
 * Compose node detached twice when the activity ends ("Must run runDetachLifecycle() once"). Those
 * failed AnalysisSmokeTest in a different test on each run (1.28.0 release PR #495).
 *
 * Each step also waits, up to its timeout, until it holds: a card that arrives above the node after
 * a scroll moves it off screen again, and the step scrolls again rather than failing.
 */
class SmokeNode(
    private val rule: SmokeRule,
    private val matcher: SemanticsMatcher,
    private val useUnmergedTree: Boolean = false,
    /** Which of the matching nodes, in the order Compose lists them: the first by default. */
    private val index: Int = 0,
) {
    private val what get() = if (index == 0) matcher.description else "${matcher.description} [$index]"

    /** Scrolls every scrolling container around the node until it is in view, or as far as they go. */
    fun performScrollTo(timeoutMs: Long = StepTimeoutMs): SmokeNode {
        var last: Offset? = null
        rule.waitNamed("$what scrolled into view", timeoutMs) {
            val step = rule.scrollStep(matcher, useUnmergedTree, index)
            // In view, or shown and the last scroll moved it no further (the end of the list).
            val done = step.inView || (step.displayed && step.position != null && step.position == last)
            last = step.position
            done
        }
        return this
    }

    /** Waits until the node is placed and at least part of it is on screen. */
    fun assertIsDisplayed(timeoutMs: Long = StepTimeoutMs): SmokeNode =
        waitUntilNode("$what displayed", timeoutMs) { node, view -> node.isDisplayedIn(view) }

    fun assertIsEnabled(): SmokeNode =
        waitUntilNode("$what enabled") { node, _ -> SemanticsProperties.Disabled !in node.config }

    fun assertIsNotEnabled(): SmokeNode =
        waitUntilNode("$what not enabled") { node, _ -> SemanticsProperties.Disabled in node.config }

    fun assertIsSelected(): SmokeNode =
        waitUntilNode("$what selected") { node, _ -> node.config.getOrNull(SemanticsProperties.Selected) == true }

    /**
     * Clicks the node through the click action of the node or of the nearest one around it that takes
     * clicks, as a tap on it would reach, found and clicked in one go on the main thread. A disabled
     * node is not clicked, as a tap does nothing there. A node with no click action anywhere around it
     * is tapped by Compose's performClick.
     */
    fun performClick(): SmokeNode {
        rule.waitForIdle()
        var outcome = Click.NoNode
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val found = findNode(matcher, useUnmergedTree, index) ?: return@runOnMainSync
            val target = generateSequence(found.first) { it.parent }
                .firstOrNull { SemanticsActions.OnClick in it.config }
            outcome = when {
                target == null -> Click.NoAction
                SemanticsProperties.Disabled in target.config -> Click.Done
                else -> {
                    target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
                    Click.Done
                }
            }
        }
        when (outcome) {
            Click.NoNode -> throw AssertionError("no node matches $what")
            Click.NoAction -> rule.onAllNodes(matcher, useUnmergedTree)[index].performClick()
            Click.Done -> Unit
        }
        rule.waitForIdle()
        return this
    }

    /** Scrolls this list to its item matching [item] (Compose's own: a list, by its scroll-to-index action). */
    fun performScrollToNode(item: SemanticsMatcher): SmokeNode {
        rule.onAllNodes(matcher, useUnmergedTree)[index].performScrollToNode(item)
        return this
    }

    /** Types [text] in place of the field's text (Compose's own: a text field, on a screen at rest). */
    fun performTextReplacement(text: String): SmokeNode {
        rule.onAllNodes(matcher, useUnmergedTree)[index].performTextReplacement(text)
        return this
    }

    private fun waitUntilNode(
        what: String,
        timeoutMs: Long = StepTimeoutMs,
        holds: (SemanticsNode, View) -> Boolean,
    ): SmokeNode {
        rule.waitNamed(what, timeoutMs) {
            rule.waitForIdle()
            var ok = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ok = findNode(matcher, useUnmergedTree, index)?.let { (node, view) -> holds(node, view) } == true
            }
            ok
        }
        return this
    }

    private enum class Click { NoNode, NoAction, Done }
}

/** One step of [SmokeNode.performScrollTo]: whether the node was in view, shown at all, and where it was. */
private class ScrollStep(val inView: Boolean, val displayed: Boolean, val position: Offset?)

/**
 * Looks the node up on the main thread: when a scrolling container around it does not show all of it,
 * scrolls the innermost such container by what it lacks, as Compose's performScrollTo does.
 */
private fun SmokeRule.scrollStep(matcher: SemanticsMatcher, useUnmergedTree: Boolean, index: Int): ScrollStep {
    waitForIdle()
    var step = ScrollStep(inView = false, displayed = false, position = null)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val (node, view) = findNode(matcher, useUnmergedTree, index) ?: return@runOnMainSync
        val displayed = node.isDisplayedIn(view)
        val target = Rect(node.positionInRoot, node.size.toSize())
        val scroll = generateSequence(node.parent) { it.parent }
            .filter { SemanticsActions.ScrollBy in it.config }
            .map { container ->
                val viewport = container.boundsInRoot
                container to Offset(
                    lacking(target.left, target.right, viewport.left, viewport.right),
                    lacking(target.top, target.bottom, viewport.top, viewport.bottom),
                )
            }
            .firstOrNull { (_, by) -> by != Offset.Zero }
        scroll?.let { (container, by) ->
            container.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(by.x, by.y)
        }
        step = ScrollStep(inView = scroll == null && displayed, displayed = displayed, position = node.positionInRoot)
    }
    return step
}

/** How far to scroll so [start]..[end] shows in [viewStart]..[viewEnd]; its start first when it is larger. */
private fun lacking(start: Float, end: Float, viewStart: Float, viewEnd: Float): Float = when {
    start < viewStart && end > viewEnd -> 0f
    end > viewEnd -> minOf(start - viewStart, end - viewEnd)
    start < viewStart -> maxOf(start - viewStart, end - viewEnd)
    else -> 0f
}

/**
 * Whether the node is placed and at least part of it is on screen, as Compose's assertIsDisplayed
 * checks. Main thread.
 */
private fun SemanticsNode.isDisplayedIn(view: View): Boolean {
    val bounds = boundsInRoot
    val at = IntArray(2)
    view.getLocationInWindow(at)
    val visible = android.graphics.Rect()
    return layoutInfo.isPlaced && view.isShown && bounds.width > 0f && bounds.height > 0f &&
        view.getGlobalVisibleRect(visible) &&
        visible.intersects(
            (bounds.left + at[0]).toInt(),
            (bounds.top + at[1]).toInt(),
            (bounds.right + at[0]).toInt(),
            (bounds.bottom + at[1]).toInt(),
        )
}

/** The node [index] of those matching [matcher] on screen now, with the view of its Compose root. Main thread. */
private fun findNode(matcher: SemanticsMatcher, useUnmergedTree: Boolean, index: Int): Pair<SemanticsNode, View>? =
    composeRoots()
        .asSequence()
        .flatMap { root ->
            root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = !useUnmergedTree)
                .asSequence()
                .filter(matcher::matches)
                .map { it to root.view }
        }
        .elementAtOrNull(index)

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

/**
 * Opens Settings, then its topic [title] (UI-137 B). Settings is picked twice: the first may bring
 * back the topic last open there, the second goes to the list of topics.
 */
fun SmokeRule.openSettingsTopic(title: String) {
    openTab("Settings")
    openTab("Settings")
    waitFor(hasText(title) and hasClickAction()).performClick()
    waitForIdle()
}

/** Opens the ⋮ sheet of the newest session in Sessions (UI-139 C), unless it is open already. */
fun SmokeRule.openNewestSessionActions() {
    if (showsText("Export ▾")) return
    SmokeNode(this, hasContentDescription(ActionsLabel)).performClick()
    waitForText("Export ▾")
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
    SmokeNode(this, hasText(car, substring = true), useUnmergedTree = true).performClick()
    waitFor(hasContentDescription(SessionMenu))
}

/**
 * Scrolls the Sessions list to the row of session [id] and opens its lap analysis. Found by its tag:
 * the row's time text ("09:00") can also stand in a notice elsewhere on the screen.
 */
fun SmokeRule.openSessionRow(id: String) {
    onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(sessionRowTag(id)))
    SmokeNode(this, hasTestTag(sessionRowTag(id))).performClick()
    waitFor(hasContentDescription(SessionMenu))
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

    /** [SmokeFixtures.seedCommute], then the app reads the sessions again. */
    fun seedCommute() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        SmokeFixtures.seedCommute(app.filesDir) { assets.open("smoke/${SmokeFixtures.VideoFile}") }
        Sessions.get(app).refresh()
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
                val demoModels = setOf(EquipmentModel.DEMO_GNSS, EquipmentModel.DEMO_TRACK)
                val demo = s.equipment.filter { it.model in demoModels }
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
