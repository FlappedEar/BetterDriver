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
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.VideoMode

/** "GNSS" or "Camera", the value next to an item in the equipment list. */
internal fun kindLabel(e: Equipment): String = when (e.kind) {
    EquipmentKind.GNSS -> "GNSS"
    EquipmentKind.CAMERA -> "Camera"
    EquipmentKind.SENSOR -> "Sensor"
}

/** What a model does, under its details. */
private fun modelNote(model: EquipmentModel): String = when (model) {
    EquipmentModel.RACEBOX -> "Records position, speed and G for each session it is chosen for."
    EquipmentModel.NMEA_GNSS, EquipmentModel.LNS_GNSS, EquipmentModel.DIY_GNSS ->
        "Records position and speed for each session it is chosen for."
    EquipmentModel.VBOX_SPORT, EquipmentModel.DRAGY ->
        "Records position and speed for each session it is chosen for. Not yet tested with a real ${model.label}."
    EquipmentModel.TCP_GNSS ->
        "Connects over Wi-Fi to the address below. Records position and speed, and G from a RaceChrono DIY device."
    EquipmentModel.HEART_RATE -> "Records your heart rate with every session while it is listed."
    EquipmentModel.DEMO_GNSS ->
        "Made-up positions on a circle, for trying the app without a receiver. Sessions with it are not real data."
    EquipmentModel.DEMO_TRACK ->
        "A made-up car lapping the Demo circuit: laps, sectors and coach cues without a track day. " +
            "Its positions are not real data."
    EquipmentModel.PHONE_CAMERA -> "Records video with each session it is turned on for."
    EquipmentModel.GOPRO -> "Starts and stops with each session it is turned on for."
    EquipmentModel.GOPRO_FILES -> "Start it by hand. Add its videos to the session afterwards."
    EquipmentModel.CAMERA_FILES ->
        "Start it by hand. Add its MP4 videos to the session afterwards; they are lined up by the camera's clock."
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

/**
 * One item: its name (renamed in a dialog), model and Bluetooth address, the video mode of a
 * camera the app sets (UI-39), and Remove (UI-33). A paired RaceBox also has Diagnostics (FBBD-89).
 * A GoPro's model can be changed (FBBD-105): a paired one reports it, one started by hand is
 * whatever generation Arek picks.
 */
@Suppress("LongParameterList") // One callback per thing Arek can change.
@Composable
fun EquipmentScreen(
    item: Equipment,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
    onChooseVideo: (VideoMode) -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onChooseGoPro: (GoProModel) -> Unit = {},
    drives: DemoDrives? = null,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var choosingDrive by rememberSaveable { mutableStateOf(false) }
    var choosingVideo by rememberSaveable { mutableStateOf(false) }
    var choosingGoPro by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(item.name, onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShellCard {
                ListRow("Name", item.name, divider = false, onClick = { renaming = true })
                val pickModel: (() -> Unit)? = if (item.model.isGoPro) ({ choosingGoPro = true }) else null
                ListRow("Model", item.modelLabel, onClick = pickModel)
                item.address?.let { ListRow("Bluetooth", "Paired · $it") }
                if (item.videoMode != null) ListRow("Video", videoLabel(item), onClick = { choosingVideo = true })
                if (item.model == EquipmentModel.RACEBOX && item.address != null) {
                    ListRow("Diagnostics", "Live", onClick = onDiagnostics)
                }
                drives?.let { ListRow("Drives", it.current.label, onClick = { choosingDrive = true }) }
            }
            DimText(modelNote(item.model))
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text("Remove", color = TrackTheme.colors.loss)
            }
        }
    }
    if (choosingDrive && drives != null) DemoDrivesDialog(drives, onDismiss = { choosingDrive = false })
    if (choosingVideo) {
        VideoModeDialog(item, rememberVideoModes(item), onChooseVideo, onClose = { choosingVideo = false })
    }
    if (choosingGoPro) {
        GoProModelDialog(
            choices = goProChoices(item.model),
            current = item.goPro,
            onPick = { m ->
                choosingGoPro = false
                onChooseGoPro(m)
            },
            onDismiss = { choosingGoPro = false },
        )
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
 * Add equipment (UI-34 option B): a scan first, listing the RaceBoxes (Mini, Mini S, Micro) and
 * Open GoPro cameras (HERO9 and later) heard nearby, then the models that need no Bluetooth.
 * [nearby] leaves out what is already listed. [searching] is false when the Bluetooth permissions
 * are missing. [busyAddress] is the GoPro being paired, which takes a moment; [error] says why
 * pairing failed. [pairedGps] are Bluetooth GPS receivers paired in Android's settings (FBBD-108),
 * which cannot be found by a scan.
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
    onAddModel: (EquipmentModel, GoProModel?) -> Unit,
    onBack: () -> Unit,
    pairedGps: List<NearbyDevice> = emptyList(),
    onAddWifiGps: (address: String) -> Unit = {},
) {
    // A Wi-Fi receiver is added by its address, typed in a dialog (FBBD-115).
    var typingAddress by rememberSaveable { mutableStateOf(false) }
    if (typingAddress) {
        WifiGpsDialog(
            onAdd = { address ->
                typingAddress = false
                onAddWifiGps(address)
            },
            onDismiss = { typingAddress = false },
        )
    }
    // A GoPro started by hand is added by generation, picked in a dialog (FBBD-105).
    var pickingGoPro by rememberSaveable { mutableStateOf(false) }
    if (pickingGoPro) {
        GoProModelDialog(
            choices = goProChoices(EquipmentModel.GOPRO_FILES),
            current = null,
            onPick = { m ->
                pickingGoPro = false
                onAddModel(EquipmentModel.GOPRO_FILES, m)
            },
            onDismiss = { pickingGoPro = false },
        )
    }
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("Add equipment", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NearbyCard(nearby, searching, busyAddress, onAddNearby)
            DimText(
                if (searching) {
                    "Searching for RaceBox, GoPro HERO9 or later, Bluetooth LE GPS, RaceChrono DIY, " +
                        "Dragy and heart-rate monitors. Put a GoPro in pairing mode first."
                } else {
                    "Allow nearby devices (Settings › App › Run setup again) to search for a RaceBox or GoPro."
                },
            )
            if (error != null) DimText(error, color = MaterialTheme.colorScheme.error)
            PairedGpsSection(pairedGps, onAddNearby)
            WithoutBluetoothSection(withoutBluetooth) { m ->
                when (m) {
                    EquipmentModel.GOPRO_FILES -> pickingGoPro = true
                    EquipmentModel.TCP_GNSS -> typingAddress = true
                    else -> onAddModel(m, null)
                }
            }
        }
    }
}

/** What the scan found, or that it is still searching (or cannot). */
@Composable
private fun NearbyCard(
    nearby: List<NearbyDevice>,
    searching: Boolean,
    busyAddress: String?,
    onAddNearby: (NearbyDevice) -> Unit,
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
                    NearbyDevice(EquipmentModel.RACEBOX, "RaceBox Mini S 3241", "AA", 4),
                    NearbyDevice(EquipmentModel.GOPRO, "GoPro 1234", "BB", 3),
                ),
                searching = true,
                withoutBluetooth = listOf(EquipmentModel.GOPRO_FILES, EquipmentModel.DEMO_GNSS),
                pairedGps = listOf(
                    NearbyDevice(EquipmentModel.VBOX_SPORT, "VBSport 00000300", "DD", 0),
                    NearbyDevice(EquipmentModel.NMEA_GNSS, "Qstarz 818XT", "CC", 0),
                ),
                busyAddress = null,
                error = null,
                onAddNearby = {},
                onAddModel = { _, _ -> },
                onBack = {},
            )
        }
    }
}
