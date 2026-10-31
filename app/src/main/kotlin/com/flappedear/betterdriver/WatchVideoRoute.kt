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
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.sessionTitle
import com.flappedear.betterdriver.videoexport.VideoExports
import com.flappedear.betterdriver.videoexport.VideoPart
import com.flappedear.betterdriver.videoexport.WatchLoad
import com.flappedear.betterdriver.videoexport.WatchVideoScreen
import com.flappedear.betterdriver.videoexport.WholeVideo

/** The Watch video destination, [Routes.WatchVideoPattern] (FBBD-341). */
internal fun NavGraphBuilder.watchVideo(nav: NavHostController, padding: PaddingValues) {
    composable(
        Routes.WatchVideoPattern,
        arguments = listOf(
            navArgument(Routes.SessionIdArg) { type = NavType.StringType },
            navArgument(Routes.VideoPartArg) {
                type = NavType.IntType
                defaultValue = WholeVideo
            },
            navArgument(Routes.VideoHandleArg) {
                type = NavType.IntType
                defaultValue = 0
            },
        ),
    ) { e ->
        val args = e.arguments
        val id = args?.getString(Routes.SessionIdArg).orEmpty()
        val part = args?.getInt(Routes.VideoPartArg, WholeVideo) ?: WholeVideo
        val handle = args?.getInt(Routes.VideoHandleArg, 0) ?: 0
        Box(Modifier.padding(padding)) { WatchVideoRoute(nav, id, part, handle) }
    }
}

/** Session [id]'s video with the data overlay, [part] of it with [handle] seconds, read off the main thread first. */
@Composable
private fun WatchVideoRoute(nav: NavHostController, id: String, part: VideoPart, handle: Int) {
    val context = LocalContext.current
    var load by remember(id, part, handle) { mutableStateOf<WatchLoad>(WatchLoad.Loading) }
    LaunchedEffect(id, part, handle) {
        VideoExports.get(context).watch(id, part, handle) { s ->
            load = s?.let { WatchLoad.Ready(it) } ?: WatchLoad.NoVideo
        }
    }
    val title = Sessions.get(context).list.firstOrNull { it.id == id }?.let(::sessionTitle).orEmpty()
    WatchVideoScreen(title, load, onBack = { nav.popBackStack() })
}
