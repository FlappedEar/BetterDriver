package com.flappedear.betterdriver.sessionanalysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.segmentLine
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.summary.segmentChanges
import com.flappedear.betterdriver.analysis.summary.summarizeProgress
import com.flappedear.betterdriver.coach.CoachPicksRepo
import com.flappedear.betterdriver.coach.CornerPick
import com.flappedear.betterdriver.feature.analysis.AnalysisScreen
import com.flappedear.betterdriver.feature.analysis.AnalysisState
import com.flappedear.betterdriver.feature.analysis.analysisUi
import com.flappedear.betterdriver.feature.analysis.defaultComparedLap
import com.flappedear.betterdriver.feature.track.editor.EditorSession
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.shell.ShellTopBar
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.timing.track.findSessionLoop
import com.flappedear.betterdriver.timing.track.proposeTrack
import com.flappedear.betterdriver.tracks.Tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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
    /**
     * [coachCandidates]: each corner (segment id) as a pick for the coach (FBBD-148), on the fastest lap.
     * [lapVideo]: the session's video and the lap review's laps, for every lap through a corner (FBBD-153).
     */
    data class Done(
        val loaded: Loaded,
        val coachCandidates: Map<String, CornerPick>,
        val lapVideo: LapVideo? = null,
    ) : Load

    /** No analysis; [proposal], when the session drove a loop that is no saved track yet (FBBD-151). */
    data class Failed(val state: AnalysisState.Unavailable, val proposal: Proposal? = null) : Load
}

/** A track made from the session, to check in the editor, and the session's drive to show there. */
private class Proposal(val track: Track, val session: EditorSession)

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
    var analysed by remember(id) { mutableStateOf<AnalysisState>(AnalysisState.Loading) }
    var state by remember(id) { mutableStateOf<AnalysisState>(AnalysisState.Loading) }
    val store = remember { SessionStore(sessionsDir(context)) }
    val tracks = remember { Tracks.get(context) }
    val coachPicks = remember { CoachPicksRepo.get(context) }
    val allPicks by coachPicks.picks.collectAsState()
    val trackId = (load as? Load.Done)?.loaded?.track?.id
    val picks = trackId?.let { allPicks[it] }
    LaunchedEffect(id) {
        load = withContext(Dispatchers.Default) {
            loadSession(store, TrackStore(tracksDir(context)), id, tracks.circuits)
        }
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
        analysed = when (val l = load) {
            null -> AnalysisState.Loading
            is Load.Failed -> l.state
            is Load.Done -> withContext(Dispatchers.Default) { readyState(l.loaded, compared, progress) }
        }
    }
    LaunchedEffect(trackId) { trackId?.let(coachPicks::load) }
    // The coach picks are applied to the analysed screen, so a toggle never analyses again.
    LaunchedEffect(analysed, picks) {
        val l = load as? Load.Done
        state = if (l == null) analysed else withContext(Dispatchers.Default) { withPicks(analysed, l, picks) }
    }
    CornerLapsRoute(nav, id, (load as? Load.Done)?.loaded?.analysis, (load as? Load.Done)?.lapVideo) {
        AnalysisScreen(
            state = state,
            onCompare = { compared = it },
            onAction = {
                val proposal = (load as? Load.Failed)?.proposal
                if (proposal == null) {
                    nav.navigate(Routes.Tracks)
                } else {
                    // Kept by Tracks before the editor opens it (one background thread, in order).
                    tracks.propose(proposal.track, id, proposal.session)
                    nav.navigate(Routes.trackEditor(proposal.track.id))
                }
            },
            onCoachPick = { toggleCoachPick(load, coachPicks, it) },
            topBar = { ShellTopBar("Lap analysis", onBack = { nav.popBackStack() }) },
        )
    }
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

/**
 * [state] with the corners that can be picked for the coach and those picked at the track (FBBD-148),
 * matched by position; none can be picked until the track's picks ([picks]) have been read.
 */
