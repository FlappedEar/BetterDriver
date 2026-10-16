package com.flappedear.betterdriver.feature.track.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.timing.map.TileSource

/** Test tag of the map background button. */
const val MapPickerTag = "map-picker"

/**
 * The map background button (FBBD-230): shows the background in use and lets Arek pick another one
 * for this place, or enter the MapTiler key that turns on MapTiler's satellite map.
 */
@Composable
internal fun MapPicker(
    sources: List<TileSource>,
    current: TileSource,
    tiles: TileLoader,
    onPick: (TileSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(16.dp),
            color = Color(0xCC000000),
            contentColor = Color(0xFFEEF2F4),
            modifier = Modifier.testTag(MapPickerTag),
        ) {
            Text(
                "Map: ${current.name}",
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in sources) {
                DropdownMenuItem(
                    text = { Text(if (s.id == current.id) "✓ ${s.name}" else s.name) },
                    onClick = {
                        onPick(s)
                        open = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (tiles.mapTilerKey == null) "Add MapTiler key…" else "Change MapTiler key…") },
                onClick = {
                    open = false
                    editingKey = true
                },
            )
        }
    }
    if (editingKey) {
        MapTilerKeyDialog(
            current = tiles.mapTilerKey.orEmpty(),
            onSave = {
                tiles.updateMapTilerKey(it)
                editingKey = false
            },
            onDismiss = { editingKey = false },
        )
    }
}

@Composable
private fun MapTilerKeyDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MapTiler key") },
        text = {
            Column {
                Text(
                    "Turns on MapTiler's satellite map. The key stays on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            if (current.isNotEmpty()) {
                TextButton(onClick = { onSave("") }) { Text("Remove") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
