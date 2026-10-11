package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentKind
import com.flappedear.betterdriver.setup.EquipmentModel

/** "GNSS" or "Camera", the value next to an item in the equipment list. */
internal fun kindLabel(e: Equipment): String = when (e.kind) {
    EquipmentKind.GNSS -> "GNSS"
    EquipmentKind.CAMERA -> "Camera"
}

/** What a model does, under its details. */
private fun modelNote(model: EquipmentModel): String = when (model) {
    EquipmentModel.RACEBOX_MICRO -> "Records position, speed and G for each session it is chosen for."
    EquipmentModel.DEMO_GNSS ->
        "Made-up positions on a circle, for trying the app without a receiver. Sessions with it are not real data."
    EquipmentModel.PHONE_CAMERA -> "Records video with each session it is turned on for."
    EquipmentModel.GOPRO_HERO11 -> "Starts and stops with each session it is turned on for."
    EquipmentModel.GOPRO_HERO8 -> "Start it by hand. Add its videos to the session afterwards."
}

/**
 * Settings › Devices (UI-33 option B): everything Arek added, in one list with one Add. Nothing
 * else is listed; a fresh install has only the phone camera.
 */
@Composable
internal fun EquipmentList(equipment: List<Equipment>, onOpen: (Equipment) -> Unit, onAdd: () -> Unit) {
    ShellCard {
        equipment.forEachIndexed { i, e -> ListRow(e.name, kindLabel(e), divider = i > 0, onClick = { onOpen(e) }) }
        AddRow("Add equipment", onAdd, divider = equipment.isNotEmpty())
    }
    DimText("Tap an item to rename or remove it.")
}

/** One item: its name (renamed in a dialog), model and Bluetooth address, and Remove (UI-33). */
@Composable
fun EquipmentScreen(item: Equipment, onRename: (String) -> Unit, onRemove: () -> Unit, onBack: () -> Unit) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(item.name, onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShellCard {
                ListRow("Name", item.name, divider = false, onClick = { renaming = true })
                ListRow("Model", item.model.label)
                item.address?.let { ListRow("Bluetooth", "Paired · $it") }
            }
            DimText(modelNote(item.model))
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text("Remove", color = TrackTheme.colors.loss)
            }
        }
    }
    if (renaming) {
        NameDialog(
            title = "Rename",
            initial = item.name,
            onDone = { name ->
                renaming = false
                onRename(name)
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Add equipment (UI-34 option B): a scan first, listing the RaceBoxes and GoPro HERO11s heard
 * nearby, then the models that need no Bluetooth. [nearby] leaves out what is already listed.
 * [searching] is false when the Bluetooth permissions are missing. [busyAddress] is the GoPro
 * being paired, which takes a moment; [error] says why pairing failed.
 */
@Suppress("LongParameterList") // One callback per kind of item.
@Composable
fun AddEquipmentScreen(
    nearby: List<NearbyDevice>,
    searching: Boolean,
    withoutBluetooth: List<EquipmentModel>,
    busyAddress: String?,
    error: String?,
    onAddNearby: (NearbyDevice) -> Unit,
    onAddModel: (EquipmentModel) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("Add equipment", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShellCard {
                SectionLabel("Found nearby")
                if (nearby.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        val text = if (searching) "Searching…" else "Not searching"
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                nearby.forEachIndexed { i, d -> NearbyRow(d, i > 0, busyAddress, onAddNearby) }
            }
            DimText(
                if (searching) {
                    "Searching for RaceBox and GoPro HERO11. Put a GoPro in pairing mode first."
                } else {
                    "Allow nearby devices (Settings › App › Run setup again) to search for a RaceBox or GoPro."
                },
            )
            if (error != null) DimText(error, color = MaterialTheme.colorScheme.error)
            if (withoutBluetooth.isNotEmpty()) {
                SectionLabel("Add without Bluetooth")
                ShellCard {
                    withoutBluetooth.forEachIndexed { i, m ->
                        ListRow(m.label, withoutBluetoothNote(m), divider = i > 0, onClick = { onAddModel(m) })
                    }
                }
            }
        }
    }
}

private fun withoutBluetoothNote(model: EquipmentModel): String = when (model) {
    EquipmentModel.GOPRO_HERO8 -> "Started by hand"
    EquipmentModel.DEMO_GNSS -> "For trying the app"
    EquipmentModel.PHONE_CAMERA -> "Built in"
    else -> ""
}

@Composable
private fun NearbyRow(d: NearbyDevice, divider: Boolean, busyAddress: String?, onAdd: (NearbyDevice) -> Unit) {
    ListRow(d.name, divider = divider) {
        SignalBars(d.bars)
        FilledTonalButton(onClick = { onAdd(d) }, enabled = busyAddress == null) {
            Text(if (busyAddress == d.address) "Pairing…" else "Add")
        }
    }
}

@Composable
internal fun SignalBars(bars: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(5, 8, 11, 14).forEachIndexed { i, h ->
            val color = if (i < bars) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            Box(Modifier.width(3.dp).height(h.dp).background(color, RoundedCornerShape(1.dp)))
        }
    }
}

@Preview(widthDp = 411, heightDp = 700)
@Composable
private fun AddEquipmentPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            AddEquipmentScreen(
                nearby = listOf(
                    NearbyDevice(EquipmentModel.RACEBOX_MICRO, "RaceBox Micro 3241", "AA", 4),
                    NearbyDevice(EquipmentModel.GOPRO_HERO11, "GoPro 1234", "BB", 3),
                ),
                searching = true,
                withoutBluetooth = listOf(EquipmentModel.GOPRO_HERO8, EquipmentModel.DEMO_GNSS),
                busyAddress = null,
                error = null,
                onAddNearby = {},
                onAddModel = {},
                onBack = {},
            )
        }
    }
}
