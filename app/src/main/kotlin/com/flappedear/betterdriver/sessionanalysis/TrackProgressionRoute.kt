package com.flappedear.betterdriver.sessionanalysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.progression.CornerMapping
import com.flappedear.betterdriver.analysis.progression.ProgressionDay
import com.flappedear.betterdriver.analysis.progression.ProgressionGrouping
import com.flappedear.betterdriver.analysis.progression.systemDayZone
import com.flappedear.betterdriver.coach.coachingHistoryStore
import com.flappedear.betterdriver.feature.analysis.ProgressionActions
import com.flappedear.betterdriver.feature.analysis.ProgressionChoice
import com.flappedear.betterdriver.feature.analysis.ProgressionPeriod
import com.flappedear.betterdriver.feature.analysis.ProgressionState
import com.flappedear.betterdriver.feature.analysis.TrackProgressionScreen
import com.flappedear.betterdriver.feature.analysis.progressionScreenUi
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.shell.ShellTopBar
import com.flappedear.betterdriver.timing.track.TrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The progression at one track (FBBD-181), opened from a session's summary. */
internal fun NavGraphBuilder.progressionDestination(nav: NavHostController, padding: PaddingValues) {
    composable(
        Routes.TrackProgressionPattern,
        arguments = listOf(navArgument(Routes.TrackIdArg) { type = NavType.StringType }),
    ) { e ->
        val id = e.arguments?.getString(Routes.TrackIdArg).orEmpty()
        Box(Modifier.padding(padding)) {
            TrackProgressionRoute(
                id,
                onBack = { nav.popBackStack() },
                // A corner's passes in the latest session that drove it (FBBD-93).
                onCornerPasses = { session, corner -> nav.navigate(Routes.sessionAnalysis(session, corner)) },
            )
        }
    }
}

/**
 * Progression over every session at track [trackId] (FBBD-181), from its coaching history
 * (FBBD-180). The records kept show at once; sessions recorded or imported at the track that have
 * none yet (never opened, or analysed before laps were kept) are analysed in the background and
 * join as each is done, so imported sessions take part without being opened first.
 */
@Composable
internal fun TrackProgressionRoute(
    trackId: String,
    onBack: () -> Unit,
    onCornerPasses: (sessionId: String, cornerId: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var title by remember(trackId) { mutableStateOf("") }
    var records by remember(trackId) { mutableStateOf<List<SessionCoachingRecord>?>(null) }
    var adding by remember(trackId) { mutableIntStateOf(0) }
    var grouping by rememberSaveable(trackId) { mutableStateOf(ProgressionGrouping.Session) }
    var period by rememberSaveable(trackId) { mutableStateOf(ProgressionPeriod.All) }
    // Saved as text: a day as ISO, the sessions left out one per line.
    var day by rememberSaveable(trackId) { mutableStateOf<String?>(null) }
    var excluded by rememberSaveable(trackId) { mutableStateOf("") }
    var state by remember(trackId) { mutableStateOf<ProgressionState>(ProgressionState.Loading) }
    // Which corner of an older revision of the track's corners is which (FBBD-179).
    var mapping by remember(trackId) { mutableStateOf(CornerMapping.SameRevisionOnly) }
    LaunchedEffect(trackId) {
        // Made off the main thread: making the stores reads the disk (FBBD-41).
        val stores = withContext(Dispatchers.IO) {
            val tracks = TrackStore(tracksDir(context))
            val history = coachingHistoryStore(context)
            val track = tracks.get(trackId)
            title = track?.displayName.orEmpty()
            mapping = CornerMapping.of(track?.identity)
            records = readHistory(history, trackId)
            ProgressionStores(SessionStore(sessionsDir(context)), tracks, history)
        }
        val history = stores.history
        // Sessions with no record yet join one by one, as each is analysed (FBBD-181), written
        // against the restore epoch taken before any of them is read (FBBD-156).
        val epoch = AppDataGate.epoch
        // Only the track's own sessions count towards the limits (other tracks' are skipped on reading).
        val (summaries, current) =
            withContext(Dispatchers.IO) { progressionCandidates(stores.sessions, history, trackId) }
        val byId = summaries.associateBy { it.id }
        addNewest(
            summaries.map { it.id },
            current = { it in current },
            tried = { triedAt(trackId, it) },
            add = { id ->
                val added = withContext(Dispatchers.Default) { addToHistory(byId.getValue(id), stores, trackId, epoch) }
                if (added == AddResult.Added) records =withContext(Dispatchers.IO) { readHistory(history, trackId) }
                added
            },
            left = { adding = it },
        )
    }
    LaunchedEffect(records, grouping, period, day, excluded, adding, title, mapping) {
        val kept = records ?: return@LaunchedEffect
        val choice = ProgressionChoice(
            grouping,
            period,
            day?.let { ProgressionDay.parse(it) },
            excluded.lines().filter { it.isNotBlank() }.toSet(),
        )
        state = withContext(Dispatchers.Default) {
            val zone = systemDayZone()
            val today = ProgressionDay.at(System.currentTimeMillis(), zone)
            ProgressionState.Ready(
                progressionScreenUi(title, kept, choice, today, zone, mapping).copy(adding = adding),
            )
        }
    }
    val actions = remember(trackId) {
        object : ProgressionActions {
            override fun grouping(grouping: ProgressionGrouping) {
                setGrouping(grouping)
            }

            override fun period(period: ProgressionPeriod) {
                setPeriod(period)
            }

            override fun day(day: ProgressionDay?) {
                setDay(day)
            }

            override fun toggle(sessionId: String) {
                setExcluded(sessionId)
            }

            override fun cornerPasses(sessionId: String, cornerId: String) {
                onCornerPasses(sessionId, cornerId)
            }

            fun setGrouping(g: ProgressionGrouping) {
                grouping = g
            }

            fun setPeriod(p: ProgressionPeriod) {
                period = p
            }

            fun setDay(d: ProgressionDay?) {
                day = d?.toString()
                // A track day opens to its sessions.
                if (d != null) grouping = ProgressionGrouping.Session
            }

            fun setExcluded(id: String) {
                val now = excluded.lines().filter { it.isNotBlank() }.toSet()
                excluded = (if (id in now) now - id else now + id).joinToString("\n")
            }
        }
    }
    TrackProgressionScreen(state, actions, topBar = { ShellTopBar("Progression", onBack = onBack) })
}
