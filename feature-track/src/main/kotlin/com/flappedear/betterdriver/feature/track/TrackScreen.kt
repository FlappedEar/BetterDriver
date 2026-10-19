package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
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
    /** The current lap's number, or null before the first fix. On a circuit the out lap is lap 1. */
    val lapNumber: Int?,
    /** The running time of the current lap. */
    val lapMillis: Long?,
    val lastMillis: Long?,
    val bestMillis: Long?,
    val sectors: List<SectorState>,
    val tiles: List<TrackTile>,
    val coachCorner: String?,
    val coachCue: String?,
    /** The coach line shows a praise (FBBD-243): its text is green. */
    val coachPraise: Boolean = false,
)

/**
 * Track screen entry point used by the app. Keeps the screen on and in
 * landscape for the whole outing, hides the system bars (a swipe shows them
 * briefly) so the layout gets the full height, and always uses the dark track
 * theme across the full window. The caller must not pad it for
 * insets; it pads its own content. [state] holds the live values; it defaults
 * to [ExampleTrackState]. Holding a finger anywhere for 3 s stops the session
 * (UI-16 option A). [brightness] is the Settings choice: full brightness by default, so the
 * screen reads in sunlight (FBBD-63). To keep the phone cooler while it records (FBBD-46) it asks
 * for sustained performance and a 60 Hz display where the phone offers them, and tells [onReport]
 * what it got, for the debug log.
 */
@Composable
fun TrackScreen(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    state: TrackScreenState = ExampleTrackState,
    brightness: TrackBrightness = TrackBrightness.Max,
    onReport: (String) -> Unit = {},
) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    KeepScreenOn(view)
    LockBrightness(activity, brightness)
    SustainedPerformance(activity, onReport)
    SlowerRefresh(activity, view, onReport)
    LockLandscape(activity)
    LightSystemBarIcons(activity, view)
    HideSystemBars(activity, view)
    BetterDriverTheme(track = true) {
        HoldToStop(onStop, modifier.fillMaxSize().background(TrackTheme.colors.background)) {
            TrackScreenContent(
                state = state,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}

/**
 * In-car track screen, layout UI-10 option A: delta left, lap times right,
 * coach line, then sectors and info tiles. Designed on a phone in landscape
 * (914 x 411 dp, a Pixel 6); every slot has a fixed size so values never move.
 */
@Composable
fun TrackScreenContent(state: TrackScreenState, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    val gap = 6.dp
    Column(
        modifier.fillMaxSize().background(c.background).padding(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        val top = Modifier.fillMaxWidth().height(30.dp).testTag(TrackSlots.Top)
        val alert = state.alert
        if (alert != null) {
            AlertBanner(alert.title, alert.detail, alert.level, state.recordingTime, top)
        } else {
            StatusStrip(state.status, state.recordingTime, top)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
            DeltaDisplay(state.deltaSeconds, Modifier.weight(0.62f).fillMaxHeight().testTag(TrackSlots.Delta))
            LapTimes(
                state.lapNumber,
                state.lapMillis,
                state.lastMillis,
                state.bestMillis,
                Modifier.weight(0.38f).fillMaxHeight().testTag(TrackSlots.Laps),
            )
        }
        CoachLine(
            state.coachCorner,
            state.coachCue,
            Modifier.fillMaxWidth().height(44.dp).testTag(TrackSlots.Coach),
            praise = state.coachPraise,
        )
        Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            SectorStrip(state.sectors, Modifier.weight(0.62f).fillMaxHeight().testTag(TrackSlots.Sectors))
            Row(Modifier.weight(0.38f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                state.tiles.take(3).forEachIndexed { i, t ->
                    val slot = Modifier.weight(1f).fillMaxHeight().testTag(TrackSlots.tile(i))
                    ValueTile(t.label, t.value, t.unit, slot, t.state)
                }
            }
        }
    }
}

/** Test tags of the track screen's fixed slots, for the layout tests (FBBD-40, FBBD-62). */
object TrackSlots {
    const val Top = "track-top"
    const val Delta = "track-delta"
    const val Laps = "track-laps"
    const val Coach = "track-coach"
    const val Sectors = "track-sectors"

    /** The [index]th info tile, from the left. */
    fun tile(index: Int) = "track-tile-$index"
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
    lapNumber = 8,
    lapMillis = 47_310,
    lastMillis = 112_810,
    bestMillis = 112_410,
    sectors = listOf(SectorState("S1", -0.21), SectorState("S2", 0.08), SectorState("S3", null, current = true)),
    tiles = listOf(TrackTile("Speed", "142", "km/h"), TrackTile("G", "1.12", "g")),
    coachCorner = "T4",
    coachCue = "Brake at the 100 board, carry 5 km/h more to apex",
)

@Preview(name = "Track screen", widthDp = 914, heightDp = 411)
@Composable
private fun TrackScreenPreview() {
    BetterDriverTheme(track = true) { TrackScreenContent(ExampleTrackState) }
}

/** Short window, e.g. system bars shown by a swipe or a cutout inset: the delta shrinks to fit. */
@Preview(name = "Track screen, short window", widthDp = 914, heightDp = 340)
@Composable
private fun TrackScreenShortPreview() {
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
                deltaSeconds = null, lapNumber = null, lapMillis = null, lastMillis = null, bestMillis = null,
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
