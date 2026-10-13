@file:Suppress("TooManyFunctions") // One screen, split into its sections.

package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.NumberText
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.core.ui.format.isDeltaGain
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One session's analysis (FBBD-102 and FBBD-19, UI review round 14, UI-61 to UI-63 and UI-67): the fastest
 * lap and the theoretical best, where time goes (what the fastest lap leaves in each corner and
 * the other laps' largest losses), the lap compared with the fastest by distance (time delta and
 * speed, corners shaded, a cursor to read values), every lap's time, and the theoretical best
 * corner by corner with the compared lap's loss and the spread of the counted laps, one section
 * per tab (UI-62 B), and the compared lap on the map with its G-G diagram (FBBD-149). Tapping a lap
 * or a loss compares it. [onCoachPick] picks or unpicks a corner (segment id) for the spoken coach's
 * next session at the track (FBBD-148). [topBar] is the app's bar with its back button. [layout] is the
 * size class of the space the screen has (FBBD-40): it sets the map and the G-G diagram side by side or not.
 * [optimalVideo], when given, goes under the theoretical best: the optimal lap's video (FBBD-163).
 */
@Composable
fun AnalysisScreen(
    state: AnalysisState,
    onCompare: (lapIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
    onCoachPick: (cornerId: String) -> Unit = {},
    layout: LayoutSize = LayoutSize.Phone,
    topBar: @Composable () -> Unit = {},
    optimalVideo: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxSize()) {
        topBar()
        when (state) {
            AnalysisState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is AnalysisState.Unavailable -> WithAcceleration(state.acceleration, state.quality, state.gForces) {
                Card {
                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                    state.action?.let { FilledTonalButton(onClick = onAction) { Text(it) } }
                }
            }
            is AnalysisState.Ready -> Ready(state.ui, onCompare, onCoachPick, optimalVideo, layout)
        }
    }
}

/** The sections of the analysis, one per tab (UI-62 B). */
internal enum class AnalysisTab(val title: String) {
    Summary("Summary"),
    Compare("Compare"),
    Map("Map"),
    Laps("Laps"),
    Corners("Corners"),
}

@Suppress("CyclomaticComplexMethod") // One branch per tab.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Ready(
    ui: AnalysisUi,
    onCompare: (Int) -> Unit,
    onCoachPick: (String) -> Unit,
    optimalVideo: (@Composable () -> Unit)?,
    layout: LayoutSize,
) {
    var tab by rememberSaveable { mutableIntStateOf(AnalysisTab.Summary.ordinal) }
    // Picking a lap or a loss compares it, so the Compare tab opens.
    val compare: (Int) -> Unit = {
        onCompare(it)
        tab = AnalysisTab.Compare.ordinal
    }
    PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
        AnalysisTab.entries.forEach { t ->
            Tab(selected = tab == t.ordinal, onClick = { tab = t.ordinal }, text = { Text(t.title) })
        }
    }
    // Each tab opens at its top: a new scroll state whenever the tab changes.
    key(tab) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val width = Modifier.widthIn(max = 720.dp).fillMaxWidth()
            // The map and the G-G diagram sit side by side on a wide screen, so their tab is wider.
            val wide = Modifier.widthIn(max = 1200.dp).fillMaxWidth()
            when (AnalysisTab.entries[tab]) {
                AnalysisTab.Summary -> {
                    // What to work on next comes first (FBBD-20): the answer the session is analysed for.
                    Focus(ui, compare, layout, width)
                    Summary(ui, width, optimalVideo)
                    ui.progress?.let { Progress(it, width) }
                    if (ui.gains.isNotEmpty() || ui.losses.isNotEmpty()) TimeGoes(ui, compare, onCoachPick, width)
                    ui.gForces?.let { GForcesCard(it, width) }
                    ui.acceleration?.let { AccelerationCard(it, width) }
                    ui.quality?.let { QualityCard(it, width) }
                }
                AnalysisTab.Compare -> ui.compare?.let { Compare(it, width) }
                    ?: Card(width) { Dim("No other counted lap to compare with the best lap.") }
                AnalysisTab.Map -> ui.map?.let { MapTab(it, layout, wide) }
                    ?: Card(width) { Dim("No map: the laps of this session have no usable GPS line.") }
                AnalysisTab.Laps -> Laps(ui, compare, width)
                AnalysisTab.Corners -> {
                    Sectors(ui, width)
                    CornerDetails(ui, width)
                }
            }
        }
    }
}

