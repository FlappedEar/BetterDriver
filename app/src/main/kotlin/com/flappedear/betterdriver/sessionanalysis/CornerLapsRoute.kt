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
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.feature.analysis.CornerLapsScreen
import com.flappedear.betterdriver.feature.analysis.CornerLapsUi
import com.flappedear.betterdriver.feature.analysis.ProvideCornerLaps
import com.flappedear.betterdriver.shell.ShellTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Same corner across laps (FBBD-153): [content] (the analysis screen of [analysis]), whose corners
 * offer "Every lap", or once one is opened, every counted lap through it with its video. The
 * corner's screen is built off the main thread; back returns to [content] with its state (tab,
 * scroll) as it was left. Compare opens the lap review of session [sessionId] on the two laps ticked.
 */
@Composable
internal fun CornerLapsRoute(
    nav: NavHostController,
    sessionId: String,
    analysis: SessionAnalysis?,
    lapVideo: LapVideo?,
    content: @Composable () -> Unit,
) {
    var corner by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var ui by remember(sessionId) { mutableStateOf<CornerLapsUi?>(null) }
    LaunchedEffect(analysis, lapVideo, corner) {
        val c = corner
        ui = null
        if (analysis != null && c != null) ui = withContext(Dispatchers.Default) { cornerLaps(analysis, c, lapVideo) }
    }
    val states = rememberSaveableStateHolder()
    BackHandler(enabled = corner != null) { corner = null }
    val open = corner
    if (open == null) {
        states.SaveableStateProvider(AnalysisKey) { ProvideCornerLaps({ corner = it }, content) }
    } else {
        CornerLapsScreen(
            ui,
            onCompare = { a, b ->
                analysis?.let { cornerReviewRoute(it, sessionId, lapVideo, a, b, open) }?.let { nav.navigate(it) }
            },
            topBar = { ShellTopBar("Every lap", onBack = { corner = null }) },
        )
    }
}

private const val AnalysisKey = "analysis"
