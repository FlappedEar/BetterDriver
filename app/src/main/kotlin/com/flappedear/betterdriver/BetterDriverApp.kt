package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.flappedear.betterdriver.coach.CoachPrefs
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.debug.CameraTestScreen
import com.flappedear.betterdriver.debug.DebugLogRows
import com.flappedear.betterdriver.feature.track.TrackScreen
import com.flappedear.betterdriver.gopro.GoPros
import com.flappedear.betterdriver.gopro.rememberGoProFilePicker
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recording.GnssSound
import com.flappedear.betterdriver.recording.SensorRef
import com.flappedear.betterdriver.recording.gnssRefOf
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.obdAdapterLabel
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.liveTrackState
import com.flappedear.betterdriver.recording.rememberNextCorners
import com.flappedear.betterdriver.recording.rememberSessionExport
import com.flappedear.betterdriver.sessionanalysis.SessionAnalysisRoute
import com.flappedear.betterdriver.sessionanalysis.analysisDestination
import com.flappedear.betterdriver.sessionanalysis.progressionDestination
import com.flappedear.betterdriver.sessionanalysis.trackDayDestination
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.FirstRunSetup
import com.flappedear.betterdriver.setup.TrackChannel
import com.flappedear.betterdriver.setup.channelsFor
import com.flappedear.betterdriver.shell.AboutScreen
import com.flappedear.betterdriver.shell.AppNavigation
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.GoProCardState
import com.flappedear.betterdriver.shell.HomeScreen
import com.flappedear.betterdriver.shell.SettingsTrackLayout
import com.flappedear.betterdriver.shell.TrackScreenPrefs
import com.flappedear.betterdriver.shell.legacyGoProName
import com.flappedear.betterdriver.shell.listedGoPros
import com.flappedear.betterdriver.shell.SessionCardActions
import com.flappedear.betterdriver.shell.SessionCoach
import com.flappedear.betterdriver.shell.SessionDeleting
import com.flappedear.betterdriver.shell.SessionsListDetail
import com.flappedear.betterdriver.shell.SessionsScreen
import com.flappedear.betterdriver.shell.SettingsScreen
import com.flappedear.betterdriver.shell.obdRowValue
import com.flappedear.betterdriver.shell.SettingsTopic
import com.flappedear.betterdriver.shell.appNavigation
import com.flappedear.betterdriver.shell.shownSession
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.shell.SetupScreen
import com.flappedear.betterdriver.shell.rememberAppPrefs
import com.flappedear.betterdriver.sharing.BackupRows
import com.flappedear.betterdriver.sharing.FollowTransfers
import com.flappedear.betterdriver.sharing.rememberSessionTransfer
import com.flappedear.betterdriver.timing.live.placeName
import com.flappedear.betterdriver.tracks.Tracks
import com.flappedear.betterdriver.update.AppUpdates
import com.flappedear.betterdriver.update.FollowUpdates
import com.flappedear.betterdriver.update.UpdateCard
import com.flappedear.betterdriver.update.UpdateRow
import com.flappedear.betterdriver.update.hasUpdate
import com.flappedear.betterdriver.update.updatesBlocked
import com.flappedear.betterdriver.videoexport.VideoExportFlow
import com.flappedear.betterdriver.videoexport.VideoExports

private val Version = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

/**
 * App shell from UI review round 2: bottom bar with Drive, Sessions and
 * Settings (UI-11 A), home with a big start button (UI-12 B), a first-run
 * checklist (UI-13 B), best-match pairing (UI-14 B) and tabbed settings
 * (UI-15 B). The track screen has no bar and stops with a 3 s hold (UI-16 A).
 *
 * Recording runs in the recorder process ([recorder]); this UI only starts, stops and follows it.
 * If the UI restarts during a recording it reconnects and returns to the track screen (FBBD-69).
 *
 * On a tablet (FBBD-40) a navigation rail replaces the bottom bar and Sessions shows the list beside
 * the picked session's analysis. The window's size class is worked out here and passed down, so the
 * screens choose their layout from it.
 */
