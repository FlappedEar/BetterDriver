package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.flappedear.betterdriver.core.ui.component.StatusItem
import com.flappedear.betterdriver.core.ui.component.StatusStrip
import com.flappedear.betterdriver.core.ui.component.ValueState

/**
 * One data tile (FBBD-262). [id] is the channel's, so the tile's list can mark it. [hasData] false:
 * the channel has given no value in this session (no strap, the adapter not connected, a PID the
 * car does not answer), and the tile is left out (UI-126 B).
 */
@Immutable
data class TrackTile(
    val label: String,
    val value: String,
    val unit: String,
    val state: ValueState = ValueState.Normal,
    val id: String = "",
    val hasData: Boolean = true,
)

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
    /**
     * The main screen's bottom panel (UI-120 C), slot by slot: two rows of [MainColumns]. Null is
     * an empty slot.
     */
    val tiles: List<TrackTile?>,
    val coachCorner: String?,
    val coachCue: String?,
    /** The coach line shows a praise (FBBD-243): its text is green. */
    val coachPraise: Boolean = false,
    /** The second screen's tiles (UI-121 B), slot by slot: three rows of [SecondColumns]. */
    val secondTiles: List<TrackTile?> = emptyList(),
    /** The car is stopped: a 1 s hold on a tile opens its list (UI-123 B). */
    val editable: Boolean = false,
)

/** UI-120 C: two rows of five on the main screen. */
const val MainColumns = 5
const val MainRows = 2

/** UI-121 B: three rows of four on the second screen. */
const val SecondColumns = 4
const val SecondRows = 3

