package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flappedear.betterdriver.feature.review.LapKey
import com.flappedear.betterdriver.feature.review.ReviewScreen
import com.flappedear.betterdriver.feature.review.ReviewStart
import com.flappedear.betterdriver.review.ReviewLaps
import com.flappedear.betterdriver.shell.ShellIcons

/**
 * The lap review: from Sessions with no laps given, or on two laps from the Corner laps screen
 * (FBBD-153), starting at the corner.
 */
internal fun NavGraphBuilder.reviewDestination(nav: NavHostController, padding: PaddingValues) {
    composable(
        Routes.ReviewPattern,
        arguments = listOf(Routes.ReviewAArg, Routes.ReviewBArg, Routes.ReviewAtArg).map {
            navArgument(it) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        },
    ) { e ->
        val args = e.arguments
        val a = LapKey.decode(args?.getString(Routes.ReviewAArg))
        val b = LapKey.decode(args?.getString(Routes.ReviewBArg))
        val start = if (a != null && b != null) {
            ReviewStart(a, b, args?.getString(Routes.ReviewAtArg)?.toDoubleOrNull())
        } else {
            null
        }
        ReviewRoute(nav, padding, start)
    }
}

/** Lap review with each lap's video (FBBD-24), opened from Sessions. Sessions are read off the main thread. */
@Composable
private fun ReviewRoute(nav: NavHostController, padding: PaddingValues, start: ReviewStart?) {
    val context = LocalContext.current
    val laps = remember(context) { ReviewLaps.get(context) }
    // The laps asked for are read even from an older session than the review reads on its own.
    LaunchedEffect(Unit) { laps.load(setOfNotNull(start?.a?.sessionId, start?.b?.sessionId)) }
    Box(Modifier.padding(padding)) {
        ReviewScreen(laps.state, ShellIcons.Back, onBack = { nav.popBackStack() }, start = start)
    }
}
