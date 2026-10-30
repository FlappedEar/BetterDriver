@file:Suppress("TooManyFunctions") // One screen, split into its sections.

package com.flappedear.betterdriver.feature.analysis

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.progression.ProgressionDay
import com.flappedear.betterdriver.analysis.progression.ProgressionGrouping
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/** The progression screen's cards, for tests. */
const val ProgressionLapsTag = "progression-laps"
const val ProgressionCornersTag = "progression-corners"
const val ProgressionCoachingTag = "progression-coaching"
const val ProgressionPointsTag = "progression-points"

/** What the progression screen is showing. */
sealed interface ProgressionState {
    data object Loading : ProgressionState

    data class Ready(val ui: ProgressionUi) : ProgressionState
}

/** What the driver can change on the progression screen. */
interface ProgressionActions {
    fun grouping(grouping: ProgressionGrouping) {}

    fun period(period: ProgressionPeriod) {}

    /** Shows only [day]'s sessions, or every day again when null. */
    fun day(day: ProgressionDay?) {}

    /** Leaves session [sessionId] out of the progression, or counts it again. */
    fun toggle(sessionId: String) {}

    /** Opens every pass through corner [cornerId] in session [sessionId]: their traces and video (FBBD-93). */
    fun cornerPasses(sessionId: String, cornerId: String) {}
}

/** On an opened corner of the progression: its passes in the latest session that drove it (FBBD-93). */
const val LatestPassesText = "Every pass, latest session ›"

/**
 * Progression at one track (FBBD-181): the laps, then each corner by its CornerId, then what came
 * of the coaching, point by point (session, track day or month) with the way each is going, and
 * the sessions it is made of, imported ones marked with where they came from. Numbers only where
 * they are precise. [topBar] is the app's bar with its back button.
 */
@Composable
fun TrackProgressionScreen(
    state: ProgressionState,
    actions: ProgressionActions,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        topBar()
        when (state) {
            ProgressionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ProgressionState.Ready -> Ready(state.ui, actions)
        }
    }
}

