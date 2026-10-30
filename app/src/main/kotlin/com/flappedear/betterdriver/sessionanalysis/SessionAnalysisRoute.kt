package com.flappedear.betterdriver.sessionanalysis

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.flappedear.betterdriver.analysis.coaching.CoachedSession
import com.flappedear.betterdriver.analysis.coaching.SessionCoaching
import com.flappedear.betterdriver.analysis.line.LineProof
import com.flappedear.betterdriver.analysis.compare.compareLaps
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.LapSessionStatus
import com.flappedear.betterdriver.analysis.map.DriveMap
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.analysis.map.driveMap
import com.flappedear.betterdriver.analysis.map.sessionGg
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.runs.findAccelerationRuns
import com.flappedear.betterdriver.analysis.segments.flaggableCorners
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.summary.segmentChanges
import com.flappedear.betterdriver.analysis.summary.summarizeProgress
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CoachPicksRepo
import com.flappedear.betterdriver.coach.CoachPrefs
import com.flappedear.betterdriver.coach.CoachReferences
import com.flappedear.betterdriver.coach.CornerPick
import com.flappedear.betterdriver.coach.pickedOf
import com.flappedear.betterdriver.coach.coaches
import com.flappedear.betterdriver.coach.coachingHistoryStore
import com.flappedear.betterdriver.coach.linePlanStore
import com.flappedear.betterdriver.coach.sessionFocusStore
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.feature.analysis.AccelerationUi
import com.flappedear.betterdriver.feature.analysis.AnalysisScreen
import com.flappedear.betterdriver.feature.analysis.AnalysisState
import com.flappedear.betterdriver.feature.analysis.CarUi
import com.flappedear.betterdriver.feature.analysis.LineState
import com.flappedear.betterdriver.feature.analysis.LocalPerfectLapGround
import com.flappedear.betterdriver.feature.analysis.QualityUi
import com.flappedear.betterdriver.feature.analysis.accelerationUi
import com.flappedear.betterdriver.feature.analysis.analysisUi
import com.flappedear.betterdriver.feature.analysis.defaultComparedLap
import com.flappedear.betterdriver.feature.analysis.mapUi
import com.flappedear.betterdriver.feature.analysis.qualityUi
import com.flappedear.betterdriver.feature.analysis.wholeDriveCar
import com.flappedear.betterdriver.feature.analysis.withCar
import com.flappedear.betterdriver.feature.analysis.withHeartRate
import com.flappedear.betterdriver.feature.analysis.withQuality
import com.flappedear.betterdriver.feature.track.editor.EditorSession
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.log.HeartRateLogIds
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.qualityReports
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.shell.rememberAcrossTurns
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.timing.track.findSessionLoop
import com.flappedear.betterdriver.timing.track.proposeTrack
import com.flappedear.betterdriver.tracks.Tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cards of a session that need no laps: its acceleration runs (FBBD-154), its G-forces and its
 * car values and heart rate over the whole drive (FBBD-176), each null when it has none.
 */
private class WholeSession(
    val acceleration: AccelerationUi?,
    val gForces: GgTrace?,
    val car: CarUi? = null,
    val drive: DriveMap? = null,
)

/** A session read and analysed once; comparing another lap reuses it. */
@Suppress("LongParameterList") // What one read of the session gives.
private class Loaded(
    val session: TelemetrySession,
    val analysis: SessionAnalysis,
    /** When the session started; the earlier sessions at [track] are read against it. */
    val startUtcMillis: Long,
    val track: Track,
    val tracks: List<Track>,
    /** What the session shows with or without laps: its acceleration runs and G-forces. */
    val whole: WholeSession,
    /** What the coach did in the session, from its events: the focus it worked on and its cues (FBBD-180). */
    val coached: CoachedSession = CoachedSession(),
    /** Where the session came from when imported from another app's file (FBBD-181); null when recorded here. */
    val origin: String? = null,
    /** The heart-rate strap's name (FBBD-225), from its event; null without one. */
    val heartRateDevice: String? = null,
) {
    val title: String get() = track.displayName
}

