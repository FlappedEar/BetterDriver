package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import java.util.Locale

/**
 * G-forces of the whole session: its G-G diagram while the car moved and the peaks. Needs no laps,
 * so a road drive shows it too; the Map tab has each lap's.
 */
@Composable
internal fun GForcesCard(trace: GgTrace, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    Card(modifier) {
        TrackLabel("G-forces · whole session")
        GgChart(
            trace = trace,
            reference = null,
            traceColor = c.best,
            referenceColor = c.best,
            cursor = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        val peaks = remember(trace) { ggPeaks(trace) }
        Dim(
            String.format(
                Locale.ROOT,
                "Peaks: braking %.2f g · cornering %.2f g · accelerating %.2f g",
                peaks.braking, peaks.cornering, peaks.accelerating,
            ),
        )
        Dim(ggSourceNote(trace.source))
    }
}
