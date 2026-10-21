package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recording.DemoReplay
import com.flappedear.betterdriver.sessionanalysis.sessionLabel
import com.flappedear.betterdriver.timing.demo.DemoCircuit
import com.flappedear.betterdriver.timing.track.TrackEvent
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The Demo track receiver (FBBD-233) as Arek uses it without a track day: added under Devices, it
 * saves the demo circuit's track on its own, and a session started with it is timed at that track
 * with the coach process running, as at a real circuit. The session is then one of the Demo track's
 * Drives (FBBD-235), and the Silesia Ring and Jastrząb laps built into the app drive at their own
 * tracks (FBBD-237, FBBD-254).
 */
@RunWith(AndroidJUnit4::class)
class DemoTrackSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @After
    fun reset() {
        // The choice outlives the Demo track item: the next test drives the demo circuit again.
        DemoReplay.choose(InstrumentationRegistry.getInstrumentation().targetContext, null)
        SmokeData.reset()
    }

    @Test
    fun aSessionOnTheDemoTrackIsTimedAtItsTrack() {
        addDemoTrack()
        val id = driveASession()

        check(SmokeData.tracks().any { it.id == DemoCircuit.TrackId }) { "the demo circuit's track was not saved" }
        assertTimedAt(id, DemoCircuit.TrackId)

        // Arek, 0.3.35: Drives listed only the demo circuit. A session on the phone is one of its choices.
        openDrives()
        val start = SmokeData.sessions().single { it.id == id }.info.startUtcMillis
        app.waitForText(sessionLabel(start), substring = true)
    }

    @Test
    fun theBuiltInSilesiaRingLapsAreTimedAtSilesiaRing() {
        addDemoTrack()
        openDrives()
        app.waitForText("Silesia Ring", substring = true).performClick()
        app.waitUntilGone("Cancel")
        app.waitForText("Silesia Ring", substring = true)
        // The Demo track's own screen has no bottom bar: back to Equipment first.
        app.back()
        val id = driveASession()

        check(SmokeData.tracks().any { it.id == SilesiaRingTrackId }) { "Silesia Ring's track was not saved" }
        assertTimedAt(id, SilesiaRingTrackId)
    }

    @Test
    fun theBuiltInJastrzabLapsAreTimedAtJastrzab() {
        addDemoTrack()
        openDrives()
        app.waitForText("Autodrom Jastrząb", substring = true).performClick()
        app.waitUntilGone("Cancel")
        app.waitForText("Autodrom Jastrząb", substring = true)
        app.back()
        val id = driveASession()

        check(SmokeData.tracks().any { it.id == JastrzabTrackId }) { "Jastrząb's track was not saved" }
        assertTimedAt(id, JastrzabTrackId)
    }

    private fun addDemoTrack() {
        app.openSettingsTopic("Equipment")
        app.waitForText("+ Add equipment").performClick()
        app.waitForText("Laps and coach cues on a made-up circuit").performScrollTo().performClick()
        app.waitFor(hasText("Demo track") and hasClickAction())
    }

    /** Settings › Equipment › Demo track › Drives, the dialog open. */
    private fun openDrives() {
        app.openSettingsTopic("Equipment")
        app.waitFor(hasText("Demo track") and hasClickAction()).performClick()
        app.waitForText("Drives").performClick()
    }

    /** Starts a session on the Drive tab, drives it a while and stops it; its id once saved. */
    private fun driveASession(): String {
        val before = SmokeData.sessions().map { it.id }.toSet()
        app.openTab("Drive")
        app.waitUntilGone("Add a GNSS receiver to start")

        app.waitForText("START").performClick()
        app.withManualClock {
            app.pumpUntil(LoadTimeoutMs, "the recorder and coach processes") {
                appProcesses().containsAll(Processes)
            }
            app.pumpFor(DrivingMs)
            check(appProcessErrors().isEmpty()) { appProcessErrors().joinToString("\n") }
            app.onRoot().performTouchInput { down(center) }
            app.pumpUntilText("Hold to stop", substring = true)
            app.pumpUntilText("START", timeoutMs = LoadTimeoutMs)
            runCatching { app.onRoot().performTouchInput { up() } }
        }
        app.waitNamed("the session to be saved", LoadTimeoutMs) {
            SmokeData.sessions().any { it.id !in before && it.state == SessionState.FINISHED }
        }
        return SmokeData.sessions().single { it.id !in before }.id
    }

    private fun assertTimedAt(id: String, trackId: String) {
        val events = SmokeData.store().read(id).events
        check(TrackEvent.savedTrackId(events) == trackId) {
            "the session was not timed at $trackId: ${events.filter { it.kind == TrackEvent.KIND }}"
        }
    }

    private companion object {
        val Processes = listOf(":recorder", ":coach")

        /** The track of the built-in Silesia Ring laps (app/src/main/assets/demo). */
        const val SilesiaRingTrackId = "silesia-ring"

        /** The track of the built-in Jastrząb laps (FBBD-254). */
        const val JastrzabTrackId = "jastrzab"

        /** Long enough for the first fix and the track to be picked. */
        const val DrivingMs = 20_000L
    }
}
