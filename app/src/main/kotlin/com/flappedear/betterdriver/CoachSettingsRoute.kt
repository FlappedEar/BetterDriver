package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.flappedear.betterdriver.coach.CoachPicksRepo
import com.flappedear.betterdriver.coach.CoachPrefs
import com.flappedear.betterdriver.coach.MeasuredDelay
import com.flappedear.betterdriver.coach.OutputDelayProbe
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.shell.CoachSettingsScreen
import com.flappedear.betterdriver.shell.PickedCorners
import com.flappedear.betterdriver.timing.track.TrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings › App › Spoken coach (FBBD-98, UI-64 B): saved as it changes, read at the next session start.
 * The corners picked in the lap analysis (FBBD-148) are read with the track names off the main thread.
 * The headset's delay (FBBD-49) is measured while the page is open, to show it.
 */
internal fun NavGraphBuilder.coachSettings(nav: NavHostController, padding: PaddingValues) {
    composable(Routes.CoachSettings) {
        val context = LocalContext.current
        val coach = remember(context) { CoachPrefs.get(context) }
        val picks = remember(context) { CoachPicksRepo.get(context) }
        val byTrack by picks.picks.collectAsState()
        var names by remember { mutableStateOf<Map<String, String>?>(null) }
        LaunchedEffect(Unit) {
            picks.loadAll()
            names = withContext(Dispatchers.IO) {
                TrackStore(tracksDir(context)).all().associate { it.id to it.displayName }
            }
        }
        val picked = names?.let { n ->
            byTrack.filterValues { it.isNotEmpty() }
                .map { (id, p) -> PickedCorners(id, n[id] ?: "A track no longer saved", p.map { it.name }) }
                .sortedBy { it.trackName }
        }
        Box(Modifier.padding(padding)) {
            CoachSettingsScreen(
                coach.settings,
                coach::update,
                onBack = { nav.popBackStack() },
                picked = picked,
                onClearPicked = picks::clear,
                delayNow = rememberDelayNow(),
            )
        }
    }
}

/**
 * The line saying the headset's delay (FBBD-49), measured while the page is open and again every few
 * seconds, so connecting a headset shows its delay. Null until the first measurement.
 */
@Composable
private fun rememberDelayNow(): String? {
    val context = LocalContext.current
    var line by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        while (true) {
            line = withContext(Dispatchers.IO) { delayLine(OutputDelayProbe.measure(context)) }
            delay(DelayCheckMillis)
        }
    }
    return line
}

private fun delayLine(m: MeasuredDelay?): String = when {
    m == null -> "Android gives no delay for this output, so the coach uses the one below."
    m.headset != null -> "${m.millis} ms now, on ${m.headset}"
    else -> "${m.millis} ms now, on the phone speaker (no headset connected)"
}

private const val DelayCheckMillis = 5_000L
