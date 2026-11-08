package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.timing.overlay.HudChoice
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.videoexport.OverlayEditorScreen
import com.flappedear.betterdriver.videoexport.VideoExports

/** The overlay editor destination, [Routes.OverlayEditorPattern] (FBBD-472). */
internal fun NavGraphBuilder.overlayEditor(nav: NavHostController, padding: PaddingValues) {
    composable(
        Routes.OverlayEditorPattern,
        arguments = listOf(
            navArgument(Routes.OverlaySessionArg) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { e ->
        val session = e.arguments?.getString(Routes.OverlaySessionArg)
        Box(Modifier.padding(padding)) { OverlayEditorRoute(nav, session) }
    }
}

/**
 * The editor over the setup's choice, saved at every change; with [sessionId], that session's data
 * is read off the main thread for its widgets and channels.
 */
@Composable
private fun OverlayEditorRoute(nav: NavHostController, sessionId: String?) {
    val context = LocalContext.current
    val repo = remember(context) { SetupRepo.get(context) }
    var data by remember(sessionId) { mutableStateOf<OverlayData?>(null) }
    var loading by remember(sessionId) { mutableStateOf(sessionId != null) }
    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            VideoExports.get(context).overlayData(sessionId) {
                data = it
                loading = false
            }
        }
    }
    val choice = remember(repo.setup.videoOverlay) { HudChoice.decode(repo.setup.videoOverlay) }
    OverlayEditorScreen(
        choice = choice,
        data = data,
        loading = loading,
        onChange = { next -> repo.update { it.withVideoOverlay(next.encode()) } },
        onBack = { nav.popBackStack() },
    )
}
