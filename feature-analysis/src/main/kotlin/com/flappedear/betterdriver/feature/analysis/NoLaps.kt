package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The analysis of a session with no laps (a road drive, say). Like the iOS app, it keeps the six tabs
 * of a session with laps: Summary has the message, then the drive's route and speed (FBBD-342), the
 * session's G-forces, its acceleration runs and its data quality (FBBD-183), which need no laps; Car
 * has its pedals, temperatures and heart rate over the whole drive (FBBD-176, UI-162 A). The tabs that
 * need laps say so. A session that could not be read at all, with nothing to show, is just the message.
 */
@Suppress("CyclomaticComplexMethod") // One branch per tab.
@Composable
internal fun NoLaps(state: AnalysisState.Unavailable, onAction: () -> Unit) {
    val message = @Composable { modifier: Modifier ->
        Card(modifier) {
            Text(state.message, style = MaterialTheme.typography.bodyLarge)
            state.action?.let { FilledTonalButton(onClick = onAction) { Text(it) } }
        }
    }
    val width = Modifier.widthIn(max = 720.dp).fillMaxWidth()
    if (state.drive == null && state.gForces == null && state.acceleration == null && state.car == null) {
        message(width.padding(16.dp))
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(AnalysisTab.Summary.ordinal) }
    AnalysisTabRow(tab) { tab = it }
    TabPage(tab) {
        when (AnalysisTab.entries[tab]) {
            AnalysisTab.Summary -> {
                message(width)
                state.drive?.let { DriveCards(it, width) }
                state.gForces?.let { GForcesCard(it, width) }
                state.acceleration?.let { AccelerationCard(it, width) }
                state.quality?.let { QualityCard(it, width) }
            }
            AnalysisTab.Compare -> Card(width) { Dim(NoCompare) }
            AnalysisTab.Map -> Card(width) { Dim(NoLine) }
            AnalysisTab.Laps -> Card(width) { Dim(NoTimedLaps) }
            AnalysisTab.Corners -> Card(width) { Dim(NoCorners) }
            AnalysisTab.Car -> CarCards(state.car, width)
        }
    }
}

// The iOS app's words for the same tabs (SessionView.swift, CompareTab.swift, MapTab.swift, CornersTab.swift).
internal const val NoCompare = "Nothing to compare: it needs a fastest lap and another counted lap."
internal const val NoLine =
    "No line analysis for this session: it needs a lap through the track's corners with GPS all the way round."
internal const val NoTimedLaps = "No timed laps in this session."
internal const val NoCorners = "No counted lap, so no corners."
