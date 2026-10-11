package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flappedear.betterdriver.coach.CueReview
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.sessionTitle
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.shell.CueReviewScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** The cue review destination, [Routes.CueReviewPattern]. */
internal fun NavGraphBuilder.cueReview(nav: NavHostController, padding: PaddingValues) {
    composable(
        Routes.CueReviewPattern,
        arguments = listOf(navArgument(Routes.SessionIdArg) { type = NavType.StringType }),
    ) { e ->
        val id = e.arguments?.getString(Routes.SessionIdArg).orEmpty()
        Box(Modifier.padding(padding)) { CueReviewRoute(nav, id) }
    }
}

/** The cue review of session [id] (FBBD-99), opened from its card. The session is read off the main thread. */
@Composable
internal fun CueReviewRoute(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val review by produceState<CueReview?>(null, id) {
        value = withContext(Dispatchers.IO) {
            try {
                CueReview.of(SessionStore(sessionsDir(context)).read(id).events)
            } catch (_: IOException) {
                CueReview(emptyList(), 0)
            } catch (_: IllegalStateException) {
                CueReview(emptyList(), 0)
            }
        }
    }
    val title = Sessions.get(context).list.firstOrNull { it.id == id }?.let(::sessionTitle).orEmpty()
    CueReviewScreen(title, review, onBack = { nav.popBackStack() })
}