private sealed interface Load {
    /**
     * [coachCandidates]: each corner (segment id) as a pick for the coach (FBBD-148), on the fastest lap.
     * [lapVideo]: the session's video and the lap review's laps, for every lap through a corner (FBBD-153).
     * [epoch]: the app data's restore epoch (FBBD-156) taken before the session was read, so what is
     * derived from it is never written into data restored since.
     */
    data class Done(
        val loaded: Loaded,
        val coachCandidates: Map<String, CornerPick>,
        val lapVideo: LapVideo? = null,
        val epoch: Long = 0L,
    ) : Load

    /**
     * No analysis; [proposal], when the session drove a loop that is no saved track yet (FBBD-151);
     * [trackId], the saved track the session was at when it has no complete lap there.
     */
    data class Failed(
        val state: AnalysisState.Unavailable,
        val proposal: Proposal? = null,
        val trackId: String? = null,
    ) : Load
}

/** What the screen read and worked out for one session: kept while the tablet turns (FBBD-323). */
private class AnalysisValues {
    var load by mutableStateOf<Load?>(null)
    var progress by mutableStateOf<SessionProgress?>(null)
    var analysed by mutableStateOf<AnalysisState>(AnalysisState.Loading)
    var state by mutableStateOf<AnalysisState>(AnalysisState.Loading)
    var quality by mutableStateOf<QualityUi?>(null)
    var qualityRead = false
    var line by mutableStateOf<LineState>(LineState.Reading)
    var coaching by mutableStateOf(SessionCoaching())
    var coachingKept = false
    var proof by mutableStateOf<LineProof?>(null)
}

/** A track made from the session, to check in the editor, and the session's drive to show there. */
private class Proposal(val track: Track, val session: EditorSession)

