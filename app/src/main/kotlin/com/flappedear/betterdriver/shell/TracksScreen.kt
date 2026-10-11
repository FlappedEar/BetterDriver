package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.timing.circuits.Circuit
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.tracks.Here
import java.util.Locale

private const val MetersPerKm = 1_000.0
private const val HereMeters = 100.0

/**
 * Tracks (UI-27 option A): where you are first, from the saved tracks and the circuit list, with
 * one tap to create a track there; any other place gets a track at your position with a name you
 * type. Your tracks are listed below; each opens the editor.
 *
 * Share moves a track to the other device as one file (FBBD-80, UI-55): Send through the share
 * sheet (Quick Share) or Save file; Import at the top brings one in, asking before it replaces a
 * track with other lines.
 */
@Composable
fun TracksScreen(
    here: Here?,
    tracks: List<Track>,
    onOpen: (Track) -> Unit,
    onCreateFromCircuit: (Circuit) -> Unit,
    onCreateHere: (name: String, at: GeoPoint) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    transfer: TrackTransferState = TrackTransferState(),
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        ShellTopBar("Tracks", onBack = onBack) {
            TextButton(onClick = transfer.onImport, enabled = transfer.importEnabled) { Text("Import") }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ImportCard(transfer.incoming)
            WhereYouAre(here, onOpen, onCreateFromCircuit)
            if (here != null && here.detection !is TrackDetection.Saved) {
                Text(
                    "Not on the list? New track here uses your position and a name you type.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { naming = true }.padding(vertical = 4.dp),
                )
            }
            SectionLabel("My tracks")
            ShellCard {
                if (tracks.isEmpty()) DimText("No tracks yet")
                tracks.forEachIndexed { i, t ->
                    val sending = transfer.outgoing[t.id]
                    ListRow(t.displayName, trackDetail(t), divider = i > 0, onClick = { onOpen(t) }) {
                        ShareButton(
                            enabled = sending?.running != true,
                            onSend = { transfer.onSend(t) },
                            onSave = { transfer.onSave(t) },
                        )
                    }
                    TransferLine(sending)
                }
            }
        }
    }
    if (naming && here != null) {
        NameDialog(
            onDone = { name ->
                naming = false
                onCreateHere(name, here.position)
            },
            onCancel = { naming = false },
        )
    }
}

@Composable
private fun WhereYouAre(here: Here?, onOpen: (Track) -> Unit, onCreateFromCircuit: (Circuit) -> Unit) {
    ShellCard {
        SectionLabel("Where you are")
        when (val d = here?.detection) {
            null -> DimText("Finding where you are…")
            is TrackDetection.Saved -> {
                BigName(d.track.displayName)
                DimText("Your track · ${away(d.distanceMeters)} · ${trackDetail(d.track)}")
                FilledTonalButton(onClick = { onOpen(d.track) }) { Text("Edit lines") }
            }
            is TrackDetection.Directory -> {
                BigName(d.circuit.name)
                DimText("From the circuit list · ${away(d.distanceMeters)} · no lines yet")
                FilledTonalButton(onClick = { onCreateFromCircuit(d.circuit) }) { Text("Create track") }
            }
            TrackDetection.Unknown -> DimText("No circuit on the list is near you.")
        }
    }
}

@Composable
private fun BigName(name: String) {
    Text(name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
}

@Composable
private fun NameDialog(onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val valid = name.isNotBlank() && name.length <= Track.MaxNameChars
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("New track here") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = valid) { Text("Create") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private fun trackDetail(t: Track): String = if (t.timeable) {
    if (t.sectorCount == 1) "1 sector" else "${t.sectorCount} sectors"
} else {
    "No lines yet"
}

private fun away(meters: Double): String =
    if (meters < HereMeters) "here" else String.format(Locale.ROOT, "%.1f km away", meters / MetersPerKm)

@Preview(widthDp = 411, heightDp = 700)
@Composable
private fun TracksScreenPreview() {
    val kielce = Circuit("Q1", "Tor Kielce", "PL", GeoPoint(50.8136, 20.5378))
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            TracksScreen(
                here = Here(GeoPoint(50.81, 20.54), TrackDetection.Directory(kielce, 400.0)),
                tracks = listOf(
                    Track("a", "Tor Poznań", GeoPoint(52.42, 16.81)),
                    Track("b", "Autodrom Jastrząb", GeoPoint(51.3, 20.9)),
                ),
                onOpen = {},
                onCreateFromCircuit = {},
                onCreateHere = { _, _ -> },
                onBack = {},
            )
        }
    }
}