/** The track screen's screens: the main one and the one a swipe shows (UI-121). */
const val TrackPages = 2

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
    tileChoices: () -> List<TileChoiceGroup> = { emptyList() },
    onChooseTile: (page: Int, slot: Int, id: String?) -> Unit = { _, _, _ -> },
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
    // The screen stays until a swipe back; the next session opens on the main one (UI-122).
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Moving off closes a tile's list: nothing covers the screen while the car moves.
    LaunchedEffect(state.editable) { if (!state.editable) editing = null }
    BetterDriverTheme(track = true) {
        HoldToStop(
            onStop,
            modifier.fillMaxSize().background(TrackTheme.colors.background),
            onSwipe = { step -> page = (page + step).coerceIn(0, TrackPages - 1) },
        ) {
            TrackScreenContent(
                state = state,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                page = page,
                onHoldTile = if (state.editable) { p, slot -> editing = p to slot } else null,
            )
        }
        editing?.let { (p, slot) ->
            val tiles = if (p == 0) state.tiles else state.secondTiles
            TilePicker(
                title = "Tile ${slot + 1}" + if (p == 0) "" else " · screen 2",
                current = tiles.getOrNull(slot)?.id,
                groups = tileChoices(),
                onChoose = { id ->
                    onChooseTile(p, slot, id)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

/**
 * In-car track screen, layout UI-10 option A with the data panel of FBBD-262: delta left, lap
 * times right, coach line, then two rows of tiles (UI-120 C). [page] 1 is the second screen: the
 * status strip, three rows of tiles and the coach line (UI-121 B). Designed on a phone in landscape
 * (914 x 411 dp, a Pixel 6); every slot has a fixed size so values never move. A window too short
 * for two rows of tiles (the system bars shown by a swipe) shows the first row only.
 * [onHoldTile], when set, is called with the page and slot of a tile held for 1 s (UI-123 B).
 */
@Composable
fun TrackScreenContent(
    state: TrackScreenState,
    modifier: Modifier = Modifier,
    page: Int = 0,
    onHoldTile: ((page: Int, slot: Int) -> Unit)? = null,
) {
    val c = TrackTheme.colors
    BoxWithConstraints(modifier.fillMaxSize().background(c.background).padding(Gap)) {
        val window = maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
            val top = Modifier.fillMaxWidth().height(TopHeight).testTag(TrackSlots.Top)
            val alert = state.alert
            if (alert != null) {
                AlertBanner(alert.title, alert.detail, alert.level, state.recordingTime, top)
            } else {
                StatusStrip(state.status, state.recordingTime, top, page = page, pages = TrackPages)
            }
            val coach = @Composable {
                CoachLine(
                    state.coachCorner,
                    state.coachCue,
                    Modifier.fillMaxWidth().height(CoachHeight).testTag(TrackSlots.Coach),
                    praise = state.coachPraise,
                )
            }
            if (page == 0) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    DeltaDisplay(state.deltaSeconds, Modifier.weight(0.62f).fillMaxHeight().testTag(TrackSlots.Delta))
                    LapTimes(
                        state.lapNumber,
                        state.lapMillis,
                        state.lastMillis,
                        state.bestMillis,
                        Modifier.weight(0.38f).fillMaxHeight().testTag(TrackSlots.Laps),
                    )
                }
                coach()
                val twoRows = window >= TwoRowsMinHeight
                val height = if (twoRows) PanelHeight else OneRowHeight
                TilePanel(
                    state.tiles,
                    MainColumns,
                    if (twoRows) MainRows else 1,
                    height,
                    MainValueSize,
                    TrackSlots::tile,
                    Modifier.fillMaxWidth().height(height).testTag(TrackSlots.Panel),
                    onHoldTile?.let { hold -> { slot: Int -> hold(0, slot) } },
                )
            } else {
                // The status strip and the coach line stay; the tiles take the rest (UI-121 B).
                val editing = onHoldTile != null
                if (shownRows(state.secondTiles, SecondColumns, SecondRows, editing).isEmpty()) {
                    Spacer(Modifier.fillMaxWidth().weight(1f))
                } else {
                    TilePanel(
                        state.secondTiles,
                        SecondColumns,
                        SecondRows,
                        window - TopHeight - CoachHeight - Gap * 2,
                        SecondValueSize,
                        TrackSlots::secondTile,
                        Modifier.fillMaxWidth().weight(1f).testTag(TrackSlots.Second),
                        onHoldTile?.let { hold -> { slot: Int -> hold(1, slot) } },
                    )
                }
                coach()
            }
        }
    }
}

private val Gap = 6.dp
private val TopHeight = 30.dp
private val CoachHeight = 44.dp

/** UI-120 C: the bottom panel grows from today's 80 dp row to 120 dp for two rows. */
private val PanelHeight = 120.dp
private val OneRowHeight = 80.dp

/** Lap times at their smallest (three rows of 31 dp numbers), with the delta beside them. */
private val MinMiddleHeight = 150.dp

/** The window that fits two rows of tiles: below it, the first row shows alone. */
private val TwoRowsMinHeight = Gap * 3 + TopHeight + CoachHeight + PanelHeight + MinMiddleHeight

/** A tile's value is fitted to its row, at most today's tile size on the main screen. */
private val MainValueSize = 34.dp
private val SecondValueSize = 46.dp

/** Test tags of the track screen's fixed slots, for the layout tests (FBBD-40, FBBD-62). */
object TrackSlots {
    const val Top = "track-top"
    const val Delta = "track-delta"
    const val Laps = "track-laps"
    const val Coach = "track-coach"

    /** The main screen's bottom panel. */
    const val Panel = "track-panel"

    /** The second screen's tiles. */
    const val Second = "track-second"

    /** A tile's list. */
    const val Picker = "track-tile-picker"

    /** The main screen's tile in slot [index], counted along the rows from the top left. */
    fun tile(index: Int) = "track-tile-$index"

    /** The second screen's tile in slot [index]. */
    fun secondTile(index: Int) = "track-second-tile-$index"
}

/** Example values matching the review mockups (round 31). Not real data. */
val ExampleTrackState = TrackScreenState(
    status = listOf(
        StatusItem("Track", "Silesia Ring"),
        StatusItem("GNSS", "25 Hz · 18 sats"),
        StatusItem("Camera", "1080p · 60"),
    ),
    recordingTime = "18:42",
    alert = null,
    deltaSeconds = -0.34,
    lapNumber = 8,
    lapMillis = 47_310,
    lastMillis = 112_810,
    bestMillis = 112_410,
    tiles = listOf(
        TrackTile("Speed", "142", "km/h", id = "speed"),
        TrackTile("G", "1.12", "g", id = "g"),
        TrackTile("Coolant", "92", "°C", id = "obd_coolant_temp"),
        TrackTile("Oil", "104", "°C", id = "obd_oil_temp"),
        TrackTile("Heart rate", "142", "bpm", id = "heart_rate"),
        TrackTile("RPM", "5840", "rpm", id = "obd_rpm"),
        TrackTile("Throttle", "87", "%", id = "obd_throttle"),
        TrackTile("Intake air", "24", "°C", id = "obd_intake_air_temp"),
        TrackTile("Battery", "14.1", "V", id = "obd_module_voltage"),
        TrackTile("Last lap", "1:52.81", "", id = "last_lap"),
    ),
    coachCorner = "T4",
    coachCue = "Brake at 100 m · 10 later",
    secondTiles = listOf(
        TrackTile("RPM", "5840", "rpm", id = "obd_rpm"),
        TrackTile("Throttle", "87", "%", id = "obd_throttle"),
        TrackTile("Coolant", "92", "°C", id = "obd_coolant_temp"),
        TrackTile("Oil", "104", "°C", id = "obd_oil_temp"),
        TrackTile("Intake air", "24", "°C", id = "obd_intake_air_temp"),
        TrackTile("Battery", "14.1", "V", id = "obd_module_voltage"),
        TrackTile("Engine load", "78", "%", id = "obd_engine_load"),
        TrackTile("Manifold", "96", "kPa", id = "obd_map"),
        TrackTile("Last lap", "1:52.81", "", id = "last_lap"),
        TrackTile("Best lap", "1:52.41", "", id = "best_lap"),
        TrackTile("Heart rate", "142", "bpm", id = "heart_rate"),
        null,
    ),
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
                coachCorner = null, coachCue = null,
            ),
        )
    }
}

@Preview(name = "Track screen, second screen", widthDp = 914, heightDp = 411)
@Composable
private fun TrackScreenSecondPreview() {
    BetterDriverTheme(track = true) { TrackScreenContent(ExampleTrackState, page = 1) }
}
