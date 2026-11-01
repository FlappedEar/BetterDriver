package com.flappedear.betterdriver

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.navigation.NavHostController
import com.flappedear.betterdriver.analysis.review.ReviewOnlyDevice
import com.flappedear.betterdriver.core.ui.layout.HeightClass
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.core.ui.layout.WidthClass
import com.flappedear.betterdriver.shell.ShellIcons

// The app's navigation around the screens: the bottom bar on a phone, the rail on a tablet (FBBD-40).

private data class TopLevelItem(val route: String, val label: String, val icon: ImageVector)

// parity-list:top-level
private val TopLevelItems = listOf(
    TopLevelItem(Routes.Drive, "Drive", ShellIcons.Drive),
    TopLevelItem(Routes.Sessions, "Sessions", ShellIcons.Sessions),
    TopLevelItem(Routes.Car, "Car", ShellIcons.Car),
    TopLevelItem(Routes.Settings, "Settings", ShellIcons.Settings),
)

/** The bar's items: a tablet only reviews, so without Drive and Car (the shared [ReviewOnlyDevice]). */
private fun topLevelItems(tabletDevice: Boolean) =
    TopLevelItems.filter { ReviewOnlyDevice.showsTopLevel(it.label, tabletDevice) }

/**
 * A tablet whichever way it is held or split: its smallest width is a tablet's (600 dp, Android's
 * own line), unlike the window's size class, which an upright tablet shares with a phone held sideways.
 */
@Composable
internal fun isTabletDevice(): Boolean = LocalConfiguration.current.smallestScreenWidthDp >= TabletSmallestWidthDp

private const val TabletSmallestWidthDp = 600

/** The window's size class (FBBD-40); the phone layout when there is no activity, as in previews. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
internal fun windowLayout(): LayoutSize {
    val activity = LocalActivity.current ?: return LayoutSize.Phone
    return layoutSizeOf(calculateWindowSizeClass(activity))
}

private fun layoutSizeOf(window: WindowSizeClass) = LayoutSize(
    width = when (window.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> WidthClass.Expanded
        WindowWidthSizeClass.Medium -> WidthClass.Medium
        else -> WidthClass.Compact
    },
    height = when (window.heightSizeClass) {
        WindowHeightSizeClass.Expanded -> HeightClass.Expanded
        WindowHeightSizeClass.Compact -> HeightClass.Compact
        else -> HeightClass.Medium
    },
)

/** [padding] without its start: the rail beside the screens takes the start inset itself. */
@Composable
internal fun withoutStart(padding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        top = padding.calculateTopPadding(),
        end = padding.calculateEndPadding(direction),
        bottom = padding.calculateBottomPadding(),
    )
}

/** [updateDot]: a quiet dot on Settings when a newer version waits (FBBD-74). */
@Composable
internal fun BottomBar(nav: NavHostController, route: String, updateDot: Boolean) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        // An upright tablet has the bar too, still without Drive.
        topLevelItems(isTabletDevice()).forEach { item ->
            NavigationBarItem(
                selected = sectionOf(route) == item.route,
                onClick = { openTopLevel(nav, item.route) },
                icon = { TopLevelIcon(item, updateDot) },
                label = { Text(item.label) },
            )
        }
    }
}

/**
 * The bottom bar's items down the side of a tablet (FBBD-40), centred within reach of a thumb. A
 * tablet only reviews, so it has no Drive (Arek, 2026-10-30: no recording on tablets).
 */
@Composable
internal fun SideRail(nav: NavHostController, route: String, updateDot: Boolean) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        Spacer(Modifier.weight(1f))
        topLevelItems(tabletDevice = true).forEach { item ->
            NavigationRailItem(
                selected = sectionOf(route) == item.route,
                onClick = { openTopLevel(nav, item.route) },
                icon = { TopLevelIcon(item, updateDot) },
                label = { Text(item.label) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** The bar's item a destination belongs to: a Settings topic is part of Settings (UI-137 B). */
private fun sectionOf(route: String): String = if (route == Routes.SettingsTopicPattern) Routes.Settings else route

private fun openTopLevel(nav: NavHostController, route: String) {
    val current = nav.currentDestination?.route
    // The picked item again goes to its top: from a Settings topic back to the topics.
    if (current != null && current != route && sectionOf(current) == route) {
        nav.popBackStack(route, inclusive = false)
        return
    }
    nav.navigate(route) {
        // Drive is the root once setup is done, the only time the bar or the rail shows.
        popUpTo(Routes.Drive) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun TopLevelIcon(item: TopLevelItem, updateDot: Boolean) {
    if (updateDot && item.route == Routes.Settings) {
        BadgedBox(badge = { Badge() }) { Icon(item.icon, contentDescription = "Update available") }
    } else {
        Icon(item.icon, contentDescription = null)
    }
}
