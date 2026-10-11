package com.flappedear.betterdriver.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.setup.EquipmentKind
import com.flappedear.betterdriver.setup.MaxTiles
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.TrackChannel
import com.flappedear.betterdriver.setup.channelsFor

private enum class Picker { Car, Gnss, Tiles }

/**
 * "This session" on home (UI-36 option A): the car, the GNSS receiver, a switch per camera and the
 * track-screen tiles (UI-38 option B, per session). The last choice is kept, so on a normal day
 * Arek only presses START. A camera turned off here does not start. While a session starts or
 * records the card is [locked]: it shows what the running session uses and nothing can change.
 */
@Composable
internal fun SessionCard(setup: Setup, actions: SessionCardActions, locked: Boolean = false) {
    val active = setup.active()
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    ShellCard {
        SectionLabel("This session")
        val car = active.car?.name ?: if (setup.cars.isEmpty()) "Add a car" else "None"
        ListRow("Car", car, divider = false, onClick = { picker = Picker.Car }.takeUnless { locked })
        ListRow("GNSS", active.gnss?.name ?: "None", onClick = { picker = Picker.Gnss }.takeUnless { locked })
        setup.ofKind(EquipmentKind.CAMERA).forEach { cam ->
            val on = cam.id in setup.session.cameraIds
            ListRow(cam.name, if (cam.model.controlledByApp) null else "Start by hand") {
                Switch(
                    checked = on,
                    onCheckedChange = { v -> actions.update { it.useCamera(cam.id, v) } },
                    enabled = !locked,
                )
            }
        }
        if (active.gnss != null) {
            val tiles = active.tiles.joinToString(" · ") { it.label }.ifEmpty { "None" }
            ListRow("Tiles", tiles, onClick = { picker = Picker.Tiles }.takeUnless { locked })
        }
        if (locked) DimText("Recording. This session's choice is fixed until it stops.")
    }
    when (picker.takeUnless { locked }) {
        Picker.Car -> ChoiceDialog(
            title = "Car",
            options = setup.cars.map { it.id to it.name },
            selected = active.car?.id,
            addLabel = "Add car",
            onChoose = { id -> actions.update { it.chooseCar(id) } },
            onAdd = actions.onAddCar,
            onClose = { picker = null },
        )
        Picker.Gnss -> ChoiceDialog(
            title = "GNSS receiver",
            options = setup.ofKind(EquipmentKind.GNSS).map { it.id to it.name },
            selected = active.gnss?.id,
            addLabel = "Add receiver",
            onChoose = { id -> actions.update { it.chooseGnss(id) } },
            onAdd = actions.onAddReceiver,
            onClose = { picker = null },
        )
        Picker.Tiles -> TilesDialog(
            chosen = setup.session.tiles,
            available = channelsFor(active.gnss),
            onDone = { tiles -> actions.update { it.chooseTiles(tiles) } },
            onClose = { picker = null },
        )
        null -> Unit
    }
}

/** A radio list with "None" last and an Add button; choosing closes it. */
@Suppress("LongParameterList")
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    addLabel: String,
    onChoose: (String?) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                val all: List<Pair<String?, String>> = options + (null to "None")
                all.forEach { (id, name) ->
                    RadioLine(name, selected == id) {
                        onChoose(id)
                        onClose()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                onAdd()
            }) { Text(addLabel) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun RadioLine(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Up to [MaxTiles] tiles, in the order they were ticked. A tile the receiver cannot fill is shown
 * but cannot be ticked; one ticked before stays saved and comes back with a receiver that fills it.
 */
@Composable
private fun TilesDialog(
    chosen: List<TrackChannel>,
    available: Set<TrackChannel>,
    onDone: (List<TrackChannel>) -> Unit,
    onClose: () -> Unit,
) {
    var picked by remember { mutableStateOf(chosen) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Track screen tiles") },
        text = {
            Column {
                TrackChannel.entries.forEach { ch ->
                    val on = ch in picked
                    val usable = ch in available
                    val full = picked.count { it in available } >= MaxTiles
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = on && usable,
                            enabled = usable && (on || !full),
                            onCheckedChange = { v -> picked = if (v) picked - ch + ch else picked - ch },
                        )
                        Column {
                            Text(ch.label, style = MaterialTheme.typography.bodyLarge)
                            if (!usable) DimText("Needs a RaceBox")
                        }
                    }
                }
                DimText("Up to $MaxTiles, in the order you tick them.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(picked.filter { it in available } + picked.filter { it !in available })
                onClose()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
