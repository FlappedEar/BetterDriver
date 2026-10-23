@file:Suppress("TooManyFunctions") // One screen, split into its sections.

package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.NumberFont
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/** The track day page's cards, for tests. */
const val TrackDayNumbersTag = "track-day-numbers"
const val TrackDaySessionsTag = "track-day-sessions"
const val TrackDayCornersTag = "track-day-corners"
const val TrackDayNextTag = "track-day-next"
const val TrackDayProofTag = "track-day-proof"

/**
 * The track day (FBBD-315, UI round 48): the day's best, typical and perfect lap with each
 * session's best and typical lap (UI-211 A), the corners where a typical lap loses most, in words,
 * with a small line of each over the day (UI-212 A, UI-213 A), what the next day here works on and
 * what came of the last day here (UI-214 B). [topBar] is the app's bar with its back button.
 */
@Composable
fun TrackDayScreen(state: TrackDayState, modifier: Modifier = Modifier, topBar: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxSize()) {
        topBar()
        when (state) {
            TrackDayState.Reading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Dim("Reading every lap of the day…")
                }
            }
            is TrackDayState.Unavailable -> Box(Modifier.padding(16.dp)) { Card { Dim(state.why) } }
            is TrackDayState.Ready -> Ready(state.ui)
        }
    }
}

@Composable
private fun Ready(ui: TrackDayUi) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val width = Modifier.widthIn(max = 720.dp).fillMaxWidth()
        Column(width, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ui.title, color = TrackTheme.colors.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Dim(ui.subtitle)
        }
        Numbers(ui, width)
        if (ui.sessions.size > 1) Sessions(ui, width)
        ui.proof?.let { Proof(it, width) }
        if (ui.corners.isNotEmpty()) Corners(ui, width)
        if (ui.next.isNotEmpty()) Next(ui, width)
    }
}

@Composable
private fun Numbers(ui: TrackDayUi, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier.testTag(TrackDayNumbersTag)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure("Best", ui.best, c.best, Modifier.weight(1f))
            Figure("Typical", ui.typical, c.text, Modifier.weight(1f))
            Figure("Perfect", ui.perfect, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        }
        Text(ui.loss, color = c.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Figure(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TrackLabel(label)
        Text(value, color = color, fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    }
}

/** Each session's typical and best lap as two bars, in the order they were driven (UI-211 A). */
@Composable
private fun Sessions(ui: TrackDayUi, modifier: Modifier) {
    val c = TrackTheme.colors
    val typicalColor = MaterialTheme.colorScheme.primary.copy(alpha = TypicalAlpha)
    Card(modifier.testTag(TrackDaySessionsTag)) {
        TrackLabel("Over the day")
        val values = ui.sessions.flatMap { listOfNotNull(it.bestSeconds, it.typicalSeconds) }
        if (values.isNotEmpty()) {
            val low = values.min() - ChartFloorSeconds
            val high = values.max()
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val slot = size.width / ui.sessions.size
                val bar = slot * BarShare / 2
                fun top(v: Double) = (size.height * (1 - (v - low) / (high - low))).toFloat()
                ui.sessions.forEachIndexed { i, s ->
                    val x = slot * i + (slot - 2 * bar) / 2
                    s.typicalSeconds?.let {
                        drawRect(typicalColor, Offset(x, top(it)), Size(bar, size.height - top(it)))
                    }
                    s.bestSeconds?.let { drawRect(c.best, Offset(x + bar, top(it)), Size(bar, size.height - top(it))) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                ui.sessions.forEach {
                    Text(
                        it.label, Modifier.weight(1f), color = c.textDim, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend("best lap", c.best)
                Legend("typical lap", typicalColor)
            }
        }
        ui.overDay?.let { Text(it, color = c.text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color))
        Dim(text)
    }
}

@Composable
private fun Corners(ui: TrackDayUi, modifier: Modifier) {
    Card(modifier.testTag(TrackDayCornersTag)) {
        TrackLabel("Where the day can get faster · per lap")
        ui.corners.forEachIndexed { i, corner ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Corner(corner)
        }
        Dim(
            "The typical pass of your laps at the day's pace (within $PaceSharePercent % of your best) against " +
                "your second fastest pass of the day. Under 0.05 s is GPS timing.",
        )
    }
}

@Composable
private fun Corner(corner: TrackDayCornerUi) {
    val c = TrackTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(corner.tag, Modifier.width(40.dp), color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                corner.kind, Modifier.weight(1f), color = if (corner.learn) c.warning else c.textDim,
                style = MaterialTheme.typography.labelMedium,
            )
            Trend(corner.trend)
            Text(
                "${seconds(corner.perLapSeconds)} s", color = MaterialTheme.colorScheme.primary,
                fontFamily = NumberFont, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
        }
        Todo(corner.todo)
    }
}

/** The corner's typical pass in each session, first session on the left, lower is faster (UI-213 A). */
@Composable
private fun Trend(values: List<Double?>) {
    val line = TrackTheme.colors.textDim
    val known = values.filterNotNull()
    if (known.size < 2) return
    val low = known.min()
    val span = (known.max() - low).takeIf { it > 0.0 } ?: 1.0
    Canvas(Modifier.size(width = 70.dp, height = 22.dp)) {
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        // Faster (lower) at the bottom, as a lap time chart reads.
        fun at(i: Int, v: Double) = Offset(step * i, (size.height * (1 - (v - low) / span)).toFloat())
        var last: Offset? = null
        values.forEachIndexed { i, v ->
            val p = v?.let { at(i, it) }
            if (p != null && last != null) drawLine(line, last!!, p, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            if (p != null) last = p
        }
    }
}

@Composable
private fun Todo(lines: List<String>) {
    lines.forEach { t ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("›", color = MaterialTheme.colorScheme.primary)
            Text(t, color = TrackTheme.colors.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Next(ui: TrackDayUi, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier.testTag(TrackDayNextTag)) {
        TrackLabel("Next time here · work on")
        ui.next.forEachIndexed { i, corner ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(corner.tag, Modifier.weight(1f), color = c.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "${seconds(corner.perLapSeconds)} s a lap", color = MaterialTheme.colorScheme.primary,
                    fontFamily = NumberFont, fontWeight = FontWeight.Bold,
                )
            }
            Todo(corner.todo)
        }
        ui.coachLine?.let { Dim(it) }
        Dim("Your next day here measures these: their typical pass then against now.")
    }
}

@Composable
private fun Proof(proof: TrackDayProofUi, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier.testTag(TrackDayProofTag)) {
        TrackLabel(proof.title)
        proof.wonBack?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    it, color = if (proof.better) c.gain else c.loss, fontFamily = NumberFont,
                    fontWeight = FontWeight.Bold, fontSize = 24.sp,
                )
                Dim("a lap ${if (proof.better) "back" else "lost"} on the corners that day said")
            }
        }
        proof.rows.forEach { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.tag, Modifier.weight(1f), color = c.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    row.verdict, fontWeight = FontWeight.Bold,
                    color = when (row.tone) {
                        ProofTone.Better -> c.gain
                        ProofTone.Worse -> c.loss
                        else -> c.textDim
                    },
                )
            }
            Text(row.line, color = c.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val TypicalAlpha = 0.55f
private const val BarShare = 0.7f

/** The chart's bars start this far below the quickest lap, so the quickest still shows a bar. */
private const val ChartFloorSeconds = 2.0

@Preview(widthDp = 400, heightDp = 1800)
@Composable
private fun TrackDayPreview() {
    BetterDriverTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            TrackDayScreen(TrackDayState.Ready(PreviewTrackDayUi))
        }
    }
}
