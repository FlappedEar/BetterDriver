package com.flappedear.betterdriver

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.flappedear.betterdriver.feature.track.TrackScreen
import com.flappedear.betterdriver.gopro.GoPros
import com.flappedear.betterdriver.recording.GnssRef
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.lastSessionLine
import com.flappedear.betterdriver.recording.liveTrackState
import com.flappedear.betterdriver.recording.vboFileName
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.GoProCardState
import com.flappedear.betterdriver.shell.HomeScreen
import com.flappedear.betterdriver.shell.SessionCardActions
import com.flappedear.betterdriver.shell.SessionsScreen
import com.flappedear.betterdriver.shell.SettingsScreen
import com.flappedear.betterdriver.shell.SettingsTab
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.shell.SetupScreen
import com.flappedear.betterdriver.shell.ShellIcons
import com.flappedear.betterdriver.shell.rememberAppPrefs
import com.flappedear.betterdriver.timing.live.placeName
import com.flappedear.betterdriver.tracks.Tracks

private data class TopLevelItem(val route: String, val label: String, val icon: ImageVector)

private val TopLevelItems = listOf(
    TopLevelItem(Routes.Drive, "Drive", ShellIcons.Drive),
    TopLevelItem(Routes.Sessions, "Sessions", ShellIcons.Sessions),
    TopLevelItem(Routes.Settings, "Settings", ShellIcons.Settings),
)

/** VBO has no registered type; a generic one keeps the .vbo file name. */
private const val VboMimeType = "application/octet-stream"

/** GoPro HERO8 files the user adds to a session (UI-32). */
private const val Mp4MimeType = "video/mp4"

private val Version = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

/**
 * App shell from UI review round 2: bottom bar with Drive, Sessions and
 * Settings (UI-11 A), home with a big start button (UI-12 B), a first-run
 * checklist (UI-13 B), best-match pairing (UI-14 B) and tabbed settings
 * (UI-15 B). The track screen has no bar and stops with a 3 s hold (UI-16 A).
 *
 * Recording runs in the recorder process ([recorder]); this UI only starts, stops and follows it.
 * If the UI restarts during a recording it reconnects and returns to the track screen (FBBD-69).
 */
@Composable
fun BetterDriverApp(recorder: RecorderClient, sessions: Sessions) {
    val nav = rememberNavController()
    val prefs = rememberAppPrefs()
    val context = LocalContext.current
    val tracks = remember { Tracks.get(context) }
    val repo = remember { SetupRepo.get(context) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Chosen once: changing a NavHost's start destination rebuilds its graph.
    // Setup again if a required permission was revoked or a one-time grant expired.
    val start = remember { if (prefs.setupDone && prefs.hasRequiredPermissions()) Routes.Drive else Routes.Setup }
    FollowRecording(nav, recorder, sessions)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (route != null && route in Routes.TopLevel) BottomBar(nav, route) },
    ) { padding ->
        // Insets are applied per destination: the track screen owns the full
        // window so its dark background also fills the system bar areas.
        NavHost(navController = nav, startDestination = start) {
            composable(Routes.Setup) {
                Box(Modifier.padding(padding)) { Setup(nav, prefs, repo) }
            }
            composable(Routes.Drive) {
                Box(Modifier.padding(padding)) { Drive(nav, prefs, repo, recorder, sessions, tracks) }
            }
            composable(Routes.Sessions) { Box(Modifier.padding(padding)) { SessionsRoute(sessions, repo) } }
            composable(Routes.Settings) {
                Box(Modifier.padding(padding)) {
                    Settings(nav, prefs, repo, tracks, SettingsTab.Devices, onBack = null)
                }
            }
            composable(
                Routes.SettingsTabPattern,
                arguments = listOf(navArgument(Routes.TabArg) { type = NavType.StringType }),
            ) { e ->
                val tab = SettingsTab.entries.firstOrNull { it.name == e.arguments?.getString(Routes.TabArg) }
                Box(Modifier.padding(padding)) {
                    Settings(nav, prefs, repo, tracks, tab ?: SettingsTab.Devices, onBack = { nav.popBackStack() })
                }
            }
            setupDestinations(nav, prefs, repo, padding)
            composable(Routes.Track) { Track(nav, prefs, repo, recorder) }
            composable(Routes.Tracks) { Box(Modifier.padding(padding)) { TracksRoute(nav, tracks) } }
            composable(
                Routes.TrackEditorPattern,
                arguments = listOf(navArgument(Routes.TrackIdArg) { type = NavType.StringType }),
            ) { e ->
                val id = e.arguments?.getString(Routes.TrackIdArg).orEmpty()
                Box(Modifier.padding(padding)) { TrackEditorRoute(nav, tracks, id) }
            }
        }
    }
}

