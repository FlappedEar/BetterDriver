package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.setup.MaxTiles
import com.flappedear.betterdriver.setup.TrackChannel

/** The tiles as Settings shows them, for example "Speed · G · Lap". */
internal fun tilesLabel(tiles: List<TrackChannel>): String = tiles.joinToString(" · ") { it.label }.ifEmpty { "None" }

/**
 * Settings › Recording › Track screen tiles (UI-38 option A): up to [MaxTiles], in the order they
 * were ticked, set once for every session. A tile the session's receiver cannot fill (G without a
 * RaceBox) is left off the track screen for that session and comes back with a receiver that fills it.
 */
@Composable
internal fun TilesDialog(chosen: List<TrackChannel>, onDone: (List<TrackChannel>) -> Unit, onClose: () -> Unit) {
    var picked by remember { mutableStateOf(chosen) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Track screen tiles") },
        text = {
            Column {
                TrackChannel.entries.forEach { ch ->
                    val on = ch in picked
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = on,
                            enabled = on || picked.size < MaxTiles,
                            onCheckedChange = { v -> picked = if (v) picked - ch + ch else picked - ch },
                        )
                        Column {
                            Text(ch.label, style = MaterialTheme.typography.bodyLarge)
                            if (ch == TrackChannel.G) DimText("Shown in sessions with a RaceBox")
                        }
                    }
                }
                DimText("Up to $MaxTiles, in the order you tick them.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(picked)
                onClose()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