private fun withPicks(state: AnalysisState, done: Load.Done, picks: List<CornerPick>?): AnalysisState {
    if (state !is AnalysisState.Ready || picks == null) return state
    val candidates = done.coachCandidates
    val picked = candidates.filterValues { c -> picks.any { it.sameCorner(c) } }.keys
    return AnalysisState.Ready(state.ui.copy(coachPickable = candidates.keys, coachPicked = picked))
}

/** Picks or unpicks [corner] (a segment id) for the coach at the analysed session's track. */
private fun toggleCoachPick(load: Load?, picks: CoachPicksRepo, corner: String) {
    val done = load as? Load.Done ?: return
    done.coachCandidates[corner]?.let { picks.toggle(done.loaded.track.id, it) }
}

/** Each corner of [analysis] as a coach pick: its line on the fastest lap. */
private fun coachCandidates(analysis: SessionAnalysis, sessionId: String): Map<String, CornerPick> {
    val axis = analysis.axis ?: return emptyMap()
    return analysis.segments?.segments.orEmpty()
        .filter { it.type == TrackSegmentType.Corner }
        .mapNotNull { corner ->
            val line = segmentLine(axis, corner).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            corner.id to CornerPick(
                corner.name,
                line.map { it.latitudeDegrees },
                line.map { it.longitudeDegrees },
                sessionId,
            )
        }
        .toMap()
}

// A damaged session or track file says so instead of crashing the app.
@Suppress("TooGenericExceptionCaught", "ReturnCount")
private fun loadSession(sessions: SessionStore, tracks: TrackStore, id: String, circuits: CircuitList): Load {
    val data = try {
        sessions.read(id)
    } catch (e: Exception) {
        DebugLog.w("SessionAnalysis", "reading session $id failed", e)
        return Load.Failed(AnalysisState.Unavailable("This session could not be read."))
    }
    val saved = tracks.all()
    val track = trackFor(data, saved) ?: return noTrack(data, saved, circuits)
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
    val loaded = Loaded(track.displayName, session, analysis, data.info.startUtcMillis, track, saved)
    return Load.Done(loaded, coachCandidates(analysis, id), lapVideo(sessions, data, track))
}

/**
 * A session at no saved track with a line. When it drove a closed loop again and again (FBBD-151),
 * the screen offers to make a track from it, as Garmin Catalyst's "Add a Track": the line goes
 * across the middle of the longest straight and Arek checks it in the editor before it is saved.
 */
// Making the offer must never fail the screen: any failure just leaves the offer out.
@Suppress("TooGenericExceptionCaught")
private fun noTrack(data: SessionData, saved: List<Track>, circuits: CircuitList): Load.Failed = try {
    proposal(data, saved, circuits)
} catch (e: Exception) {
    DebugLog.w("SessionAnalysis", "making a track from session ${data.id} failed", e)
    null
} ?: Load.Failed(
    AnalysisState.Unavailable(
        "No laps: this session, a road drive for example, was not recorded at a track with a " +
            "start/finish line. If it was at a track, set the line in the track editor, then open the " +
            "analysis again.",
        action = "Tracks",
    ),
)

/** The offer to make a track from the session, or null when it drove no loop. */
private fun proposal(data: SessionData, saved: List<Track>, circuits: CircuitList): Load.Failed? {
    val fixes = sessionFixes(data)
    val loop = findSessionLoop(fixes) ?: return null
    val label = sessionLabel(data.info.startUtcMillis)
    val fallback = data.info.track.ifBlank { "Track of $label" }.take(Track.MaxNameChars)
    val track = proposeTrack(loop, saved, circuits, fallback)
    val km = String.format(Locale.getDefault(), "%.2f", loop.lengthMeters / MetersPerKm)
    val at = if (track.name == fallback) "" else " at ${track.displayName}"
    val laps = if (loop.laps == 1) "1 lap" else "${loop.laps} laps"
    return Load.Failed(
        AnalysisState.Unavailable(
            "No laps yet: this session drove $laps of a $km km loop$at that has no start/finish line. " +
                "Make a track from it: the line goes across the middle of the longest straight, and you " +
                "check it in the track editor before it is saved.",
            action = "Make a track from this session",
        ),
        Proposal(track, EditorSession(label, fixes)),
    )
}

private const val MetersPerKm = 1_000.0
