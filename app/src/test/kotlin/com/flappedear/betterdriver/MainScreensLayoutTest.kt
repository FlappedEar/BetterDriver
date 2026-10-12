package com.flappedear.betterdriver

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.compose.rememberNavController
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.core.ui.testing.edgeToEdge
import com.flappedear.betterdriver.core.ui.testing.assertLayoutClean
import com.flappedear.betterdriver.core.ui.testing.deviceLayout
import com.flappedear.betterdriver.core.ui.testing.saveScreenshot
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.ExportState
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.sharing.TransferState
import com.flappedear.betterdriver.shell.AppNavigation
import com.flappedear.betterdriver.shell.HomeScreen
import com.flappedear.betterdriver.shell.SessionCardActions
import com.flappedear.betterdriver.shell.SessionTransferState
import com.flappedear.betterdriver.shell.SessionsScreen
import com.flappedear.betterdriver.shell.SettingsScreen
import com.flappedear.betterdriver.shell.SettingsTab
import com.flappedear.betterdriver.shell.SetupScreen
import com.flappedear.betterdriver.shell.TracksScreen
import com.flappedear.betterdriver.shell.appNavigation
import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.tracks.Here
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The app's main screens on a phone and a tablet, upright and sideways (FBBD-40), inside the same
 * bottom bar or side rail the app puts around them on that window. For each screen: no text
 * clipped, overlapping or out of the window, nothing scrolling sideways (see assertLayoutClean). A
 * picture of each is saved to build/outputs/roborazzi/layout/<device>/ for a look.
 *
 * And state that must survive a rotation (the activity recreated) does: the Settings tab, and the
 * name typed into New track here.
 *
 * The plain Application runs: BetterDriverApplication installs the app log and StrictMode, which
 * have nothing to do with layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
abstract class MainScreensLayoutTest(private val device: String) {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    @Test
    fun home() = checkScreen("home", Routes.Drive) {
        HomeScreen(
            setup = configured(),
            recording = false,
            trackName = "Tor Poznań",
            lastSession = "Yesterday · 14 laps · best 1:52.41",
            actions = SessionCardActions(),
            onStart = {},
            onAddReceiver = {},
        )
    }

    @Test
    fun homeNothingSetUp() = checkScreen("home_nothing_set_up", Routes.Drive) {
        HomeScreen(
            setup = Setup.fresh { "phone" },
            recording = false,
            trackName = null,
            lastSession = null,
            actions = SessionCardActions(),
            onStart = {},
            onAddReceiver = {},
        )
    }

    @Test
    fun sessions() = checkScreen("sessions", Routes.Sessions) {
        SessionsScreen(
            sessions = Sessions,
            exports = mapOf("a" to ExportState.Done),
            onExport = { _, _ -> },
            withVideo = setOf("a"),
            transfer = SessionTransferState(
                outgoing = mapOf("b" to TransferState("Preparing…", Progress, running = true)),
                incoming = TransferState("Imported the session of 11 Oct 10:42. Added track Tor Poznań."),
            ),
        )
    }

    @Test
    fun sessionsEmpty() = checkScreen("sessions_empty", Routes.Sessions) {
        SessionsScreen(sessions = emptyList(), exports = emptyMap(), onExport = { _, _ -> })
    }

    @Test
    fun settingsDevices() = settings(SettingsTab.Devices)

    @Test
    fun settingsCar() = settings(SettingsTab.Car)

    @Test
    fun settingsRecording() = settings(SettingsTab.Recording)

    @Test
    fun settingsApp() = settings(SettingsTab.App)

    @Test
    fun tracks() = checkScreen("tracks", null) { TracksNearKielce() }

    @Test
    fun setup() = checkScreen("setup", Routes.Setup) {
        SetupScreen(
            setup = Setup.fresh { "phone" },
            onAddReceiver = {},
            onCameras = {},
            onCar = {},
            onAddCar = {},
            onDone = {},
        )
    }