@Composable
private fun Summary(ui: AnalysisUi, modifier: Modifier, optimalVideo: (@Composable () -> Unit)? = null) {
    val c = TrackTheme.colors
    Card(modifier) {
        // The three lap times, never one name for two of them (FBBD-178): the best actual lap and the
        // realistic optimal side by side, the raw theoretical best under them, smaller.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Figure(
                BestActualLapLabel,
                formatLapTime(ui.bestLapMillis),
                "Lap ${ui.bestLapNumber}",
                c.best,
                Modifier.weight(1f),
            )
            Figure(
                RealisticOptimalLabel,
                realisticText(ui, ::formatLapTime),
                realisticDetail(ui),
                if (ui.realisticGainSeconds != null && !ui.gainConfidence.doubtful) c.gain else c.text,
                Modifier.weight(1f),
            )
        }
        Figure(
            RawTheoreticalBestLabel,
            theoreticalText(ui, ::formatLapTime),
            if (ui.gainSeconds == null) {
                "Best of each corner, never driven as one lap"
            } else {
                "${gainText(ui, ::formatDelta)} s · best of each corner, never driven as one lap"
            },
            c.textDim,
            Modifier.fillMaxWidth(),
            size = RawTheoreticalSize,
        )
        if (ui.gainSeconds != null) ConfidenceNote(ui.gainConfidence, about = "Time to gain")
        if (ui.theoreticalMillis != null) ProvenanceLegend()
        optimalVideo?.invoke()
    }
}

/** Under the realistic optimal: the time to gain to it and the laps it combines. */
private fun realisticDetail(ui: AnalysisUi): String {
    val laps = ui.realisticLaps
    val from = when {
        laps.isEmpty() -> "compatible joints only"
        laps == listOf(ui.bestLapNumber) -> "no better combination"
        else -> "laps ${laps.joinToString(", ")}"
    }
    return if (ui.realisticGainSeconds == null) from else "${realisticGainText(ui, ::formatDelta)} s · $from"
}

@Composable
private fun Figure(
    label: String,
    value: String,
    detail: String,
    color: Color,
    modifier: Modifier,
    size: Dp = FigureSize,
) {
    Column(modifier) {
        TrackLabel(label)
        NumberText(value, size = size, color = color, weight = FontWeight.SemiBold)
        Dim(detail)
    }
}

private val FigureSize = 26.dp

/** The raw theoretical best is a reference, not a target: smaller than the two lap times above it. */
private val RawTheoreticalSize = 20.dp

@Composable
private fun Compare(ui: CompareUi, modifier: Modifier) {
    val c = TrackTheme.colors
    var cursor by rememberSaveable(ui.otherNumber) { mutableStateOf<Double?>(null) }
    val corner = MaterialTheme.colorScheme.surfaceVariant
    val grid = MaterialTheme.colorScheme.outline
    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lap ${ui.otherNumber}", color = c.cold, fontWeight = FontWeight.SemiBold)
            Dim(" vs best lap ")
            Text("${ui.referenceNumber}", color = c.best, fontWeight = FontWeight.SemiBold)
        }
        CursorReadout(ui, cursor)
        TrackLabel(marked("Time delta", DeltaProvenance))
        LapChart(
            lines = listOf(ChartLine(ui.delta, c.warning)),
            lengthMeters = ui.lengthMeters,
            corners = ui.corners,
            cornerColor = corner,
            gridColor = grid,
            cursorColor = c.text,
            cursorMeters = cursor,
            onCursor = { cursor = it },
            modifier = Modifier.fillMaxWidth().height(110.dp),
            zeroLine = true,
        )
        TrackLabel(marked("Speed, km/h", ui.speedProvenance))
        LapChart(
            lines = listOf(ChartLine(ui.referenceSpeed, c.best), ChartLine(ui.otherSpeed, c.cold)),
            lengthMeters = ui.lengthMeters,
            corners = ui.corners,
            cornerColor = corner,
            gridColor = grid,
            cursorColor = c.text,
            cursorMeters = cursor,
            onCursor = { cursor = it },
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
        Row {
            Dim("0 m", Modifier.weight(1f))
            Dim("${ui.lengthMeters.roundToInt()} m")
        }
        ProvenanceLegend()
    }
}

