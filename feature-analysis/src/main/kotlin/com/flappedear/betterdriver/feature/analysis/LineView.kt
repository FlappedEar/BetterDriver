package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.layout.LayoutSize

/** Test tag of the view, and of each corner's row in its list. */
const val LineViewTag = "line-view"
const val LineRowTagPrefix = "line-row-"

/**
 * "Where to find time" in the Map tab (FBBD-306; UI-164 A, UI-165 C, UI-166 A, UI-167 B): the
 * whole lap with time to find per corner and a list ranked by it; tapping a corner zooms the map to
 * it and the list becomes what to do there. His lap is coloured by what the pedals do, the target
 * is a thin green line. The map and the list sit side by side when [layout] has room for it. The
 * tapped corner is [selected], chosen through [pick], so the perfect lap in 3D can start there.
 */
@Composable
internal fun LineView(
    ui: LineUi,
    layout: LayoutSize,
    modifier: Modifier = Modifier,
    selected: String? = null,
    pick: (String?) -> Unit = {},
) {
    val corner = ui.corners.firstOrNull { it.tag == selected }
    Box(modifier.testTag(LineViewTag)) {
        if (layout.sideBySide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LineMapCard(ui, corner, pick, Modifier.weight(3f))
                LineSide(ui, corner, pick, Modifier.weight(2f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LineMapCard(ui, corner, pick, Modifier)
                LineSide(ui, corner, pick, Modifier)
            }
        }
    }
}

@Composable
private fun LineMapCard(ui: LineUi, corner: LineCornerUi?, pick: (String?) -> Unit, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        LineMapView(ui, corner, pick, Modifier.fillMaxWidth().aspectRatio(if (corner == null) 1.3f else 1.1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(c.loss, "Braking")
            Key(c.textDim, "Coasting")
            Key(c.gain, "Throttle")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(c.text, "Your best lap ${ui.bestLapNumber}, ${lineLapTime(ui.bestLapSeconds)}, by pedals")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val target = corner?.let { "Target: ${it.targetFrom}" } ?: "Target: your second fastest pass of each corner"
            Key(c.gain, target)
        }
    }
}

@Composable
private fun LineSide(ui: LineUi, corner: LineCornerUi?, pick: (String?) -> Unit, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Card(modifier) {
        if (corner == null) {
            Row(verticalAlignment = Alignment.Bottom) {
                TrackLabel("Time to find", Modifier.weight(1f))
                Text("≈ ${seconds(ui.totalGainSeconds)} s", color = accent, fontWeight = FontWeight.Bold)
            }
            Dim(
                "Your best lap against your target through each corner: your second fastest pass there, " +
                    "from ${ui.laps} laps in ${sessionsText(ui.sessions)} at this track. " +
                    "Tap a corner to see what to do.",
            )
            for (row in ui.ranked) LineRow(row) { pick(row.tag) }
        } else {
            Text("‹ All corners", Modifier.clickable { pick(null) }.padding(vertical = 4.dp), color = accent)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    corner.tag,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("${seconds(corner.gainSeconds)} s", color = accent, fontWeight = FontWeight.Bold)
            }
            Dim("to find, against your best lap. Target: ${corner.targetFrom}.")
            for (line in corner.todo) {
                Row {
                    Text("›", color = accent)
                    Spacer(Modifier.width(8.dp))
                    Text(line)
                }
            }
            corner.wheel?.let { WheelChart(it, Modifier.padding(top = 8.dp)) }
        }
        Dim("Lines closer than 1.5 m are treated as the same line: the RaceBox repeats a line to about that.")
    }
}

private fun sessionsText(n: Int) = if (n == 1) "1 session" else "$n sessions"

@Composable
private fun LineRow(row: LineCornerUi, onClick: () -> Unit) {
    val c = TrackTheme.colors
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(LineRowTagPrefix + row.tag)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(row.tag, Modifier.width(56.dp), fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            val shown = if (row.gainSeconds > 0.0) row.todo else emptyList()
            if (shown.isEmpty()) Dim("Nothing to find") else shown.forEach { Text(it) }
        }
        Text(
            seconds(row.gainSeconds),
            color = if (row.gainSeconds > 0.0) accent else c.textDim,
            fontWeight = FontWeight.Bold,
        )
    }
}
