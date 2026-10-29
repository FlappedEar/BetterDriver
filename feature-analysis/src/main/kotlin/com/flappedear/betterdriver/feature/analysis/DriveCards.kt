package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.map.DriveMap
import com.flappedear.betterdriver.analysis.map.LapMap
import com.flappedear.betterdriver.analysis.map.MapPoint
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.NumberText
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Test tag of the drive's map card on a session with no laps. */
const val DriveMapTag = "drive-map"

/**
 * A session with no laps (FBBD-342), a road drive for example: its figures and route on the map,
 * coloured by speed as the Map tab colours a lap, then its speed along the drive as the Compare tab
 * draws it. Touching the map or the chart reads the same point on both.
 */
@Composable
internal fun DriveCards(drive: DriveMap, modifier: Modifier) {
    val c = TrackTheme.colors
    var cursorMeters by rememberSaveable(drive.distanceMeters) { mutableStateOf<Double?>(null) }
    val cursor = remember(drive.map, cursorMeters) {
        cursorMeters?.let { d -> drive.map.runs.flatten().minByOrNull { abs(it.distanceMeters - d) } }
    }
    val scale = remember(drive.map) { mapScale(drive.map, MapColoring.Speed) }
    val figures = remember(drive) { driveFigures(drive) }
    Card(modifier.testTag(DriveMapTag)) {
        TrackLabel("Drive · whole session")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DriveFigure("Distance", figures.distance, Modifier.weight(1f))
            DriveFigure("Time", figures.time, Modifier.weight(1f))
            DriveFigure("Average", figures.averageSpeed, Modifier.weight(1f))
            DriveFigure("Top", figures.topSpeed, Modifier.weight(1f))
        }
        DriveReadout(cursor)
        val aspect = remember(drive.map) { mapAspect(drive.map) }
        LapMapView(
            drive.map,
            scale,
            cursor,
            { cursorMeters = it.distanceMeters },
            Modifier.fillMaxWidth().aspectRatio(aspect),
        )
        MapLegend(scale, "km/h", Modifier.fillMaxWidth())
        Dim("Average while moving. No laps, so the route is the whole session; GPS gaps leave it open.")
    }
    if (drive.speed.isNotEmpty() && drive.distanceMeters > 0.0) {
        Card(modifier) {
            TrackLabel("Speed, km/h")
            LapChart(
                lines = listOf(ChartLine(drive.speed, c.best)),
                lengthMeters = drive.distanceMeters,
                corners = emptyList(),
                cornerColor = MaterialTheme.colorScheme.surfaceVariant,
                gridColor = MaterialTheme.colorScheme.outline,
                cursorColor = c.text,
                cursorMeters = cursorMeters,
                onCursor = { cursorMeters = it },
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            Row {
                Dim("0", Modifier.weight(1f))
                Dim(distanceText(drive.distanceMeters))
            }
        }
    }
}

@Composable
private fun DriveFigure(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        TrackLabel(label)
        NumberText(value, size = 18.dp, color = TrackTheme.colors.text, weight = FontWeight.SemiBold)
    }
}

/** What the cursor reads: how far into the drive, and the speed there. */
@Composable
private fun DriveReadout(cursor: MapPoint?) {
    if (cursor == null) {
        Dim("Touch or drag along the line or the chart to read a point.")
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Dim(distanceText(cursor.distanceMeters))
        Text(cursor.speed?.let { "${it.roundToInt()} km/h" } ?: "–", fontWeight = FontWeight.SemiBold)
    }
}

/** A loop and a half of road at changing speed, for the previews. */
@Suppress("MagicNumber")
internal val PreviewDrive: DriveMap = run {
    val points = (0..300).map { i ->
        val a = i / 200.0 * 2 * PI
        val speed = 50 + 40 * sin(a * 3)
        MapPoint(800 * cos(a) + i, 500 * sin(a), i * 25.0, speed, null, null)
    }
    DriveMap(
        LapMap(-1, null, 7500.0, listOf(points), emptyList(), emptyList(), closed = false),
        listOf(points.map { ChartPoint(it.distanceMeters, it.speed ?: 0.0) }),
        distanceMeters = 7500.0,
        durationSeconds = 640.0,
        movingSeconds = 600.0,
        averageMovingKmh = 45.0,
        topSpeedKmh = 90.0,
    )
}

@Preview(widthDp = 400, heightDp = 900)
@Composable
private fun DriveCardsPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DriveCards(PreviewDrive, Modifier.fillMaxWidth())
            }
        }
    }
}