@Composable
private fun Ready(ui: ProgressionUi, actions: ProgressionActions) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val width = Modifier.widthIn(max = 720.dp).fillMaxWidth()
        Choices(ui, actions, width)
        if (ui.empty) {
            Card(width) {
                Dim(
                    if (ui.adding > 0) {
                        "Adding the sessions recorded here…"
                    } else {
                        "No analysed session here in this period. A session takes part once its lap analysis is made."
                    },
                )
            }
            return@Column
        }
        Laps(ui, width)
        if (ui.corners.isNotEmpty()) Corners(ui, actions, width)
        if (ui.coaching.isNotEmpty()) Coaching(ui, width)
        Points(ui, actions, width)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(ui: ProgressionUi, actions: ProgressionActions, modifier: Modifier) {
    Card(modifier) {
        Text(ui.title, color = TrackTheme.colors.text, fontWeight = FontWeight.SemiBold)
        Dim(ui.summary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((g, title) in GroupingTitles) {
                FilterChip(selected = ui.grouping == g, onClick = { actions.grouping(g) }, label = { Text(title) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in ProgressionPeriod.entries) {
                FilterChip(
                    selected = ui.period == p && ui.day == null,
                    onClick = {
                        actions.day(null)
                        actions.period(p)
                    },
                    label = { Text(p.label) },
                )
            }
            ui.day?.let { d ->
                FilterChip(selected = true, onClick = { actions.day(null) }, label = { Text("$d ✕") })
            }
        }
        if (ui.adding > 0) {
            Dim("Adding ${ui.adding} more ${if (ui.adding == 1) "session" else "sessions"} recorded here…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        ui.notes.forEach { Dim(it) }
    }
}

private val GroupingTitles = listOf(
    ProgressionGrouping.Session to "Session",
    ProgressionGrouping.TrackDay to "Track day",
    ProgressionGrouping.Month to "Month",
)

@Composable
private fun Laps(ui: ProgressionUi, modifier: Modifier) {
    Card(modifier.testTag(ProgressionLapsTag)) {
        TrackLabel("Laps")
        ui.laps.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Metric(row)
        }
        // One track day open: how its lap times went, lap by lap (FBBD-175).
        ui.dayLaps?.let {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Metric(it)
        }
    }
}

@Composable
private fun Corners(ui: ProgressionUi, actions: ProgressionActions, modifier: Modifier) {
    Card(modifier.testTag(ProgressionCornersTag)) {
        TrackLabel("Corners · tap one for more")
        ui.corners.forEachIndexed { i, corner ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Corner(corner, actions)
        }
    }
}

@Composable
private fun Corner(corner: CornerRow, actions: ProgressionActions) {
    var open by rememberSaveable(corner.cornerId) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { open = !open },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Metric(corner.time, title = corner.name)
        if (open) {
            corner.details.forEach { Metric(it, small = true) }
            if (corner.details.isEmpty()) Dim("Nothing else measured here.")
            corner.sessionId?.let { session ->
                TextButton(onClick = { actions.cornerPasses(session, corner.cornerId) }) { Text(LatestPassesText) }
            }
        }
    }
}

/** One metric: its name and latest value, a small chart of its points, and its trend in words. */
@Composable
private fun Metric(row: MetricRow, title: String? = null, small: Boolean = false) {
    val c = TrackTheme.colors
    val toneColor = when (row.tone) {
        TrendTone.Better -> c.gain
        TrendTone.Worse -> c.loss
        TrendTone.Neutral -> c.text
        TrendTone.Steady, TrendTone.Unknown -> c.textDim
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, color = c.text, fontWeight = FontWeight.SemiBold)
                if (title == null || small) Text(row.label, color = if (small) c.textDim else c.text)
                if (title != null && !small) Dim(row.label)
            }
            if (row.latest.isNotEmpty()) {
                Text(row.latest, color = if (row.doubtful) c.textDim else c.text, fontWeight = FontWeight.SemiBold)
            }
            Sparkline(row, toneColor, Modifier.padding(start = 12.dp).width(SparkWidth).height(SparkHeight))
        }
        Text(
            row.trend + if (row.doubtful) " · not every pass is precise, so no number" else "",
            color = toneColor,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val SparkWidth = 88.dp
private val SparkHeight = 28.dp

/**
 * The metric at each point, oldest on the left; a time drawn upside down, so up is always quicker.
 * No numbers on it: the value and its trend are said beside it, where they may be.
 */
@Composable
private fun Sparkline(row: MetricRow, color: Color, modifier: Modifier) {
    val points = row.values.mapIndexedNotNull { i, v -> v?.let { i to it } }
    val line = TrackTheme.colors.textDim
    Canvas(modifier.semantics { contentDescription = "${row.label}: ${row.trend}" }) {
        if (points.isEmpty()) return@Canvas
        val lo = points.minOf { it.second }
        val hi = points.maxOf { it.second }
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val last = (row.values.size - 1).coerceAtLeast(1)
        fun at(i: Int, v: Double): Offset {
            val share = ((v - lo) / span).toFloat()
            val y = if (row.invert) share else 1f - share
            return Offset(size.width * i / last, if (hi == lo) size.height / 2 else y * size.height)
        }
        points.zipWithNext().forEach { (a, b) ->
            drawLine(
                line, at(a.first, a.second), at(b.first, b.second),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        val (i, v) = points.last()
        drawCircle(color, radius = 3.dp.toPx(), center = at(i, v))
    }
}

@Composable
private fun Coaching(ui: ProgressionUi, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier.testTag(ProgressionCoachingTag)) {
        TrackLabel("Coaching · what came of each focus")
        ui.coachingSummary?.let { Text(it, color = c.text) }
        for (row in ui.coaching) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Dim(row.label)
                Text(
                    row.text,
                    color = when (row.tone) {
                        TrendTone.Better -> c.gain
                        TrendTone.Worse -> c.loss
                        else -> c.text
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Points(ui: ProgressionUi, actions: ProgressionActions, modifier: Modifier) {
    val c = TrackTheme.colors
    val title = when (ui.grouping) {
        ProgressionGrouping.Session -> "Sessions · untick one to leave it out"
        ProgressionGrouping.TrackDay -> "Track days · tap one for its sessions"
        ProgressionGrouping.Month -> "Months"
    }
    Card(modifier.testTag(ProgressionPointsTag)) {
        TrackLabel(title)
        for (p in ui.points) {
            val tap: (() -> Unit)? = when {
                ui.grouping == ProgressionGrouping.Session -> { -> actions.toggle(p.key) }
                p.day != null -> { -> actions.day(p.day) }
                else -> null
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (tap != null) Modifier.clickable(onClick = tap) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui.grouping == ProgressionGrouping.Session) {
                    Checkbox(checked = !p.excluded, onCheckedChange = { actions.toggle(p.key) })
                }
                Column(Modifier.weight(1f)) {
                    Text(p.label, color = if (p.excluded) c.textDim else c.text)
                    Dim(p.detail)
                    p.origin?.let { Dim(it, color = c.cold) }
                }
                Text(p.best, color = if (p.excluded) c.textDim else c.text, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Preview(widthDp = 400, heightDp = 1600)
@Composable
private fun TrackProgressionPreview() {
    BetterDriverTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            TrackProgressionScreen(ProgressionState.Ready(previewProgressionUi()), object : ProgressionActions {})
        }
    }
}
