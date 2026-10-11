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
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.flappedear.betterdriver.feature.track.TrackScreen
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.lastSessionLine
import com.flappedear.betterdriver.recording.liveTrackState
import com.flappedear.betterdriver.recording.vboFileName
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.HomeScreen
import com.flappedear.betterdriver.shell.PairScreen
import com.flappedear.betterdriver.shell.SessionsScreen
import com.flappedear.betterdriver.shell.SettingsScreen
import com.flappedear.betterdriver.shell.SettingsTab
import com.flappedear.betterdriver.shell.SetupScreen
import com.flappedear.betterdriver.shell.ShellIcons
import com.flappedear.betterdriver.shell.rememberAppPrefs

private data class TopLevelItem(val route: String, val label: String, val icon: ImageVector)

private val TopLevelItems = listOf(
    TopLevelItem(Routes.Drive, "Drive", ShellIcons.Drive),
    TopLevelItem(Routes.Sessions, "Sessions", ShellIcons.Sessions),
    TopLevelItem(Routes.Settings, "Settings", ShellIcons.Settings),
)

/** VBO has no registered type; a generic one keeps the .vbo file name. */
private const val VboMimeType = "application/octet-stream"

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
                Box(Modifier.padding(padding)) { Setup(nav, prefs) }
            }
            composable(Routes.Drive) { Box(Modifier.padding(padding)) { Drive(nav, prefs, recorder, sessions) } }
            composable(Routes.Sessions) { Box(Modifier.padding(padding)) { SessionsRoute(sessions) } }
            composable(Routes.Settings) {
                Box(Modifier.padding(padding)) { Settings(nav, prefs, SettingsTab.Devices, onBack = null) }
            }
            composable(
                Routes.SettingsTabPattern,
                arguments = listOf(navArgument(Routes.TabArg) { type = NavType.StringType }),
            ) { e ->
                val tab = SettingsTab.entries.firstOrNull { it.name == e.arguments?.getString(Routes.TabArg) }
                Box(Modifier.padding(padding)) {
                    Settings(nav, prefs, tab ?: SettingsTab.Devices, onBack = { nav.popBackStack() })
                }
            }
            composable(Routes.Pair) {
                Box(Modifier.padding(padding)) {
                    PairScreen(candidate = null, onConnect = {}, onShowAll = {}, onBack = { nav.popBackStack() })
                }
            }
            composable(Routes.Track) { Track(nav, prefs, recorder) }
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

@Composable
private fun Drive(nav: NavHostController, prefs: AppPrefs, recorder: RecorderClient, sessions: Sessions) {
    HomeScreen(
        devices = prefs.devices(),
        trackName = null,
        lastSession = lastSessionLine(sessions.list),
        onStart = {
            if (prefs.hasRequiredPermissions()) {
                // While recording, START goes back to the running session.
                if (!recorder.state.recording) recorder.start(prefs.car, track = "")
                nav.navigate(Routes.Track)
            } else {
                nav.navigate(Routes.Setup) { popUpTo(Routes.Drive) { inclusive = true } }
            }
        },
    )
}

/** A restored back stack can reopen Track after a one-time grant expired, so it checks again here. */
@Composable
private fun Track(nav: NavHostController, prefs: AppPrefs, recorder: RecorderClient) {
    if (prefs.hasRequiredPermissions()) {
        TrackScreen(
            state = liveTrackState(recorder.state),
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

/** Sessions list; export asks where to save the VBO file, then writes it in the background. */
@Composable
private fun SessionsRoute(sessions: Sessions) {
    // Saveable: the activity can be re-created while the file picker is open.
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VboMimeType)) { uri ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) sessions.export(id, uri)
    }
    LaunchedEffect(Unit) { sessions.refresh() }
    SessionsScreen(
        sessions = sessions.list,
        exports = sessions.exports,
        onExport = { s ->
            exporting = s.id
            pickFile.launch(vboFileName(s))
        },
    )
}

@Composable
private fun Setup(nav: NavHostController, prefs: AppPrefs) {
    SetupScreen(
        prefs = prefs,
        onPair = { nav.navigate(Routes.Pair) },
        onCameras = { nav.navigate(Routes.settingsTab(SettingsTab.Devices.name)) },
        onCar = { nav.navigate(Routes.settingsTab(SettingsTab.Car.name)) },
        onDone = {
            prefs.updateSetupDone(true)
            nav.navigate(Routes.Drive) { popUpTo(Routes.Setup) { inclusive = true } }
        },
    )
}

@Composable
private fun Settings(nav: NavHostController, prefs: AppPrefs, tab: SettingsTab, onBack: (() -> Unit)?) {
    SettingsScreen(
        prefs = prefs,
        version = Version,
        initialTab = tab,
        onPair = { nav.navigate(Routes.Pair) },
        onRunSetup = {
            prefs.updateSetupDone(false)
            nav.navigate(Routes.Setup) { popUpTo(Routes.Drive) { inclusive = true } }
        },
        onBack = onBack,
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
