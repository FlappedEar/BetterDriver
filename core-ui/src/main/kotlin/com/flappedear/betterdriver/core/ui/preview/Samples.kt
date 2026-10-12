package com.flappedear.betterdriver.core.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.AlertBanner
import com.flappedear.betterdriver.core.ui.component.AlertLevel
import com.flappedear.betterdriver.core.ui.component.DeltaDisplay
import com.flappedear.betterdriver.core.ui.component.SectorState
import com.flappedear.betterdriver.core.ui.component.SectorStrip
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.component.ValueState
import com.flappedear.betterdriver.core.ui.component.ValueTile

// Samples of the theme and the shared components (FBBD-38, FBBD-39), shown by
// Previews.kt and drawn by the screenshot tests, so both show the same thing.
// Example values only, not real data.

/** Material colours of the current scheme: what settings, sessions and review use. */
@Composable
internal fun ThemeSample() {
    val s = MaterialTheme.colorScheme
    Column(
        Modifier.width(360.dp).background(s.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("BetterDriver", style = MaterialTheme.typography.headlineSmall, color = s.onBackground)
        Text("Body text on the background", style = MaterialTheme.typography.bodyMedium, color = s.onBackground)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Session card", style = MaterialTheme.typography.titleMedium, color = s.onSurface)
                Text("Secondary text", style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
                Text("1:52.41", style = MaterialTheme.typography.displaySmall, color = s.onSurface)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text("Start") }
            OutlinedButton(onClick = {}) { Text("Settings") }
        }
    }
}

/** Every track colour token with the text drawn on it. */
@Composable
internal fun TrackColorsSample() {
    val c = TrackTheme.colors
    Column(
        Modifier.width(360.dp).background(c.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch("Gain", c.gain, c.onGain)
            Swatch("Loss", c.loss, c.onLoss)
            Swatch("Warn", c.warning, c.onWarning)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch("Text", c.surface, c.text)
            Swatch("Dim", c.surface, c.textDim)
            Swatch("Cold", c.surface, c.cold)
            Swatch("Best", c.surface, c.best)
        }
    }
}

@Composable
private fun Swatch(name: String, background: Color, text: Color) {
    Box(
        Modifier.size(width = 76.dp, height = 40.dp).background(background, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        TrackLabel(name, size = 13.dp, color = text)
    }
}

/** The four value states side by side, at the size used on the track screen. */
@Composable
internal fun ValueTileSample() {
    Row(
        Modifier.background(TrackTheme.colors.background).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tile = Modifier.size(width = 112.dp, height = 80.dp)
        ValueTile("Speed", "142", "km/h", tile)
        ValueTile("Tyre", "41", "°C", tile, ValueState.Cold)
        ValueTile("Oil", "124", "°C", tile, ValueState.Warning)
        ValueTile("Water", "108", "°C", tile, ValueState.Critical)
    }
}

/** A warning and a critical alert in the status strip's 30 dp slot. */
@Composable
internal fun AlertBannerSample() {
    Column(
        Modifier.width(600.dp).background(TrackTheme.colors.background).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val slot = Modifier.fillMaxWidth().height(30.dp)
        AlertBanner("Phone hot", "Video quality lowered · data still recording", AlertLevel.Warning, "18:42", slot)
        AlertBanner("GNSS signal lost", "Lap timing paused · video still recording", AlertLevel.Critical, "18:42", slot)
    }
}

/** Gain, loss, the current sector and one not reached yet; then option B without times. */
@Composable
internal fun SectorStripSample() {
    val sectors = listOf(
        SectorState("S1", -0.21),
        SectorState("S2", 0.08),
        SectorState("S3", null, current = true),
        SectorState("S4", null),
    )
    Column(
        Modifier.width(560.dp).background(TrackTheme.colors.background).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectorStrip(sectors, Modifier.fillMaxWidth().height(80.dp))
        SectorStrip(sectors, Modifier.fillMaxWidth().height(40.dp), showTimes = false)
    }
}

/** Ahead, behind, and no reference lap yet. */
@Composable
internal fun DeltaDisplaySample() {
    Column(
        Modifier.background(TrackTheme.colors.background).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tile = Modifier.size(width = 420.dp, height = 170.dp)
        DeltaDisplay(-0.34, tile, numberSize = 100.dp)
        DeltaDisplay(1.27, tile, numberSize = 100.dp)
        DeltaDisplay(null, tile, numberSize = 100.dp)
    }
}