@Composable
fun BetterDriverApp(recorder: RecorderClient, sessions: Sessions) {
    val nav = rememberNavController()
    val prefs = rememberAppPrefs()
    val context = LocalContext.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Chosen once: changing a NavHost's start destination rebuilds its graph.
    // Setup again if a required permission was revoked or a one-time grant expired.
    // A tablet only reviews (no recording there, Arek 2026-10-30): it opens on Sessions.
    val tablet = isTabletDevice()
    val start = remember {
        when {
            tablet -> Routes.Sessions
            FirstRunSetup.opensOnSetup(tablet, prefs.setupDone, prefs.hasRequiredPermissions()) -> Routes.Setup
            else -> Routes.Drive
        }
    }
    FollowRecording(nav, recorder, sessions)
    FollowTransfers(nav, recorder)
    val updates = remember { AppUpdates.get(context) }
    FollowUpdates(updates, recorder, sessions)
    val updateDot = hasUpdate(updates.state) && !updatesBlocked(recorder, sessions)
    val layout = windowLayout()
    // A Settings topic opened from Settings keeps the bar, with Settings picked (UI-137 B).
    val inSettings = route == Routes.SettingsTopicPattern &&
        nav.previousBackStackEntry?.destination?.route == Routes.Settings
    val navigation = appNavigation(layout, topLevel = route != null && (route in Routes.TopLevel || inSettings))
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (navigation == AppNavigation.Bar && route != null) BottomBar(nav, route, updateDot) },
    ) { scaffoldPadding ->
        // The rail takes the start inset itself, so the screens beside it do not pad for it again.
        val padding = if (navigation == AppNavigation.Rail) withoutStart(scaffoldPadding) else scaffoldPadding
        Row(Modifier.fillMaxSize()) {
            if (navigation == AppNavigation.Rail && route != null) SideRail(nav, route, updateDot)
            AppNavHost(nav, start, prefs, padding, layout, recorder, sessions, Modifier.weight(1f))
        }
    }
}

/** Every destination. [layout] is the window's size class, passed to the screens that change with it. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun AppNavHost(
    nav: NavHostController,
    start: String,
    prefs: AppPrefs,
    padding: PaddingValues,
    layout: LayoutSize,
    recorder: RecorderClient,
    sessions: Sessions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tracks = remember { Tracks.get(context) }
    val repo = remember { SetupRepo.get(context) }
    // Insets are applied per destination: the track screen owns the full
    // window so its dark background also fills the system bar areas.
    NavHost(navController = nav, startDestination = start, modifier = modifier) {
        composable(Routes.Setup) {
            Box(Modifier.padding(padding)) { Setup(nav, prefs, repo) }
        }
        composable(Routes.Drive) {
            Box(Modifier.padding(padding)) { Drive(nav, prefs, repo, recorder, tracks) }
        }
        composable(Routes.Sessions) {
            Box(Modifier.padding(padding)) { SessionsRoute(nav, sessions, repo, layout) }
        }
        reviewDestination(nav, padding, layout)
        analysisDestination(nav, padding, layout)
        progressionDestination(nav, padding)
        trackDayDestination(nav, padding)
        cueReview(nav, padding, layout)
        watchVideo(nav, padding)
        composable(Routes.Settings) {
            Box(Modifier.padding(padding)) { Settings(nav, prefs, repo, tracks, topic = null, onBack = null) }
        }
        composable(
            Routes.SettingsTopicPattern,
            arguments = listOf(navArgument(Routes.TopicArg) { type = NavType.StringType }),
        ) { e ->
            val topic = SettingsTopic.entries.firstOrNull { it.name == e.arguments?.getString(Routes.TopicArg) }
            Box(Modifier.padding(padding)) {
                Settings(nav, prefs, repo, tracks, topic ?: SettingsTopic.Equipment, onBack = { nav.popBackStack() })
            }
        }
        carDestinations(nav, repo, padding)
        settingsPages(nav, padding)
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

/** Pages opened from Settings › App: the spoken coach (FBBD-98) and About and credits (FBBD-79). */
private fun NavGraphBuilder.settingsPages(nav: NavHostController, padding: PaddingValues) {
    coachSettings(nav, padding)
    composable(Routes.CameraTest) {
        Box(Modifier.padding(padding)) { CameraTestScreen(onBack = { nav.popBackStack() }) }
    }
    composable(Routes.About) {
        Box(Modifier.padding(padding)) { AboutScreen(Version, onBack = { nav.popBackStack() }) }
    }
    composable(Routes.Obd) {
        val recorder = RecorderClient.get(LocalContext.current)
        Box(Modifier.padding(padding)) { ObdRoute(recorder, onBack = { nav.popBackStack() }) }
    }
}

/**
 * Returns to the track screen when the UI reconnects to a running recording (UI restarted, or
 * opened from the notification), and lists a recording once it ends, recovering it if needed.
 */
