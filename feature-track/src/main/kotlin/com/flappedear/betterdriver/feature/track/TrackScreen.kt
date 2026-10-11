package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.PlaceholderScreen

/**
 * Placeholder for the always-on track screen. Values are dummies; the layout
 * comes from the approved UI design.
 */
@Composable
fun TrackScreen(onStop: () -> Unit, modifier: Modifier = Modifier) {
    // The track screen stays on for the whole outing.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    PlaceholderScreen(title = "Track", modifier = modifier) {
        Text("Delta ${formatDelta(-420)}", style = MaterialTheme.typography.displayMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Last ${formatLapTime(102_317)}", style = MaterialTheme.typography.titleLarge)
            Text("Best ${formatLapTime(101_894)}", style = MaterialTheme.typography.titleLarge)
        }
        Text("Oil 104 °C   Coolant 92 °C", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStop) { Text("Stop session") }
    }
}

@Preview
@Composable
private fun TrackScreenPreview() {
    BetterDriverTheme(darkTheme = true) { TrackScreen(onStop = {}) }
}
