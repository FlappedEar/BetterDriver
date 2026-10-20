package com.flappedear.betterdriver.shell

import androidx.compose.runtime.Composable
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentKind
import com.flappedear.betterdriver.setup.EquipmentModel

/** The kind of an OBD-II adapter (UI-105 C), in the equipment list and on its page. */
internal const val CarDataLabel = "Car data"

/** The model (a GoPro's is picked), or an OBD-II adapter's kind, and how the item is reached. */
@Composable
internal fun ModelRows(item: Equipment, connection: String?, onPickGoPro: () -> Unit) {
    if (item.kind == EquipmentKind.CAR_DATA) {
        ListRow("Kind", CarDataLabel)
    } else {
        ListRow("Model", item.modelLabel, onClick = if (item.model.isGoPro) onPickGoPro else null)
    }
    val address = item.address ?: return
    if (item.model == EquipmentModel.OBD_WIFI) {
        ListRow("Address", address)
    } else {
        ListRow("Bluetooth", connection ?: "Paired · $address")
    }
}
