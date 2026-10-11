package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.AlertBanner
import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.core.ui.component.CoachLine
import com.flappedear.betterdriver.core.ui.component.DeltaDisplay
import com.flappedear.betterdriver.core.ui.component.LapTimes
import com.flappedear.betterdriver.core.ui.component.SectorState
import com.flappedear.betterdriver.core.ui.component.SectorStrip
import com.flappedear.betterdriver.core.ui.component.StatusItem
import com.flappedear.betterdriver.core.ui.component.StatusStrip
import com.flappedear.betterdriver.core.ui.component.ValueState
import com.flappedear.betterdriver.core.ui.component.ValueTile

@Immutable
data class TrackTile(val label: String, val value: String, val unit: String, val state: ValueState = ValueState.Normal)

@Immutable
data class TrackAlert(val title: String, val detail: String, val level: AlertLevel)

/** Everything the track screen shows. Pure data, so the screen never waits on I/O. */
@Immutable
data class TrackScreenState(
    val status: List<StatusItem>,
    val recordingTime: String?,
    val alert: TrackAlert?,
    val deltaSeconds: Double?,
    val predictedMillis: Long?,
    val lastMillis: Long?,
    val bestMillis: Long?,
    val sectors: List<SectorState>,
    val tiles: List<TrackTile>,
    val coachCorner: String?,
    val coachCue: String?,
)

/**
 * Track screen entry point used by the app. Keeps the screen on for the whole
 * outing and always uses the dark track theme. Live data is not wired yet, so
 * it shows [ExampleTrackState]. A long press anywhere stops the session; this
 * stop control is provisional until its design is approved.
 */
@Composable
fun TrackScreen(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BetterDriverTheme(track = true) {
        TrackScreenContent(
            state = ExampleTrackState,
            modifier = modifier.pointerInput(onStop) { detectTapGestures(onLongPress = { onStop() }) },
        )
    }
}

/**
 * In-car track screen, layout UI-10 option A: delta left, lap times right,
 * coach line, then sectors and info tiles. Designed for the Pixel 6 in
 * landscape (914 x 411 dp); every slot has a fixed size so values never move.
 */
@Composable
fun TrackScreenContent(state: TrackScreenState, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    val gap = 6.dp
    Column(
        modifier.fillMaxSize().background(c.background).padding(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        val top = Modifier.fillMaxWidth().height(30.dp)
        val alert = state.alert
        if (alert != null) {
            AlertBanner(alert.title, alert.detail, alert.level, state.recordingTime, top)
        } else {
            StatusStrip(state.status, state.recordingTime, top)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
            DeltaDisplay(state.deltaSeconds, Modifier.weight(0.62f).fillMaxHeight())
            LapTimes(state.predictedMillis, state.lastMillis, state.bestMillis, Modifier.weight(0.38f).fillMaxHeight())
        }
        CoachLine(state.coachCorner, state.coachCue, Modifier.fillMaxWidth().height(36.dp))
        Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            SectorStrip(state.sectors, Modifier.weight(0.62f).fillMaxHeight())
            Row(Modifier.weight(0.38f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                state.tiles.take(3).forEach { t ->
                    ValueTile(t.label, t.value, t.unit, Modifier.weight(1f).fillMaxHeight(), t.state)
                }
            }
        }
    }
}

/** Example values matching the round 1 review mockups. Not real data. */
val ExampleTrackState = TrackScreenState(
    status = listOf(
        StatusItem("GNSS", "25 Hz · 18 sats"),
        StatusItem("CAM", "Phone 4K · GoPro"),
        StatusItem("Phone", "38°C"),
        StatusItem("Batt", "84%"),
    ),
    recordingTime = "18:42",
    alert = null,
    deltaSeconds = -0.34,
    predictedMillis = 112_070,
    lastMillis = 112_810,
    bestMillis = 112_410,
    sectors = listOf(SectorState("S1", -0.21), SectorState("S2", 0.08), SectorState("S3", null, current = true)),
    tiles = listOf(TrackTile("Speed", "142", "km/h"), TrackTile("G", "1.12", "g"), TrackTile("Lap", "7", "")),
    coachCorner = "T4",
    coachCue = "Brake at the 100 board, carry 5 km/h more to apex",
)

@Preview(name = "Track screen", widthDp = 914, heightDp = 411)
@Composable
private fun TrackScreenPreview() {
    BetterDriverTheme(track = true) { TrackScreenContent(ExampleTrackState) }
}

@Preview(name = "Track screen, GNSS lost", widthDp = 914, heightDp = 411)
@Composable
private fun TrackScreenAlertPreview() {
    BetterDriverTheme(track = true) {
        TrackScreenContent(
            ExampleTrackState.copy(
                alert = TrackAlert(
                    title = "GNSS signal lost",
                    detail = "Lap timing paused · video still recording",
                    level = AlertLevel.Critical,
                ),
            ),
        )
    }
}

@Preview(name = "Track screen, before first lap", widthDp = 914, heightDp = 411)
@Composable
private fun TrackScreenEmptyPreview() {
    BetterDriverTheme(track = true) {
        TrackScreenContent(
            ExampleTrackState.copy(
                deltaSeconds = null, predictedMillis = null, lastMillis = null, bestMillis = null,
                sectors = listOf(
                    SectorState("S1", null, current = true),
                    SectorState("S2", null),
                    SectorState("S3", null),
                ),
                coachCorner = null, coachCue = null,
            ),
        )
    }
}
