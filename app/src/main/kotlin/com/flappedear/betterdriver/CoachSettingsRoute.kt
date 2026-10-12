package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.flappedear.betterdriver.coach.AudioDelay
import com.flappedear.betterdriver.coach.ClickPlayer
import com.flappedear.betterdriver.coach.CoachPicksRepo
import com.flappedear.betterdriver.coach.CoachPrefs
import com.flappedear.betterdriver.coach.TapRun
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.shell.AudioDelayScreen
import com.flappedear.betterdriver.shell.AudioDelayUi
import com.flappedear.betterdriver.shell.CoachSettingsScreen
import com.flappedear.betterdriver.shell.DelayStep
import com.flappedear.betterdriver.shell.PickedCorners
import com.flappedear.betterdriver.shell.afterRun
import com.flappedear.betterdriver.timing.track.TrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings › App › Spoken coach (FBBD-98, UI-64 B): saved as it changes, read at the next session start.
 * The corners picked in the lap analysis (FBBD-148) are read with the track names off the main thread.
 * The headset delay test (FBBD-49) is a page of its own below it.
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
                onMeasureDelay = { nav.navigate(Routes.CoachAudioDelay) },
            )
        }
    }
    composable(Routes.CoachAudioDelay) {
        Box(Modifier.padding(padding)) { AudioDelayRoute(onBack = { nav.popBackStack() }) }
    }
}

/** Clicks this many times at most in one run, so a run with many missed clicks still ends. */
private const val MaxClicksPerRun = AudioDelay.Taps * 2

/**
 * The headset delay test (FBBD-49): plays the clicks off the main thread at random gaps, matches the
 * taps to them, and saves the result as the coach's audio delay. Leaving the page stops the clicks.
 */
@Composable
private fun AudioDelayRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val coach = remember(context) { CoachPrefs.get(context) }
    val player = remember(context) { ClickPlayer(context) }
    DisposableEffect(player) { onDispose { player.close() } }
    var ui by remember { mutableStateOf(AudioDelayUi()) }
    var run by remember { mutableStateOf<TapRun?>(null) }
    LaunchedEffect(player) {
        while (true) {
            val headset = withContext(Dispatchers.Default) { player.headset() }
            ui = ui.copy(headset = headset)
            delay(HeadsetCheckMillis)
        }
    }
    val active = run
    LaunchedEffect(active) {
        if (active == null) return@LaunchedEffect
        val speaker = ui.step == DelayStep.Speaker
        while (!active.done && active.clicks < MaxClicksPerRun) {
            delay(AudioDelay.nextGapMillis())
            withContext(Dispatchers.Default) { player.play(speaker) }?.let(active::onClick)
        }
        // Too many clicks missed: give the last one its time, then take what was tapped.
        delay(AudioDelay.MaxTapMillis)
        run = null
        ui = ui.afterRun(active.delays)
    }
    AudioDelayScreen(
        ui,
        currentMillis = coach.settings.audioDelayMillis,
        onStart = {
            ui = ui.copy(running = true, taps = 0)
            run = TapRun()
        },
        onTap = { t ->
            val r = run
            if (r?.onTap(t) != null) {
                ui = ui.copy(taps = r.delays.size)
                if (r.done) {
                    run = null
                    ui = ui.afterRun(r.delays)
                }
            }
        },
        onUse = { ms ->
            coach.update(coach.settings.copy(audioDelayMillis = ms))
            onBack()
        },
        onAgain = { ui = AudioDelayUi(headset = ui.headset) },
        onBack = onBack,
    )
}

private const val HeadsetCheckMillis = 2_000L