@Composable
private fun CursorReadout(ui: CompareUi, cursor: Double?) {
    val c = TrackTheme.colors
    if (cursor == null) {
        Dim("Touch the charts to read a point. Shaded: corners.")
        return
    }
    fun speed(runs: List<List<ChartPoint>>) =
        marked(valueAt(runs, cursor)?.let { "${it.roundToInt()}" } ?: Missing, ui.speedProvenance)
    val delta = valueAt(ui.delta, cursor)
    val corner = ui.corners.firstOrNull { inBand(it, cursor) }?.name
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Dim("${cursor.roundToInt()} m" + (corner?.let { " · $it" } ?: ""))
        Text(
            marked(delta?.let(::formatDelta) ?: Missing, DeltaProvenance),
            color = when {
                delta == null -> c.textDim
                isDeltaGain(delta) -> c.gain
                else -> c.loss
            },
            fontWeight = FontWeight.SemiBold,
        )
        Text(speed(ui.referenceSpeed), color = c.best)
        Text(speed(ui.otherSpeed), color = c.cold)
    }
}

private fun inBand(band: CornerBand, meters: Double) = if (band.endMeters >= band.startMeters) {
    meters in band.startMeters..band.endMeters
} else {
    meters >= band.startMeters || meters <= band.endMeters
}

@Composable
private fun Laps(ui: AnalysisUi, onCompare: (Int) -> Unit, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        TrackLabel("Laps · tap one to compare")
        for (lap in ui.laps) {
            val compared = ui.compare?.otherNumber == lap.number
            val counted = lap.deltaSeconds != null
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (compared) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable(enabled = counted && !lap.best) { onCompare(lap.index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(8.dp).background(
                        when {
                            lap.best -> c.best
                            compared -> c.cold
                            else -> Color.Transparent
                        },
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Dim("Lap ${lap.number}", Modifier.width(56.dp))
                Text(
                    formatLapTime(lap.millis),
                    color = if (lap.best) c.best else if (counted) c.text else c.textDim,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                when {
                    lap.note.isNotEmpty() -> Dim(lap.note, color = c.warning)
                    lap.best -> Dim("Best", color = c.best)
                    lap.deltaSeconds != null -> Text(formatDelta(lap.deltaSeconds), color = c.loss)
                }
            }
        }
    }
}

@Composable
private fun Sectors(ui: AnalysisUi, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        TrackLabel("Raw theoretical best by corner")
        ui.sectorsNote?.let { Dim(it) }
        if (ui.sectors.isEmpty()) return@Card
        Row {
            Dim("", Modifier.weight(1f))
            Dim("Best", Modifier.width(76.dp))
            Dim("Lap ${ui.compare?.otherNumber ?: ""}", Modifier.width(64.dp))
            Dim("Spread", Modifier.width(SpreadWidth))
        }
        for (s in ui.sectors) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name,
                    color = if (s.corner) c.text else c.textDim,
                    modifier = Modifier.weight(1f),
                )
                Column(Modifier.width(76.dp)) {
                    Text(s.bestSeconds?.let(::seconds) ?: "–", fontWeight = FontWeight.SemiBold)
                    s.bestLapNumber?.let { Dim("lap $it") }
                }
                Text(
                    s.lossSeconds?.let(::formatDelta) ?: "–",
                    color = if ((s.lossSeconds ?: 0.0) > MeaningfulLossSeconds) c.loss else c.textDim,
                    modifier = Modifier.width(64.dp),
                )
                Dim(s.spreadSeconds?.let(::seconds) ?: "–", Modifier.width(SpreadWidth))
            }
        }
    }
}

private val SpreadWidth = 52.dp

internal fun seconds(value: Double) = String.format(Locale.ROOT, "%.2f", value)

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The legend line: what [NotMeasuredMark] means (FBBD-174). */
@Composable
internal fun ProvenanceLegend(modifier: Modifier = Modifier) {
    Dim(ProvenanceLegendText, modifier.testTag(ProvenanceLegendTag))
}

@Composable
internal fun Dim(text: String, modifier: Modifier = Modifier, color: Color = TrackTheme.colors.textDim) {
    Text(text, modifier = modifier, color = color, style = MaterialTheme.typography.bodyMedium)
}

/**
 * A conclusion's confidence next to it (FBBD-177): "High confidence", or below High its level and
 * why, in the warning colour.
 */
@Composable
internal fun ConfidenceNote(confidence: ConfidenceUi, modifier: Modifier = Modifier, about: String? = null) {
    val c = TrackTheme.colors
    val text = if (about == null) confidence.note else "$about: ${confidence.note}"
    Dim(text, modifier, color = if (confidence.doubtful) c.warning else c.textDim)
}

@Preview(widthDp = 400, heightDp = 1400)
@Composable
private fun AnalysisScreenPreview() {
    BetterDriverTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            AnalysisScreen(AnalysisState.Ready(PreviewUi.copy(map = PreviewMap)), onCompare = {})
        }
    }
}