@Composable
private fun FollowRecording(nav: NavHostController, recorder: RecorderClient, sessions: Sessions) {
    val recording = recorder.state.recording
    // A video with data never renders while a session records (FBBD-152): the recording comes first.
    val context = LocalContext.current
    val starting = recording || recorder.state.starting
    LaunchedEffect(starting) {
        if (starting) VideoExports.get(context).stopForRecording()
    }
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
    tracks: Tracks,
) {
    LaunchedEffect(Unit) { tracks.locate() }
    // Starting counts too: the recorder and the cameras are already on their way.
    val recording = recorder.state.recording || recorder.state.starting
    HomeScreen(
        // While recording, the card shows what the running session uses, not later Settings changes.
        setup = if (recording) repo.forSession() else repo.setup,
        recording = recording,
        trackName = tracks.here?.detection?.placeName,
        actions = SessionCardActions(
            update = repo::update,
            onAddCar = { nav.navigate(Routes.car(Routes.NewCar)) },
            onAddReceiver = { nav.navigate(Routes.AddEquipment) },
        ),
        onAddReceiver = { nav.navigate(Routes.AddEquipment) },
        cameraAllowed = prefs.cameraGranted(),
        onStart = {
            val active = repo.setup.active()
            if (!prefs.hasRequiredPermissions()) {
                nav.navigate(Routes.Setup) { popUpTo(Routes.Drive) { inclusive = true } }
            } else if (recording) {
                // While recording, START goes back to the running session.
                nav.navigate(Routes.Track)
            } else if (active.gnss != null) {
                repo.sessionStarted()
                // Only what this session uses starts (UI-36): its receiver, its car, its cameras (FBBD-83).
                val gnss = active.gnss?.let(::gnssRefOf)
                recorder.start(
                    active.car?.name.orEmpty(),
                    // Only a fresh position names the session: a stale one could name last weekend's circuit.
                    track = tracks.here?.takeIf { it.fresh() }?.detection?.placeName.orEmpty(),
                    gnss,
                    phoneVideo = active.cameras.firstOrNull { it.model == EquipmentModel.PHONE_CAMERA }
                        ?.videoMode
                        ?.takeIf { prefs.cameraGranted() },
                    // The listed strap, as on iOS (FBBD-349).
                    sensors = listOfNotNull(active.heartRateStrap).mapNotNull { s ->
                        s.address?.let { SensorRef(it, s.advertisedName ?: s.name) }
                    },
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
        GnssSound(recorder.state.live?.gnss, enabled = prefs.gnssSound)
        val session = repo.forSession()
        val gnss = session.active().gnss
        val hasG = TrackChannel.G in channelsFor(gnss)
        // Read once as the track screen opens: the coach line does not come and go while driving (UI-142 B).
        val context = LocalContext.current
        val coachOn = remember { CoachPrefs.get(context).settings.verbosity != Verbosity.Off }
        // The line plan's next corner on a third screen (UI-171 B, UI-172 C), at a saved track with one.
        val corners = rememberNextCorners(recorder.state.trackId)
        TrackScreen(
            brightness = prefs.trackBrightness,
            onReport = { DebugLog.i("TrackScreen", it) },
            state = liveTrackState(recorder.state, session.trackScreens, hasG).copy(
                coachOn = coachOn,
                mapOn = corners != null,
                nextCorner = corners?.at(recorder.state.live?.latitude, recorder.state.live?.longitude),
            ),
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
 * Sessions list. Export asks where to save, then writes in the background (rememberSessionExport).
 * On a tablet ([layout]) a card opens its lap analysis beside the list (FBBD-40); the picked session
 * is kept when the tablet turns and when Android ends the app in the background.
 */
@Composable
private fun SessionsRoute(nav: NavHostController, sessions: Sessions, repo: SetupRepo, layout: LayoutSize) {
    val context = LocalContext.current
    val goPros = remember(context) { GoPros.get(context) }
    val addFiles = rememberGoProFilePicker(goPros)
    val export = rememberSessionExport(sessions)
    LaunchedEffect(Unit) { sessions.refresh() }
    val updates = remember(context) { AppUpdates.get(context) }
    val recorder = RecorderClient.get(context)
    val updatesBlocked = updatesBlocked(recorder, sessions)
    val recording = recorder.state.recording || recorder.state.starting
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = shownSession(picked, sessions.list.map { it.id })
    VideoExportFlow(sessions.list, recording, onWatch = { s -> nav.navigate(Routes.watchVideo(s.id)) }) { videoExport ->
        SessionsListDetail(
            layout = layout,
            shown = shown,
            onClose = { picked = null },
            detail = { id, pane, onBack, listToggle ->
                SessionAnalysisRoute(nav, id, pane, onBack, listToggle = listToggle)
            },
        ) { listModifier ->
            SessionsScreen(
                modifier = listModifier,
                header = {
                    SessionsHeader(sessions.list.any(), updates, updatesBlocked) { nav.navigate(Routes.Review) }
                },
                transfer = rememberSessionTransfer(recording = recording),
                videoExport = videoExport,
                sessions = sessions.list,
                exports = sessions.exports,
                withVideo = sessions.withVideo,
                onAnalyse = { s -> if (layout.tablet) picked = s.id else nav.navigate(Routes.sessionAnalysis(s.id)) },
                // The whole day at one track (FBBD-315, UI-210 B).
                onTrackDay = { ids -> nav.navigate(Routes.trackDay(ids)) },
                selectedId = shown.takeIf { layout.tablet },
                facts = rememberSessionFacts(sessions),
                onExport = export,
                goPro = GoProCardState(
                    sessions = sessions.goPro,
                    work = goPros.work,
                    problems = goPros.problems,
                    enabled = !goPros.recording,
                    listed = listedGoPros(repo.setup),
                    nameOf = { id -> repo.goProFor(id)?.name ?: legacyGoProName(id) },
                    onDownload = { s, camera -> goPros.download(s.id, camera) },
                    onAddFiles = { s, camera -> addFiles(s.id, camera) },
                ),
                deleting = SessionDeleting(
                    sizes = sessions.sizes,
                    states = sessions.deletes,
                    onDelete = { s -> sessions.delete(s.id) },
                ),
                coach = SessionCoach(sessions.cues) { s -> nav.navigate(Routes.cueReview(s.id)) },
            )
        }
    }
}

/** Above the sessions list: "Compare laps" once there are sessions, then the update card. */
@Composable
private fun SessionsHeader(
    hasSessions: Boolean,
    updates: AppUpdates,
    updatesBlocked: Boolean,
    onCompareLaps: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hasSessions) {
            FilledTonalButton(onClick = onCompareLaps, modifier = Modifier.fillMaxWidth()) {
                Text("Compare laps")
            }
        }
        UpdateCard(updates, updatesBlocked)
    }
}

@Composable
private fun Setup(nav: NavHostController, prefs: AppPrefs, repo: SetupRepo) {
    SetupScreen(
        setup = repo.setup,
        onAddReceiver = { nav.navigate(Routes.AddEquipment) },
        onCameras = { nav.navigate(Routes.settingsTopic(SettingsTopic.Equipment.name)) },
        onCar = { nav.navigate(Routes.settingsTopic(SettingsTopic.Cars.name)) },
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
    topic: SettingsTopic?,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val updates = remember(context) { AppUpdates.get(context) }
    val updatesBlocked = updatesBlocked(RecorderClient.get(context), Sessions.get(context))
    val coach = remember(context) { CoachPrefs.get(context) }
    val firstAdapter = repo.setup.active().carData.firstOrNull()
    val obdAdapter = remember(firstAdapter) { obdAdapterLabel(context, firstAdapter) }
    SettingsScreen(
        updateRow = { UpdateRow(updates, updatesBlocked) },
        debugRows = { DebugLogRows(onCameraTest = { nav.navigate(Routes.CameraTest) }) },
        backupRows = { BackupRows() },
        setup = repo.setup,
        version = Version,
        topic = topic,
        onOpenTopic = { t ->
            nav.navigate(
                when (t) {
                    SettingsTopic.Tracks -> Routes.Tracks
                    SettingsTopic.Coach -> Routes.CoachSettings
                    else -> Routes.settingsTopic(t.name)
                },
            )
        },
        onOpenEquipment = { e -> nav.navigate(Routes.equipment(e.id)) },
        onAddEquipment = { nav.navigate(Routes.AddEquipment) },
        onOpenCar = { c -> nav.navigate(Routes.carPage(c.id)) },
        onAddCar = { nav.navigate(Routes.car(Routes.NewCar)) },
        onRunSetup = {
            prefs.updateSetupDone(false)
            nav.navigate(Routes.Setup) { popUpTo(Routes.Drive) { inclusive = true } }
        },
        onBack = onBack,
        trackCount = tracks.list.size,
        onChooseVideo = { cam, mode -> repo.update { it.chooseVideo(cam.id, mode) } },
        coach = coach.settings,
        onOpenCoach = { nav.navigate(Routes.CoachSettings) },
        trackScreen = TrackScreenPrefs(prefs.trackBrightness, prefs.gnssSound),
        onTrackScreen = { p ->
            prefs.updateTrackBrightness(p.brightness)
            prefs.updateGnssSound(p.gnssSound)
        },
        onOpenAbout = { nav.navigate(Routes.About) },
        obdRow = obdRowValue(RecorderClient.get(context).state.obd, obdAdapter),
        onOpenObd = { nav.navigate(Routes.Obd) },
        reach = if (topic == SettingsTopic.Equipment) rememberEquipmentReach(prefs, repo) else emptyMap(),
        trackLayout = { SettingsTrackLayout(repo, coach.settings.verbosity != Verbosity.Off) },
    )
}
