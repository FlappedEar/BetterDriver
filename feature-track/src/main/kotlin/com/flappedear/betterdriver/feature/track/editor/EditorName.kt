package com.flappedear.betterdriver.feature.track.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.timing.track.Track

/**
 * Above the lines: what a track proposed from a session (FBBD-151) still needs, and the track's
 * name, which a tap renames. A proposed track at a place the circuit list does not know is named
 * after the session, so this is where it gets a real name before it is saved.
 */
@Composable
internal fun TrackHeader(state: TrackEditorState) {
    if (state.proposed) {
        DimLine(
            "Made from your session: the start/finish line is across the middle of the longest straight. " +
                "Check it on the map, rename the track if you like, then Save.",
            Modifier.padding(vertical = 8.dp),
        )
    }
    var naming by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { naming = true }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Name", style = MaterialTheme.typography.bodyLarge)
        Text(
            state.track.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("Rename", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    if (naming) {
        RenameDialog(
            current = state.track.name,
            onDone = { name ->
                state.rename(name)
                naming = false
            },
            onCancel = { naming = false },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    val valid = name.isNotBlank() && name.trim().length <= Track.MaxNameChars
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Track name") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = valid) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
