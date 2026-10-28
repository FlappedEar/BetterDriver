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
import com.flappedear.betterdriver.videoexport.WatchLoad
import com.flappedear.betterdriver.videoexport.WatchVideoScreen

/** The Watch video destination, [Routes.WatchVideoPattern] (FBBD-341). */
internal fun NavGraphBuilder.watchVideo(nav: NavHostController, padding: PaddingValues) {
    composable(
        Routes.WatchVideoPattern,
        arguments = listOf(navArgument(Routes.SessionIdArg) { type = NavType.StringType }),
    ) { e ->
        val id = e.arguments?.getString(Routes.SessionIdArg).orEmpty()
        Box(Modifier.padding(padding)) { WatchVideoRoute(nav, id) }
    }
}

/** Session [id]'s whole video with the data overlay, read off the main thread first. */
@Composable
private fun WatchVideoRoute(nav: NavHostController, id: String) {
    val context = LocalContext.current
    var load by remember(id) { mutableStateOf<WatchLoad>(WatchLoad.Loading) }
    LaunchedEffect(id) {
        VideoExports.get(context).watch(id) { s -> load = s?.let { WatchLoad.Ready(it) } ?: WatchLoad.NoVideo }
    }
    val title = Sessions.get(context).list.firstOrNull { it.id == id }?.let(::sessionTitle).orEmpty()
    WatchVideoScreen(title, load, onBack = { nav.popBackStack() })
}
