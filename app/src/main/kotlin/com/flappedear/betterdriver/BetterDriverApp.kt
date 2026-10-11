package com.flappedear.betterdriver

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.flappedear.betterdriver.core.ble.scanRaceBoxes
import com.flappedear.betterdriver.feature.review.ReviewScreen
import com.flappedear.betterdriver.feature.track.TrackScreen
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.HomeScreen
import com.flappedear.betterdriver.shell.PairCandidate
import com.flappedear.betterdriver.shell.PairScreen
import com.flappedear.betterdriver.shell.PairedRaceBox
import com.flappedear.betterdriver.shell.SettingsScreen
import com.flappedear.betterdriver.shell.SettingsTab
import com.flappedear.betterdriver.shell.SetupScreen
import com.flappedear.betterdriver.shell.ShellIcons
import com.flappedear.betterdriver.shell.rememberAppPrefs
import kotlinx.coroutines.flow.flowOf

private data class TopLevelItem(val route: String, val label: String, val icon: ImageVector)

private val TopLevelItems = listOf(
    TopLevelItem(Routes.Drive, "Drive", ShellIcons.Drive),
    TopLevelItem(Routes.Sessions, "Sessions", ShellIcons.Sessions),
    TopLevelItem(Routes.Settings, "Settings", ShellIcons.Settings),
)

private val Version = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

/**
 * App shell from UI review round 2: bottom bar with Drive, Sessions and
 * Settings (UI-11 A), home with a big start button (UI-12 B), a first-run
 * checklist (UI-13 B), best-match pairing (UI-14 B) and tabbed settings
 * (UI-15 B). The track screen has no bar and stops with a 3 s hold (UI-16 A).
 */
@Composable
fun BetterDriverApp(wide: Boolean) {
    val nav = rememberNavController()
    val prefs = rememberAppPrefs()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Chosen once: changing a NavHost's start destination rebuilds its graph.
    // Setup again if a required permission was revoked or a one-time grant expired.
    val start = remember { if (prefs.setupDone && prefs.hasRequiredPermissions()) Routes.Drive else Routes.Setup }
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
            composable(Routes.Drive) {
                Box(Modifier.padding(padding)) {
                    HomeScreen(
                        devices = prefs.devices(),
                        trackName = null,
                        lastSession = null,
                        onStart = {
                            if (prefs.hasRequiredPermissions()) {
                                nav.navigate(Routes.Track)
                            } else {
                                nav.navigate(Routes.Setup) { popUpTo(Routes.Drive) { inclusive = true } }
                            }
                        },
                    )
                }
            }
            composable(Routes.Sessions) { Box(Modifier.padding(padding)) { ReviewScreen(wide = wide) } }
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
                Box(Modifier.padding(padding)) { PairRoute(nav, prefs) }
            }
            composable(Routes.Track) { Track(nav, prefs) }
        }
    }
}

/**
 * Scans for RaceBoxes while the pairing screen is shown, if the Bluetooth permissions are granted
 * (otherwise it stays on searching). Connect saves the strongest RaceBox; the recorder connects to it
 * when a session starts (FBBD-43).
 */
@Composable
private fun PairRoute(nav: NavHostController, prefs: AppPrefs) {
    val context = LocalContext.current
    val found by remember(context) {
        if (prefs.hasRequiredPermissions()) scanRaceBoxes(context) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val best = found.firstOrNull()
    PairScreen(
        candidate = best?.let { PairCandidate(it.model, it.serial, it.bars, it.address) },
        onConnect = { c ->
            val name = found.firstOrNull { it.address == c.address }?.name ?: "${c.name} ${c.serial}"
            prefs.updateRaceBox(PairedRaceBox(name, c.address))
            nav.popBackStack()
        },
        onShowAll = {},
        onBack = { nav.popBackStack() },
    )
}

/** A restored back stack can reopen Track after a one-time grant expired, so it checks again here. */
@Composable
private fun Track(nav: NavHostController, prefs: AppPrefs) {
    if (prefs.hasRequiredPermissions()) {
        TrackScreen(onStop = { nav.popBackStack() })
    } else {
        LaunchedEffect(Unit) {
            nav.navigate(Routes.Setup) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }
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
