package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.map.GgSource
import com.flappedear.betterdriver.analysis.map.MapPoint
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.format.isDeltaGain
import com.flappedear.betterdriver.core.ui.layout.HeightClass
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.core.ui.layout.WidthClass
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Speeds on the map are in this unit, as on the Compare tab. */
private const val SpeedUnit = "km/h"

/**
 * The Map tab (FBBD-149): the compared lap's GPS line coloured by speed or by time gained and lost
 * against the best lap, corners tagged as on the Corners tab, and touching the line reads speed and
 * delta there; beside it (below on a phone) the G-G diagram of the lap over the best lap's. The two sit
 * side by side when [layout] has room for it: tablets, and large phones held sideways.
 */
@Composable
internal fun MapTab(ui: MapUi, layout: LayoutSize, modifier: Modifier) {
    var coloring by rememberSaveable(ui.lapNumber) {
        mutableStateOf(if (ui.referenceNumber != null) MapColoring.Delta else MapColoring.Speed)
    }
    var cursorMeters by rememberSaveable(ui.lapNumber) { mutableStateOf<Double?>(null) }
    val cursor = remember(ui.map, cursorMeters) {
        cursorMeters?.let { d -> ui.map.runs.flatten().minByOrNull { abs(it.distanceMeters - d) } }
    }
    val scale = remember(ui.map, coloring) { mapScale(ui.map, coloring) }
    val onCursor: (MapPoint) -> Unit = { cursorMeters = it.distanceMeters }
    Box(modifier) {
        if (layout.sideBySide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MapCard(ui, scale, cursor, onCursor, { coloring = it }, Modifier.weight(3f))
                GgCard(ui, cursor, Modifier.weight(2f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MapCard(ui, scale, cursor, onCursor, { coloring = it }, Modifier)
                GgCard(ui, cursor, Modifier)
            }
        }
    }
}

@Composable
private fun MapCard(
    ui: MapUi,
    scale: MapScale,
    cursor: MapPoint?,
    onCursor: (MapPoint) -> Unit,
    onColoring: (MapColoring) -> Unit,
    modifier: Modifier,
) {
    Card(modifier) {
        LapTitle(ui)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in MapColoring.entries) {
                FilterChip(
                    selected = scale.coloring == option,
                    onClick = { onColoring(option) },
                    enabled = option == MapColoring.Speed || ui.referenceNumber != null,
                    label = { Text(option.title) },
                )
            }
        }
        MapReadout(ui, cursor)
        val aspect = remember(ui.map) { mapAspect(ui.map) }
        LapMapView(ui.map, scale, cursor, onCursor, Modifier.fillMaxWidth().aspectRatio(aspect))
        MapLegend(scale, SpeedUnit, Modifier.fillMaxWidth())
        if (ui.referenceNumber == null) {
            Dim("This is the best lap, so there is no time to compare. Pick another lap on Laps.")
        } else if (scale.coloring == MapColoring.Delta) {
            Dim("Time vs best: where this lap gains or loses time against the best lap, per 100 m.")
        }
    }
}

@Composable
private fun LapTitle(ui: MapUi) {
    val c = TrackTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Lap ${ui.lapNumber}",
            color = if (ui.referenceNumber == null) c.best else c.cold,
            fontWeight = FontWeight.SemiBold,
        )
        if (ui.referenceNumber != null) {
            Dim(" vs best lap ")
            Text("${ui.referenceNumber}", color = c.best, fontWeight = FontWeight.SemiBold)
        } else {
            Dim(" (best lap)")
        }
    }
}

/** What the map cursor reads: distance, corner, speed, delta and the rate of gain or loss there. */
@Composable
private fun MapReadout(ui: MapUi, cursor: MapPoint?) {
    val c = TrackTheme.colors
    if (cursor == null) {
        Dim("Touch or drag along the line to read a point. The ringed dot is the start/finish line.")
        return
    }
    val corner = ui.map.corners.minByOrNull { abs(it.distanceMeters - cursor.distanceMeters) }
        ?.takeIf { abs(it.distanceMeters - cursor.distanceMeters) <= CornerReadoutMeters }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Dim("${cursor.distanceMeters.roundToInt()} m" + (corner?.let { " · ${cornerTag(it.name)}" } ?: ""))
        Text(cursor.speed?.let { "${it.roundToInt()} $SpeedUnit" } ?: "–", fontWeight = FontWeight.SemiBold)
        cursor.deltaSeconds?.let { delta ->
            Text(
                formatDelta(delta),
                color = if (isDeltaGain(delta)) c.gain else c.loss,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    cursor.deltaRate?.let { rate ->
        val amount = String.format(Locale.ROOT, "%.2f s per 100 m", abs(rate))
        Dim(if (rate < 0) "Gaining $amount here" else "Losing $amount here", color = if (rate < 0) c.gain else c.loss)
    }
}

/** A corner is named in the readout within this distance of its middle. */
private const val CornerReadoutMeters = 40.0

@Composable
private fun GgCard(ui: MapUi, cursor: MapPoint?, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        TrackLabel("G-G · traction circle")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(c.cold.takeIf { ui.referenceNumber != null } ?: c.best, "Lap ${ui.lapNumber}")
            ui.referenceNumber?.let { Key(c.best, "Best lap $it") }
        }
        val gg = ui.gg
        if (gg == null || gg.points.isEmpty()) {
            Dim("No acceleration for this lap: it has too little GPS.")
            return@Card
        }
        val at = cursor?.let { ggPointAt(gg, it.distanceMeters) }
        GgChart(
            trace = gg,
            reference = ui.referenceGg,
            traceColor = if (ui.referenceNumber != null) c.cold else c.best,
            referenceColor = c.best,
            cursor = at,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        val peaks = remember(gg) { ggPeaks(gg) }
        Dim(
            String.format(
                Locale.ROOT,
                "Peaks: braking %.2f g · cornering %.2f g · accelerating %.2f g",
                peaks.braking, peaks.cornering, peaks.accelerating,
            ),
        )
        Dim(ggSourceNote(gg.source))
    }
}

/** Where a G-G diagram's readings come from, and how to read it. */
internal fun ggSourceNote(source: GgSource) = when (source) {
    GgSource.Accelerometer -> "From the RaceBox's accelerometer, or the phone's when the RaceBox's did not match " +
        "the GPS, its mounting and tilt corrected against GPS. Left turns on the left; rings every 0.5 g."
    GgSource.Gps -> "Worked out from GPS speed and heading, smoothed: neither the RaceBox's nor the phone's " +
        "accelerometer matched the GPS. Left turns on the left; rings every 0.5 g."
}

@Composable
private fun Key(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Dim(label)
    }
}

@Preview(name = "Phone", widthDp = 400, heightDp = 1300)
@Composable
private fun MapTabPhonePreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            MapTab(PreviewMap, LayoutSize.Phone, Modifier.fillMaxWidth())
        }
    }
}

@Preview(name = "Tablet", widthDp = 1000, heightDp = 800)
@Composable
private fun MapTabTabletPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            MapTab(PreviewMap, LayoutSize(WidthClass.Expanded, HeightClass.Medium), Modifier.fillMaxWidth())
        }
    }
}