/** The lap analysis screen of one session, opened from its card on Sessions. */
internal fun NavGraphBuilder.analysisDestination(nav: NavHostController, padding: PaddingValues, layout: LayoutSize) {
    composable(
        Routes.SessionAnalysisPattern,
        arguments = listOf(
            navArgument(Routes.SessionIdArg) { type = NavType.StringType },
            navArgument(Routes.CornerArg) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { e ->
        val id = e.arguments?.getString(Routes.SessionIdArg).orEmpty()
        val corner = e.arguments?.getString(Routes.CornerArg)
        Box(Modifier.padding(padding)) {
            SessionAnalysisRoute(nav, id, layout, onBack = { nav.popBackStack() }, corner = corner)
        }
    }
}

/**
 * Lap analysis of one saved session (FBBD-102). The session is read and analysed off the main
 * thread against its track's start/finish line; a session without a track that has a line, or
 * without two crossings of it, says so. Its acceleration runs (FBBD-154) show either way.
 *
 * It fills its own screen, or the detail pane of Sessions on a tablet (FBBD-40); [layout] is the size
 * class of the space it has. [onBack] is its back button, none beside the session list. [corner]
 * (a CornerId) opens it on that corner's passes, as the progression does (FBBD-93).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Loads, analyses and follows the coach picks: one flow, one place.
@Composable
internal fun SessionAnalysisRoute(
    nav: NavHostController,
    id: String,
    layout: LayoutSize,
    onBack: (() -> Unit)?,
    corner: String? = null,
) {
    val context = LocalContext.current
    // Kept while the tablet turns, so the session is not read and analysed again (FBBD-323).
    val kept = rememberAcrossTurns("session-analysis:$id") { AnalysisValues() }
    var load by kept::load
    var compared by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    var progress by kept::progress
    var analysed by kept::analysed
    var state by kept::state
    var quality by kept::quality
    var line by kept::line
    var coaching by kept::coaching
    var proof by kept::proof
    // Made off the main thread when first used: making it reads the disk (FBBD-41).
    val store = remember { lazy { SessionStore(sessionsDir(context)) } }
    val tracks = remember { Tracks.get(context) }
    val coachPicks = remember { CoachPicksRepo.get(context) }
    val verbosity = remember { CoachPrefs.get(context) }.settings.verbosity
    val allPicks by coachPicks.picks.collectAsState()
    val trackId = (load as? Load.Done)?.loaded?.track?.id
    val picks = trackId?.let { allPicks[it] }
    LaunchedEffect(id) {
        if (load != null) return@LaunchedEffect
        load = withContext(Dispatchers.Default) {
            val epoch = AppDataGate.epoch
            val started = SystemClock.elapsedRealtime()
            val read = analysedOr<Load>(id, { Load.Failed(it) }) {
                loadSession(store.value, TrackStore(tracksDir(context)), id, tracks.circuits)
            }
            // How long the screen waited, so a drive log shows a slow analysis (0.3.19 and 0.3.20 were slow).
            val outcome = if (read is Load.Done) "analysed" else "not analysed"
            DebugLog.i("SessionAnalysis", "session $id $outcome in ${SystemClock.elapsedRealtime() - started} ms")
            if (read is Load.Done) read.copy(epoch = epoch) else read
        }
    }
    // The session's validation report (FBBD-183): read, or made the first time the session is opened.
    LaunchedEffect(id) {
        if (kept.qualityRead) return@LaunchedEffect
        quality = withContext(Dispatchers.IO) { qualityReports(context).load(id)?.let(::qualityUi) }
        kept.qualityRead = true
    }
    // The earlier sessions at the track are read after the analysis shows, so they never delay it.
    LaunchedEffect(load) {
        val l = load as? Load.Done ?: return@LaunchedEffect
        if (progress != null) return@LaunchedEffect
        progress = withContext(Dispatchers.IO) {
            val loaded = l.loaded
            val earlier = earlierSessions(store.value, id, loaded.startUtcMillis, loaded.track, loaded.tracks)
            summarizeProgress(loaded.analysis.laps, earlier.sessions).copy(
                changes = earlier.previous?.let { segmentChanges(loaded.analysis, it) }.orEmpty(),
                earlierLimited = earlier.limited,
            )
        }
    }
    // Where to find time (FBBD-306) reads every session at the track, so it comes after the rest; it
    // also keeps the track's line plan for the coach and the 3D export.
    LaunchedEffect(load) {
        val l = load as? Load.Done ?: return@LaunchedEffect
        if (line !is LineState.Reading) return@LaunchedEffect
        line = withContext(Dispatchers.Default) {
            val loaded = l.loaded
            analysedOr<LineState>(id, { LineState.Unavailable(it.message) }) {
                val others = lineSessions(store.value, id, loaded.track, loaded.tracks)
                lineState(
                    linePlanStore(context), l.epoch, loaded.track, id, loaded.startUtcMillis,
                    loaded.analysis, loaded.session, others,
                ) { CoachReferences.refresh(context) }
            }
        }
    }
    LaunchedEffect(load, compared, progress, line) {
        analysed = when (val l = load) {
            null -> AnalysisState.Loading
            is Load.Failed -> l.state
            is Load.Done -> withContext(Dispatchers.Default) {
                analysedOr<AnalysisState>(id, { it }) { readyState(l.loaded, compared, progress, line) }
            }
        }
    }
    // What to work on next (FBBD-20) is kept for the track's next session's coach once analysed, and
    // what was driven for the coaching history (FBBD-180), which says what came of the last focus.
    LaunchedEffect(load) {
        val l = load as? Load.Done ?: return@LaunchedEffect
        if (kept.coachingKept) return@LaunchedEffect
        val loaded = l.loaded
        coaching = withContext(Dispatchers.Default) {
            val store = sessionFocusStore(context)
            val at = loaded.startUtcMillis
            keepSessionFocus(store, l.epoch, loaded.track.id, id, at, loaded.analysis, l.coachCandidates)
            val history = coachingHistoryStore(context)
            val tracks = TrackStore(tracksDir(context))
            keepCoachingHistory(
                history, l.epoch, loaded.track.id, id, at, loaded.analysis, loaded.coached, loaded.origin,
            ) { tracks.get(loaded.track.id) != null }
        }
        kept.coachingKept = true
    }
    // What came of the last focus in seconds (FBBD-310), once this session's line plan is kept.
    LaunchedEffect(line, coaching) {
        val l = load as? Load.Done ?: return@LaunchedEffect
        if (line !is LineState.Ready || coaching.attempts.isEmpty()) return@LaunchedEffect
        proof = withContext(Dispatchers.IO) { lastFocusProof(linePlanStore(context), l.loaded.track.id, id, coaching) }
    }
    LaunchedEffect(trackId) { trackId?.let(coachPicks::load) }
    // The coach picks and the data quality are applied to the analysed screen, so neither analyses again.
    LaunchedEffect(analysed, picks, quality, verbosity, coaching, proof) {
        val l = load as? Load.Done
        val picked = if (l == null) analysed else withContext(Dispatchers.Default) { withPicks(analysed, l, picks) }
        state = picked.withQuality(quality).withCoachFocus(l?.loaded?.track, verbosity).withCoaching(coaching, proof)
    }
    val done = load as? Load.Done
    // The session as Sessions lists it, for the title: its start and track, read or not.
    val sessions = remember { Sessions.get(context) }
    val listed = sessions.list.firstOrNull { it.id == id }
    val source = CornerSource(done?.loaded?.analysis, done?.loaded?.session, done?.lapVideo, load is Load.Failed)
    // MapTiler's photo under the perfect lap in 3D once a key is entered on the editor's map (UI-201 B).
    val mapKey = tracks.tiles.mapTilerKey
    val ground = remember(mapKey) { mapKey?.let { MapTilerGround(tracks.tiles, it) } }
    CornerLapsRoute(nav, id, source, corner) {
        CompositionLocalProvider(LocalPerfectLapGround provides ground) {
            AnalysisScreen(
                state = state,
                onCompare = { compared = it },
                onAction = { unavailableAction(load, nav, tracks, id) },
                onCoachPick = { toggleCoachPick(load, coachPicks, it) },
                layout = layout,
                topBar = {
                    AnalysisTopBar(
                        analysisTitle(listed?.info?.startUtcMillis, done?.loaded?.title ?: listed?.info?.track),
                        onBack = onBack,
                        // Also for a session at a track with no complete lap there.
                        onProgression = (trackId ?: (load as? Load.Failed)?.trackId)
                            ?.let { t -> { nav.navigate(Routes.trackProgression(t)) } },
                        onCoachReview = { nav.navigate(Routes.cueReview(id)) },
                    )
                },
                optimalVideo = optimalVideoSlot(id, done?.loaded?.analysis, done?.lapVideo),
                // Progression over every session at the track (FBBD-181).
                onProgression = trackId?.let { t -> { nav.navigate(Routes.trackProgression(t)) } },
            )
        }
    }
}

/** The unavailable analysis's action: Tracks, or the editor on the track made from session [id]. */
private fun unavailableAction(load: Load?, nav: NavHostController, tracks: Tracks, id: String) {
    val proposal = (load as? Load.Failed)?.proposal
    if (proposal == null) {
        nav.navigate(Routes.Tracks)
    } else {
        // Kept by Tracks before the editor opens it (one background thread, in order).
        tracks.propose(proposal.track, id, proposal.session)
        nav.navigate(Routes.trackEditor(proposal.track.id))
    }
}

private fun readyState(loaded: Loaded, compared: Int?, progress: SessionProgress?, line: LineState): AnalysisState {
    val analysis = loaded.analysis
    val fastest = analysis.laps.fastestLapIndex
    val other = compared ?: defaultComparedLap(analysis)
    val comparison = if (fastest != null && other != null) {
        compareLaps(analysis, loaded.session, fastest, other)
    } else {
        null
    }
    val ui = analysisUi(loaded.title, analysis, comparison)
        ?.let { withHeartRate(it, analysis, loaded.session, loaded.heartRateDevice) }
        ?.let { withCar(it, analysis, loaded.session, loaded.heartRateDevice) }
        ?: return AnalysisState.Unavailable(
            "No lap counts: every lap has a GPS gap or is not a full lap.",
            acceleration = loaded.whole.acceleration,
            gForces = loaded.whole.gForces,
            car = loaded.whole.car,
            drive = loaded.whole.drive,
        )
    // The map and G-G diagram read every sample of two laps; this runs on Dispatchers.Default.
    val map = mapUi(analysis, loaded.session, comparison)
    val whole = loaded.whole
    return AnalysisState.Ready(
        ui.copy(
            progress = progress, map = map, acceleration = whole.acceleration, gForces = whole.gForces, line = line,
        ),
    )
}

/**
 * [state] with the corners that can be picked for the coach and those picked at the track (FBBD-148):
 * by CornerId when the analysis has the track's corner identity (FBBD-172), else by position. None
 * can be picked until the track's picks ([picks]) have been read.
 */
private fun withPicks(state: AnalysisState, done: Load.Done, picks: List<CornerPick>?): AnalysisState {
    if (state !is AnalysisState.Ready || picks == null) return state
    val candidates = done.coachCandidates
    val analysis = done.loaded.analysis
    val picked = pickedOf(candidates, picks, analysis.identity?.takeIf { analysis.identified })
    return AnalysisState.Ready(state.ui.copy(coachPickable = candidates.keys, coachPicked = picked))
}

/** Picks or unpicks [corner] (a segment id) for the coach at the analysed session's track. */
private fun toggleCoachPick(load: Load?, picks: CoachPicksRepo, corner: String) {
    val done = load as? Load.Done ?: return
    done.coachCandidates[corner]?.let { picks.toggle(done.loaded.track.id, it) }
}

/**
 * Each corner of [analysis] as a coach pick: its line on the fastest lap, and its CornerId in the
 * track's corner identity (FBBD-172) when the analysis's corners are the track's.
 */
private fun coachCandidates(analysis: SessionAnalysis, sessionId: String): Map<String, CornerPick> =
    flaggableCorners(analysis).associate {
        it.id to CornerPick(it.name, it.lats, it.lons, sessionId, it.cornerId, it.identityRevision)
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
    val track = trackFor(data, saved)
    val session = if (track == null) telemetrySessionOf(data, null) else telemetrySessionOf(data, track)
    // Acceleration runs need no track or laps, so a road drive shows them too.
    val acceleration = accelerationUi(findAccelerationRuns(session))
    // So do the session's G-forces.
    val gForces = sessionGg(session)
    val strap = data.events.firstOrNull { it.kind == HeartRateLogIds.DEVICE }
        ?.text?.substringAfter("name=", "")?.ifEmpty { null }
    // And the car's values and the heart rate over the whole drive (UI-162 A).
    val car = wholeDriveCar(session, strap)
    // And the driven route with its speed (FBBD-342): a road drive is seen on the map, laps or not.
    val drive = driveMap(session)
    if (track == null) {
        val failed = noTrack(data, saved, circuits)
        return failed.copy(
            state = failed.state.copy(acceleration = acceleration, gForces = gForces, car = car, drive = drive),
        )
    }
    // Analysed through the track's corners and straights (FBBD-172); the first analysis there makes them.
    val analysis = analyzeAtTrack(tracks, track, session, data.info.startUtcMillis)
    if (analysis.laps.status != LapSessionStatus.Available || analysis.laps.timedLaps.isEmpty()) {
        val why = if (track.pointToPoint) {
            "No complete run at ${track.displayName}: a run goes from the start line to the finish line."
        } else {
            "No complete lap at ${track.displayName}: the car has to cross the start/finish line at least twice."
        }
        return Load.Failed(
            AnalysisState.Unavailable(why, acceleration = acceleration, gForces = gForces, car = car, drive = drive),
            trackId = track.id,
        )
    }
    // The coach's own record of the session: which focus it worked on, and its cues at each corner (FBBD-180).
    // A coach that worked on the line plan's corners logs them too (UI-174 A, FBBD-313).
    val coached = CoachedSession(
        CoachEvents.focusSource(data.events),
        CoachEvents.cuesByCorner(data.events),
        CoachEvents.planCorners(data.events),
    )
    val whole = WholeSession(acceleration, gForces, car, drive)
    val loaded = Loaded(
        session, analysis, data.info.startUtcMillis, track, saved, whole, coached, originOf(data.info), strap,
    )
    // No corners to pick where the coach does not run (point-to-point tracks, FBBD-155).
    val candidates = if (coaches(track)) coachCandidates(analysis, id) else emptyMap()
    return Load.Done(loaded, candidates, lapVideo(sessions, data, track))
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
    val km = kmText(loop.lengthMeters)
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