    /** Rotating the device recreates the activity: the tab picked in Settings stays picked. */
    @Test
    fun settingsTabSurvivesRotation() {
        compose.edgeToEdge()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppScreen { SettingsAt(SettingsTab.Devices) } }
        compose.onNodeWithText("App").performClick()
        compose.onNodeWithText("Run setup again").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Run setup again").assertExists()
    }

    /** A name half typed into New track here survives a rotation, dialog and all. */
    @Test
    fun newTrackNameSurvivesRotation() {
        compose.edgeToEdge()
        val restoration = StateRestorationTester(compose)
        step("showing the tracks") { restoration.setContent { AppScreen { TracksNearKielce() } } }
        val newTrack = compose.onNodeWithText("Not on the list", substring = true)
        step("scrolling to New track here") { newTrack.performScrollTo() }
        step("opening the dialog") { newTrack.performClick() }
        // Set without focusing the field: whether the name is kept does not depend on focus.
        step("typing the name") { compose.onNode(hasSetTextAction()).performTextReplacement("Kartodrom") }
        step("rotating") { restoration.emulateSavedInstanceStateRestore() }
        compose.onNodeWithText("New track here").assertExists()
        compose.onNodeWithText("Kartodrom").assertExists()
    }

    /** Runs [block]; a failure (the window never getting idle, say) names the step it happened at. */
    private fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            throw AssertionError("$what on $device: ${e.message}", e)
        }
    }

    private fun settings(tab: SettingsTab) = checkScreen("settings_${tab.name.lowercase()}", Routes.Settings) {
        SettingsAt(tab)
    }

    /**
     * Shows [screen] as the app does at [route] (with the bar or rail when it is a top-level one),
     * saves its picture and checks its layout.
     */
    private fun checkScreen(name: String, route: String?, screen: @Composable () -> Unit) {
        compose.edgeToEdge()
        compose.setContent { AppScreen { Chrome(route, screen) } }
        compose.waitForIdle()
        compose.saveScreenshot(device, name)
        compose.assertLayoutClean("$name on $device")
    }

    private companion object {
        const val Progress = 0.42f
        const val MinuteMicros = 60_000_000L

        fun configured(): Setup {
            var n = 0
            val ids = { "id${++n}" }
            return Setup.fresh(ids)
                .addEquipment(EquipmentModel.RACEBOX, "RaceBox Mini S 3241", "AA", ids)
                .addEquipment(EquipmentModel.GOPRO, "GoPro 1234", "BB", ids, GoProModel.HERO13_BLACK)
                .addEquipment(EquipmentModel.GOPRO, "GoPro 5678", "CC", ids, GoProModel.HERO10_BLACK)
                .addCar("Track car", "Any car", ids)
        }

        fun session(id: String, start: Long, state: SessionState, minutes: Long?) = SessionSummary(
            id,
            File(id),
            SessionInfo(start, car = "Club car", track = "Tor Poznań"),
            state,
            minutes?.let { it * MinuteMicros },
        )

        val Sessions = listOf(
            session("c", 1_760_190_000_000, SessionState.RECORDING, null),
            session("b", 1_760_180_000_000, SessionState.RECOVERED, 12),
            session("a", 1_760_170_000_000, SessionState.FINISHED, 18),
            SessionSummary(
                "i",
                File("i"),
                SessionInfo(
                    1_760_160_000_000,
                    track = "Autodrom Jastrząb",
                    imported = ImportOrigin("RaceChrono CSV", "session_20251011_1042.csv"),
                ),
                SessionState.FINISHED,
                21 * MinuteMicros,
            ),
        )
    }
}

/** The bottom bar or side rail the app shows around a top-level [route] on this window. */
@Composable
private fun Chrome(route: String?, screen: @Composable () -> Unit) {
    val nav = rememberNavController()
    val navigation = appNavigation(deviceLayout(), topLevel = route != null && route in Routes.TopLevel)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (navigation == AppNavigation.Bar && route != null) BottomBar(nav, route, false) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (navigation == AppNavigation.Rail && route != null) SideRail(nav, route, false)
            Row(Modifier.weight(1f)) { screen() }
        }
    }
}

@Composable
private fun SettingsAt(tab: SettingsTab) {
    SettingsScreen(
        setup = Setup.fresh { "phone" },
        version = "0.3.2",
        initialTab = tab,
        onOpenEquipment = {},
        onAddEquipment = {},
        onOpenCar = {},
        onAddCar = {},
        onRunSetup = {},
        trackCount = 2,
    )
}

@Composable
private fun TracksNearKielce() {
    val kielce = Circuit("Q1", "Tor Kielce", "PL", GeoPoint(50.8136, 20.5378))
    TracksScreen(
        here = Here(GeoPoint(50.81, 20.54), TrackDetection.Directory(kielce, 400.0)),
        tracks = listOf(
            Track("a", "Tor Poznań", GeoPoint(52.42, 16.81)),
            Track("b", "Autodrom Jastrząb", GeoPoint(51.3, 20.9)),
        ),
        onOpen = {},
        onCreateFromCircuit = {},
        onCreateHere = { _, _ -> },
        onBack = {},
    )
}

@Config(qualifiers = Devices.PhonePortrait)
class MainScreensLayoutPhonePortraitTest : MainScreensLayoutTest("phone-portrait")

@Config(qualifiers = Devices.PhoneLandscape)
class MainScreensLayoutPhoneLandscapeTest : MainScreensLayoutTest("phone-landscape")

@Config(qualifiers = Devices.TabletPortrait)
class MainScreensLayoutTabletPortraitTest : MainScreensLayoutTest("tablet-portrait")

@Config(qualifiers = Devices.TabletLandscape)
class MainScreensLayoutTabletLandscapeTest : MainScreensLayoutTest("tablet-landscape")
