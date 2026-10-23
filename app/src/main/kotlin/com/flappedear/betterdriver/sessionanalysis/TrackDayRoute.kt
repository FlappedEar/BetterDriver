package com.flappedear.betterdriver.sessionanalysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flappedear.betterdriver.Routes
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.DayPlanStore
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeDay
import com.flappedear.betterdriver.analysis.line.dayPlan
import com.flappedear.betterdriver.analysis.line.dayProof
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.coach.dayPlanStore
import com.flappedear.betterdriver.feature.analysis.TrackDayScreen
import com.flappedear.betterdriver.feature.analysis.TrackDayState
import com.flappedear.betterdriver.feature.analysis.trackDayUi
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.shell.ShellTopBar
import com.flappedear.betterdriver.shell.rememberAcrossTurns
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The track day page (FBBD-315), opened from a day's heading in Sessions (UI-210 B). */
internal fun NavGraphBuilder.trackDayDestination(nav: NavHostController, padding: PaddingValues) {
    composable(
        Routes.TrackDayPattern,
        arguments = listOf(navArgument(Routes.TrackDayIdsArg) { type = NavType.StringType }),
    ) { e ->
        val ids = e.arguments?.getString(Routes.TrackDayIdsArg).orEmpty().split(',').filter { it.isNotBlank() }
        Box(Modifier.padding(padding)) { TrackDayRoute(ids, onBack = { nav.popBackStack() }) }
    }
}

/**
 * The day of sessions [ids] at one track: read together on the day's fastest lap's corners, kept as
 * the track's day plan for the next day there (its coach and its proof, UI-214 B), and measured
 * against the plan of the day before at the track.
 */
@Composable
internal fun TrackDayRoute(ids: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    // Kept while the tablet turns, so the day is not read again (FBBD-323).
    var state by rememberAcrossTurns("track-day:" + ids.joinToString(",")) {
        mutableStateOf<TrackDayState>(TrackDayState.Reading)
    }
    LaunchedEffect(ids) {
        if (state !is TrackDayState.Reading) return@LaunchedEffect
        // Taken before any session is read, so a day read before a restore is not kept (FBBD-156).
        val epoch = AppDataGate.epoch
        state = withContext(Dispatchers.Default) {
            trackDayState(
                SessionStore(sessionsDir(context)), TrackStore(tracksDir(context)), dayPlanStore(context), ids, epoch,
            )
        }
    }
    TrackDayScreen(state, topBar = { ShellTopBar("Track day", onBack = onBack) })
}

/** One session of the day as read: its laps and when it started. */
private class DaySessionRead(val line: LineSession, val startUtcMillis: Long, val track: Track)

@Suppress("TooGenericExceptionCaught", "ReturnCount", "LongParameterList")
internal fun trackDayState(
    sessions: SessionStore,
    tracks: TrackStore,
    plans: DayPlanStore,
    ids: List<String>,
    epoch: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): TrackDayState {
    val saved = tracks.all()
    val read = ids.mapNotNull { id ->
        try {
            val data = sessions.read(id)
            val track = trackFor(data, saved) ?: return@mapNotNull null
            val session = telemetrySessionOf(data, track)
            val start = data.info.startUtcMillis
            val line = LineSession(session, deriveSourceLapSession(session), timeLabel(start, zone), id)
            DaySessionRead(line, start, track)
        } catch (e: Exception) {
            DebugLog.w(Tag, "reading session $id for the track day failed", e)
            null
        }
    }.sortedBy { it.startUtcMillis }
    // The day's track is the one most of its sessions were driven at.
    val track = read.groupBy { it.track.id }.maxByOrNull { it.value.size }?.value?.first()?.track
        ?: return TrackDayState.Unavailable(NoDayText)
    val day = read.filter { it.track.id == track.id }
    val starts = day.associate { it.line.id to it.startUtcMillis }
    // With the track's corner identity (FBBD-172), so the day plan names corners as the coach does.
    val analyze = { s: LineSession -> analyzeAtTrack(tracks, track, s.session, starts.getValue(s.id)) }
    val analysis = analyzeDay(day.map { it.line }, analyze) ?: return TrackDayState.Unavailable(NoDayText)
    val date = Instant.ofEpochMilli(day.first().startUtcMillis).atZone(zone).toLocalDate()
    val last = day.last()
    val plan = dayPlan(analysis, track.id, date.toString(), last.line.id, last.startUtcMillis)
    val previous = plans.before(track.id, date.toString())
    try {
        AppDataGate.writeIfCurrent(epoch) {
            if (plans.save(plan)) {
                DebugLog.i(Tag, "day plan ${plan.day} at ${track.id}: focus ${plan.plan.focus().map { it.name }}")
            }
        }
    } catch (e: IOException) {
        DebugLog.w(Tag, "day plan of ${plan.day} at ${track.id} not saved", e)
    }
    val proof = previous?.let { dayProof(it, analysis) }
    val previousLabel = previous?.let { DayFormat.format(LocalDate.parse(it.day)) }
    return TrackDayState.Ready(trackDayUi(analysis, track.displayName, DayFormat.format(date), proof, previousLabel))
}

private fun timeLabel(startUtcMillis: Long, zone: ZoneId) =
    TimeFormat.format(Instant.ofEpochMilli(startUtcMillis).atZone(zone))

private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private const val Tag = "TrackDay"

private const val NoDayText =
    "No track day for these sessions: it needs two sessions at one saved track with a lap through its corners each."
