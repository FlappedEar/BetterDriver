package com.flappedear.betterdriver.sessionanalysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flappedear.betterdriver.Routes
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.compare.compareLaps
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.feature.analysis.AnalysisScreen
import com.flappedear.betterdriver.feature.analysis.AnalysisState
import com.flappedear.betterdriver.feature.analysis.analysisUi
import com.flappedear.betterdriver.feature.analysis.defaultComparedLap
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.shell.ShellTopBar
import com.flappedear.betterdriver.timing.track.TrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A session read and analysed once; comparing another lap reuses it. */
private class Loaded(val title: String, val session: TelemetrySession, val analysis: SessionAnalysis)

private sealed interface Load {
    data class Done(val loaded: Loaded) : Load

    data class Failed(val state: AnalysisState.Unavailable) : Load
}

/** The lap analysis screen of one session, opened from its card on Sessions. */
internal fun NavGraphBuilder.analysisDestination(nav: NavHostController, padding: PaddingValues) {
    composable(
        Routes.SessionAnalysisPattern,
        arguments = listOf(navArgument(Routes.SessionIdArg) { type = NavType.StringType }),
    ) { e ->
        val id = e.arguments?.getString(Routes.SessionIdArg).orEmpty()
        Box(Modifier.padding(padding)) { SessionAnalysisRoute(nav, id) }
    }
}

/**
 * Lap analysis of one saved session (FBBD-102). The session is read and analysed off the main
 * thread against its track's start/finish line; a session without a track that has a line, or
 * without two crossings of it, says so.
 */
@Composable
private fun SessionAnalysisRoute(nav: NavHostController, id: String) {
    val context = LocalContext.current
    var load by remember(id) { mutableStateOf<Load?>(null) }
    var compared by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    var state by remember(id) { mutableStateOf<AnalysisState>(AnalysisState.Loading) }
    LaunchedEffect(id) {
        load = withContext(Dispatchers.Default) {
            loadSession(SessionStore(sessionsDir(context)), TrackStore(tracksDir(context)), id)
        }
    }
    LaunchedEffect(load, compared) {
        state = when (val l = load) {
            null -> AnalysisState.Loading
            is Load.Failed -> l.state
            is Load.Done -> withContext(Dispatchers.Default) { readyState(l.loaded, compared) }
        }
    }
    AnalysisScreen(
        state = state,
        onCompare = { compared = it },
        onAction = { nav.navigate(Routes.Tracks) },
        topBar = { ShellTopBar("Lap analysis", onBack = { nav.popBackStack() }) },
    )
}

private fun readyState(loaded: Loaded, compared: Int?): AnalysisState {
    val analysis = loaded.analysis
    val fastest = analysis.laps.fastestLapIndex
    val other = compared ?: defaultComparedLap(analysis)
    val comparison = if (fastest != null && other != null) {
        compareLaps(analysis, loaded.session, fastest, other)
    } else {
        null
    }
    val ui = analysisUi(loaded.title, analysis, comparison)
        ?: return AnalysisState.Unavailable("No lap counts: every lap has a GPS gap or is not a full lap.")
    return AnalysisState.Ready(ui)
}

// A damaged session or track file says so instead of crashing the app.
@Suppress("TooGenericExceptionCaught", "ReturnCount")
private fun loadSession(sessions: SessionStore, tracks: TrackStore, id: String): Load {
    val data = try {
        sessions.read(id)
    } catch (e: Exception) {
        android.util.Log.w("SessionAnalysis", "reading session $id failed", e)
        return Load.Failed(AnalysisState.Unavailable("This session could not be read."))
    }
    val track = trackFor(data, tracks.all()) ?: return Load.Failed(
        AnalysisState.Unavailable(
            "This session has no track with a start/finish line. Set the line in the track editor, " +
                "then open the analysis again.",
            action = "Tracks",
        ),
    )
    val session = telemetrySessionOf(data, track.startFinish)
    val analysis = analyzeSession(session)
    if (analysis.laps.status != LapSessionStatus.Available || analysis.laps.timedLaps.isEmpty()) {
        return Load.Failed(
            AnalysisState.Unavailable(
                "No complete lap at ${track.displayName}: the car has to cross the start/finish line " +
                    "at least twice.",
            ),
        )
    }
    return Load.Done(Loaded(track.displayName, session, analysis))
}
