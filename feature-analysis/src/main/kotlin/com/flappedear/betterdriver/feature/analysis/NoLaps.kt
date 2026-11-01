package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The analysis of a session with no laps (a road drive, say). Like the iOS app, it keeps the six tabs
 * of a session with laps: Summary has the message, then the drive's route and speed (FBBD-342), the
 * session's G-forces, its acceleration runs and its data quality (FBBD-183), which need no laps; Car
 * has its pedals, temperatures and heart rate over the whole drive (FBBD-176, UI-162 A). The tabs that
 * need laps say so. A session that could not be read at all, with nothing to show, is just the message
 * and its data quality. [video], when given, is the Video tab (FBBD-392): a commute's video too.
 */
@Suppress("CyclomaticComplexMethod") // One branch per tab.
@Composable
internal fun NoLaps(state: AnalysisState.Unavailable, onAction: () -> Unit, video: (@Composable () -> Unit)? = null) {
    val message = @Composable { modifier: Modifier ->
        Card(modifier) {
            Text(state.message, style = MaterialTheme.typography.bodyLarge)
            state.action?.let { FilledTonalButton(onClick = onAction) { Text(it) } }
        }
    }
    val width = Modifier.widthIn(max = 720.dp).fillMaxWidth()
    if (video == null && listOf(state.drive, state.gForces, state.acceleration, state.car).all { it == null }) {
        // The data quality still shows: it says why the recording has nothing else (FBBD-183).
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            message(width)
            state.quality?.let { QualityCard(it, width) }
        }
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(AnalysisTab.Summary.ordinal) }
    val shown = shownTab(tab, video != null)
    AnalysisTabRow(shown, video != null) { tab = it }
    TabPage(shown.ordinal) {
        when (shown) {
            AnalysisTab.Summary -> {
                message(width)
                state.drive?.let { DriveCards(it, width) }
                state.weather?.let { WeatherCard(it, width) }
                state.gForces?.let { GForcesCard(it, width) }
                state.acceleration?.let { AccelerationCard(it, width) }
                state.quality?.let { QualityCard(it, width) }
            }
            AnalysisTab.Compare -> Card(width) { Dim(NoCompare) }
            AnalysisTab.Map -> Card(width) { Dim(NoLine) }
            AnalysisTab.Laps -> Card(width) { Dim(NoTimedLaps) }
            AnalysisTab.Corners -> Card(width) { Dim(NoCorners) }
            AnalysisTab.Car -> CarCards(state.car, width)
            AnalysisTab.Video -> VideoTab(video, width)
        }
    }
}

// The iOS app's words for the same tabs (SessionView.swift, CompareTab.swift, MapTab.swift, CornersTab.swift).
internal const val NoCompare = "Nothing to compare: it needs a fastest lap and another counted lap."
internal const val NoLine =
    "No line analysis for this session: it needs a lap through the track's corners with GPS all the way round."
internal const val NoTimedLaps = "No timed laps in this session."
internal const val NoCorners = "No counted lap, so no corners."
