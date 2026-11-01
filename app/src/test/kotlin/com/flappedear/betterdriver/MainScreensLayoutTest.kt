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
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import com.flappedear.betterdriver.core.ui.testing.AppScreen
import com.flappedear.betterdriver.core.ui.testing.Devices
import com.flappedear.betterdriver.core.ui.testing.RegisterComponentActivity
import com.flappedear.betterdriver.core.ui.testing.assertLayoutClean
import com.flappedear.betterdriver.core.ui.testing.deviceLayout
import com.flappedear.betterdriver.core.ui.testing.edgeToEdge
import com.flappedear.betterdriver.core.ui.testing.saveScreenshot
import com.flappedear.betterdriver.obd.ObdLinkState
import com.flappedear.betterdriver.obd.ObdReading
import com.flappedear.betterdriver.obd.ObdStatus
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.ExportState
import com.flappedear.betterdriver.recording.layoutTrackState
import com.flappedear.betterdriver.setup.CanIdRecord
import com.flappedear.betterdriver.setup.CarParameter
import com.flappedear.betterdriver.setup.CarParameterSource
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.CarScanRecord
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.TrackScreens
import com.flappedear.betterdriver.sharing.TransferState
import com.flappedear.betterdriver.shell.AppNavigation
import com.flappedear.betterdriver.shell.CarPage
import com.flappedear.betterdriver.shell.CarPidsPage
import com.flappedear.betterdriver.shell.CarScanState
import com.flappedear.betterdriver.shell.HomeScreen
import com.flappedear.betterdriver.shell.ObdAdapterLabel
import com.flappedear.betterdriver.shell.ObdScreen
import com.flappedear.betterdriver.shell.ObdView
import com.flappedear.betterdriver.shell.ParameterDraft
import com.flappedear.betterdriver.shell.ParameterStep
import com.flappedear.betterdriver.shell.ParameterSteps
import com.flappedear.betterdriver.shell.SessionCardActions
import com.flappedear.betterdriver.shell.SessionFacts
import com.flappedear.betterdriver.shell.SessionTransferState
import com.flappedear.betterdriver.shell.SessionsScreen
import com.flappedear.betterdriver.shell.SettingsScreen
import com.flappedear.betterdriver.shell.SettingsTopic
import com.flappedear.betterdriver.shell.SetupScreen
import com.flappedear.betterdriver.shell.TrackLayoutSection
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
 * And state that must survive a rotation (the activity recreated) does: the name typed into New
 * track here in [NewTrackRotationTest].
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
            actions = SessionCardActions(),
            onStart = {},
            onAddReceiver = {},
        )
    }

    @Test
    fun obd() = checkScreen("obd", Routes.Settings) {
        ObdScreen(
            ObdView(
                recording = false,
                status = ObdStatus(
                    ObdLinkState.Connected,
                    "vLinker MC+ · ELM327 v2.3 · ISO 15765-4 CAN (11 bit, 500 kbaud) · 12.4V",
                    listOf(
                        ObdReading("rpm", "RPM", "rpm", 2850.0, 9.1),
                        ObdReading("speed", "Speed", "km/h", 92.0, 9.0),
                        ObdReading("throttle", "Throttle", "%", 34.1, 9.0),
                        ObdReading("engine_load", "Engine load", "%", 41.2, 4.5),
                        ObdReading("fuel_level", "Fuel level", "%", 62.0, 0.2),
                    ),
                ),
                adapter = ObdAdapterLabel("vLinker MC+", "Bluetooth"),
            ),
            title = "Track car",
            onBack = {},
        )
    }

    @Test
    fun carPage() = checkScreen("car", Routes.Car) {
        CarPage(
            car = CarProfile("c", "Track car", "2013 Scion FR-S"),
            chosen = true,
            obd = ObdView(recording = false, status = null, adapter = null),
        )
    }

    @Test
    fun carPageForReview() = checkScreen("car_review_only", Routes.Car) {
        // A tablet only reviews (ReviewOnlyDevice): the car's name and notes, no adapter or parameters.
        CarPage(
            car = CarProfile("c", "Track car", "2013 Scion FR-S"),
            chosen = true,
            obd = null,
            records = false,
        )
    }

    @Test
    fun carPageWithParameters() = checkScreen("car_parameters", Routes.Car) {
        // FBBD-344, UI-300 A: own parameters and a scan, on a phone.
        CarPage(
            car = CarProfile(
                "c", "Track car", "2013 Scion FR-S",
                parameters = listOf(
                    CarParameter(
                        "wheel", "Wheel speed RL", CarParameterSource.CAN,
                        "bytesToUIntLe(raw, 4, 2) * 0.015694", "km/h", canId = 0x0D4,
                    ),
                    CarParameter(
                        "trim", "Fuel trim", CarParameterSource.OBD, "(A-128)*100.0/128", "%",
                        header = 0x7E0, pid = 0x06,
                    ),
                ),
                scan = CarScanRecord(
                    0, "A6", listOf(0x00, 0x05, 0x0C, 0x0F, 0x11, 0x20, 0x42, 0x5A), "JF1ZNAA1", canHeard = true,
                    canIds = listOf(CanIdRecord(0x0D0, 52.0, 8, listOf(0, 1), "2C01000000000000")),
                ),
            ),
            chosen = true,
            obd = ObdView(recording = false, status = null, adapter = null),
            scan = CarScanState(start = {}),
        )
    }

    @Test
    fun carPids() = checkScreen("car_pids", Routes.Car) {
        CarPidsPage(
            car = CarProfile("c", "Track car", scan = CarScanRecord(0, "A6", listOf(0x05, 0x0C, 0x1F, 0x42, 0x5A))),
            recorded = setOf(0x05, 0x0C),
            readings = listOf(ObdReading("rpm", "RPM", "rpm", 2850.0, 9.1)),
            onSwitch = { _, _ -> },
            onAdd = {},
            onBack = {},
        )
    }

    @Test
    fun parameterSteps() = checkScreen("car_parameter_steps", Routes.Car) {
        ParameterSteps(
            draft = ParameterDraft.can(0x0D4).copy(equation = "bytesToUIntLe(raw, 4, 2) * 0.015694", unit = "km/h"),
            step = ParameterStep.Formula,
            frame = CanIdRecord(0x0D4, 50.0, 8, listOf(4, 5), "1A003C00E80F007B"),
            live = ObdReading("custom_draft", "Draft", "km/h", 62.4, 20.0),
            trying = true,
            onChange = {},
            onStep = {},
            onTry = {},
            onSave = {},
            onRemove = null,
            onBack = {},
        )
    }

    @Test
    fun homeNothingSetUp() = checkScreen("home_nothing_set_up", Routes.Drive) {
        HomeScreen(
            setup = Setup.fresh { "phone" },
            recording = false,
            trackName = null,
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
            facts = mapOf("a" to SessionFacts(laps = 14, bestMillis = 112_410, obd = true, heartRate = true)),
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
    fun settings() = checkScreen("settings", Routes.Settings) { SettingsAt(null) }

    @Test
    fun settingsEquipment() = settings(SettingsTopic.Equipment)

    @Test
    fun settingsCars() = settings(SettingsTopic.Cars)

    @Test
    fun settingsVideo() = settings(SettingsTopic.Video)

    @Test
    fun settingsApp() = settings(SettingsTopic.App)

    @Test
    fun settingsTrackScreen() = settings(SettingsTopic.TrackScreen)

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

    /** A topic's page, as opened from the Settings page (UI-137 B). */
    private fun settings(topic: SettingsTopic) = checkScreen("settings_${topic.name.lowercase()}", null) {
        SettingsAt(topic)
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
private fun SettingsAt(topic: SettingsTopic?) {
    SettingsScreen(
        setup = Setup.fresh { "phone" },
        version = "0.3.2",
        topic = topic,
        onOpenTopic = {},
        onBack = if (topic == null) null else ({}),
        onOpenEquipment = {},
        onAddEquipment = {},
        onOpenCar = {},
        onAddCar = {},
        onRunSetup = {},
        trackCount = 2,
        trackLayout = {
            val screens = TrackScreens(
                main = listOf("speed", "g", "obd_coolant_temp", "obd_oil_temp", "heart_rate", null),
                second = listOf("obd_rpm", "obd_throttle", "last_lap", "best_lap"),
            )
            TrackLayoutSection(
                layoutTrackState(screens, true, true),
                choices = { emptyList() },
                onChoose = { _, _, _ -> },
                onReset = {},
                cars = listOf(CarProfile("frs", "FR-S"), CarProfile("corolla", "Corolla")),
                carId = "frs",
                nextCarId = "frs",
            )
        },
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

/**
 * A name half typed into New track here survives a rotation (the activity recreated), dialog and
 * all.
 *
 * In a tablet window only. Under Robolectric a dialog at the platform's default width never lets
 * Compose get idle in a window narrower than 600 dp: a bare AlertDialog with nothing typed does
 * it, a bare Dialog does it at any width, and the same dialog without the default width does not.
 * Keeping the name is the same code at any width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = Devices.TabletPortrait)
class NewTrackRotationTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    @Test
    fun newTrackNameSurvivesRotation() {
        compose.edgeToEdge()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AppScreen { TracksNearKielce() } }
        compose.onNodeWithText("Not on the list", substring = true).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Kartodrom")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("New track here").assertExists()
        compose.onNodeWithText("Kartodrom").assertExists()
    }
}
