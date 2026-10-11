package com.flappedear.betterdriver.shell

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.flappedear.betterdriver.setup.EquipmentModel

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
    EquipmentModel.DEMO_GNSS -> "For trying the app"
    EquipmentModel.PHONE_CAMERA -> "Built in"
    else -> ""
}

/**
 * Bluetooth GPS receivers (Qstarz, Garmin GLO and the like) already paired in Android's settings
 * (FBBD-108). They speak Bluetooth Classic, which the app does not scan for.
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
    DimText("Pair a GPS receiver that sends NMEA, such as a Qstarz or Garmin GLO, in Android's Bluetooth settings.")
}