/**
 * Returns to the track screen when the UI reconnects to a running recording (UI restarted, or
 * opened from the notification), and lists a recording once it ends, recovering it if needed.
 */
@Composable
private fun FollowRecording(nav: NavHostController, recorder: RecorderClient, sessions: Sessions) {
    val recording = recorder.state.recording
    LaunchedEffect(recording) {
        if (recording) {
            val current = nav.currentDestination
            if (current != null && current.route != Routes.Track) nav.navigate(Routes.Track)
        } else {
            sessions.recoverAndRefresh()
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun Drive(
    nav: NavHostController,
    prefs: AppPrefs,
    repo: SetupRepo,
    recorder: RecorderClient,
    sessions: Sessions,
    tracks: Tracks,
) {
    LaunchedEffect(Unit) { tracks.locate() }
    // Starting counts too: the recorder and the cameras are already on their way.
    val recording = recorder.state.recording || recorder.state.starting
    HomeScreen(
        setup = repo.setup,
        recording = recording,
        trackName = tracks.here?.detection?.placeName,
        lastSession = lastSessionLine(sessions.list),
        actions = SessionCardActions(
            update = repo::update,
            onAddCar = { nav.navigate(Routes.car(Routes.NewCar)) },
            onAddReceiver = { nav.navigate(Routes.AddEquipment) },
        ),
        onAddReceiver = { nav.navigate(Routes.AddEquipment) },
        onStart = {
            val active = repo.setup.active()
            if (!prefs.hasRequiredPermissions()) {
                nav.navigate(Routes.Setup) { popUpTo(Routes.Drive) { inclusive = true } }
            } else if (recording) {
                // While recording, START goes back to the running session.
                nav.navigate(Routes.Track)
            } else if (active.gnss != null) {
                // Only what this session uses starts (UI-36): its receiver, its car, its cameras (FBBD-83).
                val gnss = active.gnss?.let {
                    if (it.model == EquipmentModel.DEMO_GNSS) GnssRef.Demo else GnssRef(it.address.orEmpty(), it.name)
                }
                recorder.start(
                    active.car?.name.orEmpty(),
                    track = tracks.here?.detection?.placeName.orEmpty(),
                    gnss,
                    phoneVideo = active.cameras.firstOrNull { it.model == EquipmentModel.PHONE_CAMERA }
                        ?.videoMode
                        ?.takeIf { prefs.cameraGranted() },
                )
                nav.navigate(Routes.Track)
            }
        },
    )
}

/** A restored back stack can reopen Track after a one-time grant expired, so it checks again here. */
@Composable
private fun Track(nav: NavHostController, prefs: AppPrefs, repo: SetupRepo, recorder: RecorderClient) {
    if (prefs.hasRequiredPermissions()) {
        TrackScreen(
            state = liveTrackState(recorder.state, repo.setup.active().tiles),
            onStop = {
                recorder.stop()
                nav.popBackStack()
            },
        )
    } else {
        LaunchedEffect(Unit) {
            nav.navigate(Routes.Setup) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }
}

/**
 * Sessions list. Export asks where to save the VBO file, then writes it in the background. A session
 * with video asks for a folder instead, because the VBO and its video files must sit side by side.
 */
@Composable
private fun SessionsRoute(sessions: Sessions, repo: SetupRepo) {
    val context = LocalContext.current
    val goPros = remember(context) { GoPros.get(context) }
    var addingTo by rememberSaveable { mutableStateOf<String?>(null) }
    val pickHero8 = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = addingTo
        addingTo = null
        if (id != null) goPros.addHero8(id, uris)
    }
    // Saveable: the activity can be re-created while the file picker is open.
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VboMimeType)) { uri ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) sessions.export(id, uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) sessions.exportWithVideo(id, uri)
    }
    LaunchedEffect(Unit) { sessions.refresh() }
    SessionsScreen(
        sessions = sessions.list,
        exports = sessions.exports,
        withVideo = sessions.withVideo,
        onExport = { s ->
            exporting = s.id
            if (s.id in sessions.withVideo) pickFolder.launch(null) else pickFile.launch(vboFileName(s))
        },
        goPro = GoProCardState(
            sessions = sessions.goPro,
            work = goPros.work,
            problems = goPros.problems,
            enabled = !goPros.recording,
            hero8Listed = repo.setup.equipment.any { it.model == EquipmentModel.GOPRO_HERO8 },
            canDownload = repo.hero11Address() != null,
            onDownload = { s -> goPros.download(s.id) },
            onAddHero8 = { s ->
                addingTo = s.id
                pickHero8.launch(arrayOf(Mp4MimeType))
            },
        ),
    )
}

