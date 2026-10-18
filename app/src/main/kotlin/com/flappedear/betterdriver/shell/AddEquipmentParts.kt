package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.TcpAddress

/** Models added without a Bluetooth search (UI-34 B): the phone camera, a GoPro started by hand, Demo GNSS. */
@Composable
internal fun WithoutBluetoothSection(models: List<EquipmentModel>, onAdd: (EquipmentModel) -> Unit) {
    if (models.isEmpty()) return
    SectionLabel("Add without Bluetooth")
    ShellCard {
        models.forEachIndexed { i, m ->
            ListRow(m.label, withoutBluetoothNote(m), divider = i > 0, onClick = { onAdd(m) })
        }
    }
}

private fun withoutBluetoothNote(model: EquipmentModel): String = when (model) {
    EquipmentModel.GOPRO_FILES -> "HERO8 or older, MAX"
    EquipmentModel.CAMERA_FILES -> "DJI Action, Insta360, Garmin and others"
    EquipmentModel.TCP_GNSS -> "NMEA or RaceChrono DIY over Wi-Fi"
    EquipmentModel.DEMO_GNSS -> "For trying the app"
    EquipmentModel.DEMO_TRACK -> "Laps and coach cues on a made-up circuit"
    EquipmentModel.PHONE_CAMERA -> "Built in"
    else -> ""
}

/**
 * Bluetooth GPS receivers already paired in Android's settings: NMEA receivers (Qstarz, Garmin GLO
 * and the like, FBBD-108) and VBOX Sports (FBBD-116). They speak Bluetooth Classic, which the app
 * does not scan for.
 */
@Composable
internal fun PairedGpsSection(pairedGps: List<NearbyDevice>, onAdd: (NearbyDevice) -> Unit) {
    SectionLabel("Paired Bluetooth GPS")
    if (pairedGps.isNotEmpty()) {
        ShellCard {
            pairedGps.forEachIndexed { i, d ->
                ListRow(d.name, divider = i > 0) {
                    FilledTonalButton(onClick = { onAdd(d) }) { Text("Add") }
                }
            }
        }
    }
    DimText(
        "Pair a VBOX Sport, or a GPS receiver that sends NMEA such as a Qstarz or Garmin GLO, " +
            "in Android's Bluetooth settings.",
    )
}

/**
 * The address of a Wi-Fi GPS receiver (FBBD-115): host or IP address and TCP port, from the
 * receiver's manual. Add stays off until both are usable ([TcpAddress.parse]).
 */
@Composable
internal fun WifiGpsDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    val address = TcpAddress.parse(host, port)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi GPS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Join the receiver's Wi-Fi first. Its manual gives the address and port.")
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP address or host") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { address?.let { onAdd(it.toString()) } }, enabled = address != null) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
