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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.NumberText
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.core.ui.format.isDeltaGain
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One session's analysis (FBBD-102 and FBBD-19, UI review round 14, UI-61 to UI-63 and UI-67): the fastest
 * lap and the theoretical best, where time goes (what the fastest lap leaves in each corner and
 * the other laps' largest losses), the lap compared with the fastest by distance (time delta and
 * speed, corners shaded, a cursor to read values), every lap's time, and the theoretical best
 * corner by corner with the compared lap's loss and the spread of the counted laps, one section
 * per tab (UI-62 B). Tapping a lap or a loss compares it. [topBar] is the app's bar with its back
 * button.
 */
@Composable
fun AnalysisScreen(
    state: AnalysisState,
    onCompare: (lapIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
    topBar: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        topBar()
        when (state) {
            AnalysisState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is AnalysisState.Unavailable -> Box(Modifier.padding(16.dp)) {
                Card {
                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                    state.action?.let { FilledTonalButton(onClick = onAction) { Text(it) } }
                }
            }
            is AnalysisState.Ready -> Ready(state.ui, onCompare)
        }
    }
}

/** The sections of the analysis, one per tab (UI-62 B). */
internal enum class AnalysisTab(val title: String) {
    Summary("Summary"),
    Compare("Compare"),
    Laps("Laps"),
    Corners("Corners"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Ready(ui: AnalysisUi, onCompare: (Int) -> Unit) {
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
            when (AnalysisTab.entries[tab]) {
                AnalysisTab.Summary -> {
                    Summary(ui, width)
                    ui.progress?.let { Progress(it, width) }
                    if (ui.focus.isNotEmpty()) Focus(ui, compare, width)
                    if (ui.gains.isNotEmpty() || ui.losses.isNotEmpty()) TimeGoes(ui, compare, width)
                }
                AnalysisTab.Compare -> ui.compare?.let { Compare(it, width) }
                    ?: Card(width) { Dim("No other counted lap to compare with the best lap.") }
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
private fun Summary(ui: AnalysisUi, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Figure("Best lap", formatLapTime(ui.bestLapMillis), "Lap ${ui.bestLapNumber}", c.best, Modifier.weight(1f))
            Figure(
                "Theoretical",
                ui.theoreticalMillis?.let(::formatLapTime) ?: "–",
                "Best of each corner",
                c.text,
                Modifier.weight(1f),
            )
            Figure(
                "To gain",
                ui.gainSeconds?.let { formatDelta(-it) } ?: "–",
                "s",
                if (ui.gainSeconds != null) c.gain else c.textDim,
                Modifier.weight(0.8f),
            )
        }
    }
}

@Composable
private fun Figure(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        TrackLabel(label)
        NumberText(value, size = 26.dp, color = color, weight = FontWeight.SemiBold)
        Dim(detail)
    }
}

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
        TrackLabel("Time delta")
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
        TrackLabel("Speed, km/h")
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
    }
}

@Composable
private fun CursorReadout(ui: CompareUi, cursor: Double?) {
    val c = TrackTheme.colors
    if (cursor == null) {
        Dim("Touch the charts to read a point. Shaded: corners.")
        return
    }
    fun speed(runs: List<List<ChartPoint>>) = valueAt(runs, cursor)?.let { "${it.roundToInt()}" } ?: "–"
    val delta = valueAt(ui.delta, cursor)
    val corner = ui.corners.firstOrNull { inBand(it, cursor) }?.name
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Dim("${cursor.roundToInt()} m" + (corner?.let { " · $it" } ?: ""))
        Text(
            delta?.let(::formatDelta) ?: "–",
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
        TrackLabel("Theoretical best by corner")
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

@Composable
internal fun Dim(text: String, modifier: Modifier = Modifier, color: Color = TrackTheme.colors.textDim) {
    Text(text, modifier = modifier, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Preview(widthDp = 400, heightDp = 1400)
@Composable
private fun AnalysisScreenPreview() {
    BetterDriverTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            AnalysisScreen(AnalysisState.Ready(PreviewUi), onCompare = {})
        }
    }
}