@Composable
private fun Setup(nav: NavHostController, prefs: AppPrefs, repo: SetupRepo) {
    SetupScreen(
        setup = repo.setup,
        onAddReceiver = { nav.navigate(Routes.AddEquipment) },
        onCameras = { nav.navigate(Routes.settingsTab(SettingsTab.Devices.name)) },
        onCar = { nav.navigate(Routes.settingsTab(SettingsTab.Car.name)) },
        onAddCar = { nav.navigate(Routes.car(Routes.NewCar)) },
        onDone = {
            prefs.updateSetupDone(true)
            nav.navigate(Routes.Drive) { popUpTo(Routes.Setup) { inclusive = true } }
        },
    )
}

@Suppress("LongParameterList")
@Composable
private fun Settings(
    nav: NavHostController,
    prefs: AppPrefs,
    repo: SetupRepo,
    tracks: Tracks,
    tab: SettingsTab,
    onBack: (() -> Unit)?,
) {
    SettingsScreen(
        setup = repo.setup,
        version = Version,
        initialTab = tab,
        onOpenEquipment = { e -> nav.navigate(Routes.equipment(e.id)) },
        onAddEquipment = { nav.navigate(Routes.AddEquipment) },
        onOpenCar = { c -> nav.navigate(Routes.car(c.id)) },
        onAddCar = { nav.navigate(Routes.car(Routes.NewCar)) },
        onRunSetup = {
            prefs.updateSetupDone(false)
            nav.navigate(Routes.Setup) { popUpTo(Routes.Drive) { inclusive = true } }
        },
        onBack = onBack,
        trackCount = tracks.list.size,
        onTracks = { nav.navigate(Routes.Tracks) },
        onChooseTiles = { tiles -> repo.update { it.chooseTiles(tiles) } },
        onChooseVideo = { cam, mode -> repo.update { it.chooseVideo(cam.id, mode) } },
    )
}

@Composable
private fun BottomBar(nav: NavHostController, route: String) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        TopLevelItems.forEach { item ->
            NavigationBarItem(
                selected = route == item.route,
                onClick = {
                    nav.navigate(item.route) {
                        // Drive is the root once setup is done, the only time the bar shows.
                        popUpTo(Routes.Drive) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}
