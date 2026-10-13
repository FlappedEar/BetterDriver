package com.flappedear.betterdriver.sessionanalysis

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.flappedear.betterdriver.feature.analysis.CornerLapsScreen
import com.flappedear.betterdriver.feature.analysis.CornerLapsUi
import com.flappedear.betterdriver.feature.analysis.ProvideCornerLaps
import com.flappedear.betterdriver.shell.ShellTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Same corner across laps (FBBD-153): [content] (the analysis screen of [source]'s analysis), whose
 * corners, focus and coaching lines offer every pass through a corner (FBBD-93), or once one is
 * opened, every counted pass through it with its traces and video. The corner's screen is built off
 * the main thread; back returns to [content] with its state (tab, scroll) as it was left. Compare
 * opens the lap review of session [sessionId] on the two laps ticked, side by side at the corner.
 * [initialCorner] opens on that corner, as from the progression; back from it then goes back there.
 */
@Composable
internal fun CornerLapsRoute(
    nav: NavHostController,
    sessionId: String,
    source: CornerSource,
    initialCorner: String? = null,
    content: @Composable () -> Unit,
) {
    val analysis = source.analysis
    val lapVideo = source.lapVideo
    var corner by rememberSaveable(sessionId) { mutableStateOf(initialCorner) }
    // Opened from elsewhere on a corner: back leaves for where it was opened from.
    val fromOutside = initialCorner != null && corner == initialCorner
    var ui by remember(sessionId) { mutableStateOf<CornerLapsUi?>(null) }
    LaunchedEffect(analysis, source.session, lapVideo, corner) {
        val c = corner
        ui = null
        if (analysis != null && c != null) {
            ui = withContext(Dispatchers.Default) { cornerLaps(analysis, source.session, c, lapVideo) }
        }
    }
    val states = rememberSaveableStateHolder()
    fun back() {
        if (fromOutside) nav.popBackStack() else corner = null
    }
    BackHandler(enabled = corner != null) { back() }
    val open = corner
    if (open == null) {
        states.SaveableStateProvider(AnalysisKey) { ProvideCornerLaps({ corner = it }, content) }
    } else {
        CornerLapsScreen(
            ui,
            onCompare = { a, b ->
                analysis?.let { cornerReviewRoute(it, sessionId, lapVideo, a, b, open) }?.let { nav.navigate(it) }
            },
            topBar = { ShellTopBar("Every lap", onBack = { back() }) },
        )
    }
}

private const val AnalysisKey = "analysis"
