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
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.summary.segmentChanges
import com.flappedear.betterdriver.analysis.summary.summarizeProgress
import com.flappedear.betterdriver.feature.analysis.AnalysisScreen
import com.flappedear.betterdriver.feature.analysis.AnalysisState
import com.flappedear.betterdriver.feature.analysis.analysisUi
import com.flappedear.betterdriver.feature.analysis.defaultComparedLap
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.shell.ShellTopBar
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A session read and analysed once; comparing another lap reuses it. */
private class Loaded(
    val title: String,
    val session: TelemetrySession,
    val analysis: SessionAnalysis,
    /** When the session started; the earlier sessions at [track] are read against it. */
    val startUtcMillis: Long,
    val track: Track,
    val tracks: List<Track>,
)

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
    var progress by remember(id) { mutableStateOf<SessionProgress?>(null) }
    var state by remember(id) { mutableStateOf<AnalysisState>(AnalysisState.Loading) }
    val store = remember { SessionStore(sessionsDir(context)) }
    LaunchedEffect(id) {
        load = withContext(Dispatchers.Default) { loadSession(store, TrackStore(tracksDir(context)), id) }
    }
    // The earlier sessions at the track are read after the analysis shows, so they never delay it.
    LaunchedEffect(load) {
        val l = load as? Load.Done ?: return@LaunchedEffect
        progress = withContext(Dispatchers.IO) {
            val loaded = l.loaded
            val earlier = earlierSessions(store, id, loaded.startUtcMillis, loaded.track, loaded.tracks)
            summarizeProgress(loaded.analysis.laps, earlier.sessions).copy(
                changes = earlier.previous?.let { segmentChanges(loaded.analysis, it) }.orEmpty(),
                earlierLimited = earlier.limited,
            )
        }
    }
    LaunchedEffect(load, compared, progress) {
        state = when (val l = load) {
            null -> AnalysisState.Loading
            is Load.Failed -> l.state
            is Load.Done -> withContext(Dispatchers.Default) { readyState(l.loaded, compared, progress) }
        }
    }
    AnalysisScreen(
        state = state,
        onCompare = { compared = it },
        onAction = { nav.navigate(Routes.Tracks) },
        topBar = { ShellTopBar("Lap analysis", onBack = { nav.popBackStack() }) },
    )
}

private fun readyState(loaded: Loaded, compared: Int?, progress: SessionProgress?): AnalysisState {
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
    return AnalysisState.Ready(ui.copy(progress = progress))
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
    val saved = tracks.all()
    val track = trackFor(data, saved) ?: return Load.Failed(
        AnalysisState.Unavailable(
            "No laps: this session, a road drive for example, was not recorded at a track with a " +
                "start/finish line. If it was at a track, set the line in the track editor, then open the " +
                "analysis again.",
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
    return Load.Done(Loaded(track.displayName, session, analysis, data.info.startUtcMillis, track, saved))
}
