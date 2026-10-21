package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentKind

/**
 * Settings › Equipment (UI-33 option B, UI-138 B): everything Arek added, grouped by kind, each
 * with whether it can be reached now ([reach], by item id; empty while that is not known), and one
 * Add. Under Car data, Live values opens the next session's car data (UI-135 B). A fresh install has
 * only the phone camera.
 */
@Suppress("LongParameterList") // The list, its reach, and where its rows lead.
@Composable
internal fun EquipmentGroups(
    equipment: List<Equipment>,
    reach: Map<String, Reach>,
    onOpen: (Equipment) -> Unit,
    onAdd: () -> Unit,
    obdRow: String,
    onOpenObd: () -> Unit,
) {
    GroupOrder.forEach { kind ->
        val items = equipment.filter { it.kind == kind }
        if (items.isEmpty()) return@forEach
        SectionLabel(groupLabel(kind), Modifier.padding(top = 4.dp, start = 4.dp))
        ShellCard {
            items.forEachIndexed { i, e -> EquipmentRow(e, reach[e.id], divider = i > 0) { onOpen(e) } }
            if (kind == EquipmentKind.CAR_DATA) ListRow("Live values", obdRow, onClick = onOpenObd)
        }
    }
    ShellCard { AddRow("Add equipment", onAdd, divider = false) }
    DimText("Tap an item to rename or remove it.")
}

private val GroupOrder = listOf(EquipmentKind.GNSS, EquipmentKind.CAR_DATA, EquipmentKind.SENSOR, EquipmentKind.CAMERA)

private fun groupLabel(kind: EquipmentKind): String = when (kind) {
    EquipmentKind.GNSS -> "GNSS receivers"
    EquipmentKind.CAR_DATA -> CarDataLabel
    EquipmentKind.SENSOR -> "Sensors"
    EquipmentKind.CAMERA -> "Cameras"
}

@Composable
private fun EquipmentRow(e: Equipment, reach: Reach?, divider: Boolean, onClick: () -> Unit) {
    ListRow(e.name, divider = divider, onClick = onClick, supporting = reach?.label ?: kindLabel(e)) {
        reach?.state?.let { StatusDot(it) }
    }
}
