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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

/** Test tag of the map background button. */
const val MapPickerTag = "map-picker"

/** How often a map on screen checks its Google session (made, or renewed before it expires). */
private const val SessionCheckMs = 30_000L

/**
 * Keeps [tiles]' Google session made while this is on screen and a Google key is set (FBBD-412):
 * at once, after a lost signal, and before the two-week session expires.
 */
@Composable
fun KeepGoogleSession(tiles: TileLoader) {
    val key = tiles.googleKey
    LaunchedEffect(key) {
        while (key != null) {
            tiles.ensureGoogle()
            delay(SessionCheckMs)
        }
    }
}

/** Which key the key dialog edits. */
private enum class KeyKind { Google, MapTiler }

/**
 * The map background button (FBBD-230): shows the background in use and lets Arek pick another one
 * for this place, or enter the Google key (FBBD-412) or MapTiler key that turns on that satellite map.
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
    var editingKey by remember { mutableStateOf<KeyKind?>(null) }
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
                text = { Text(if (tiles.googleKey == null) "Add Google key…" else "Change Google key…") },
                onClick = {
                    open = false
                    editingKey = KeyKind.Google
                },
            )
            DropdownMenuItem(
                text = { Text(if (tiles.mapTilerKey == null) "Add MapTiler key…" else "Change MapTiler key…") },
                onClick = {
                    open = false
                    editingKey = KeyKind.MapTiler
                },
            )
        }
    }
    editingKey?.let { kind -> KeyDialogFor(kind, tiles) { editingKey = null } }
}

/** The dialog that enters, changes or removes the [kind] key; [onDone] closes it. */
@Composable
private fun KeyDialogFor(kind: KeyKind, tiles: TileLoader, onDone: () -> Unit) {
    when (kind) {
        KeyKind.Google -> KeyDialog(
            title = "Google key",
            about = googleKeyNote(tiles.headers),
            current = tiles.googleKey.orEmpty(),
            onSave = {
                tiles.updateGoogleKey(it)
                onDone()
            },
            onDismiss = onDone,
        )
        KeyKind.MapTiler -> KeyDialog(
            title = "MapTiler key",
            about = "Turns on MapTiler's satellite map. The key stays on this device.",
            current = tiles.mapTilerKey.orEmpty(),
            onSave = {
                tiles.updateMapTilerKey(it)
                onDone()
            },
            onDismiss = onDone,
        )
    }
}

/**
 * What the Google key dialog says: the key turns on Google's satellite map, and the app's package
 * and certificate a key limited to Android apps needs (from the request [headers]).
 */
internal fun googleKeyNote(headers: Map<String, String>): String {
    val pkg = headers[AndroidPackageHeader]
    val cert = headers[AndroidCertHeader]?.chunked(2)?.joinToString(":")
    val limit = if (pkg != null && cert != null) {
        " Limit the key to the Map Tiles API and to Android apps: package $pkg, SHA-1 $cert."
    } else {
        ""
    }
    return "Turns on Google's satellite map (a Map Tiles API key). The key stays on this device.$limit"
}

/** The headers a Google key limited to Android apps checks: the app's package and its signing certificate's SHA-1. */
const val AndroidPackageHeader = "X-Android-Package"
const val AndroidCertHeader = "X-Android-Cert"

@Composable
private fun KeyDialog(
    title: String,
    about: String,
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(about, style = MaterialTheme.typography.bodyMedium)
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
