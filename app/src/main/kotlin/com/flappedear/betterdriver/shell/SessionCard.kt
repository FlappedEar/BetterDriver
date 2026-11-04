package com.flappedear.betterdriver.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.StartGroup
import com.flappedear.betterdriver.setup.StartRow
import com.flappedear.betterdriver.setup.chooseAtStart
import com.flappedear.betterdriver.setup.startGroups

/** The sheet's own start button: the ring on Drive opens the sheet, this starts the session. */
const val StartSessionLabel = "Start session"

/**
 * The list at START (FBBD-446, UI-311 A): the car, then every item listed in Settings › Equipment,
 * grouped, each in the same row: its name, whether it is in range now ([reach], by item id) and what
 * it is, and its choice. Where one item records (receiver, car data, heart rate, tyres) it is one of
 * the group or None; each camera has a switch. A camera's video mode is shown and changed in Settings
 * › Equipment (UI-312 B). The choice is kept for the next session (UI-313 A), so on a normal day Arek
 * only presses Start. Without camera access ([cameraAllowed] false) the phone camera says so instead
 * of silently not recording. [onStart] null leaves the start button out.
 */
@Suppress("LongParameterList") // The setup, how it changes, what is in range, and the start.
@Composable
internal fun SessionCard(
    setup: Setup,
    actions: SessionCardActions,
    cameraAllowed: Boolean = true,
    reach: Map<String, Reach> = emptyMap(),
    onStart: (() -> Unit)? = null,
) {
    val active = setup.active()
    var pickingCar by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("This session", style = MaterialTheme.typography.titleLarge)
        val car = active.car?.name ?: if (setup.cars.isEmpty()) "Add a car" else "None"
        ShellCard { ListRow("Car", car, divider = false, onClick = { pickingCar = true }) }
        startGroups(setup).forEach { group ->
            SectionLabel(group.title, Modifier.padding(top = 4.dp, start = 4.dp))
            val noAccess = setup.equipment.filter { !cameraAllowed && it.model == EquipmentModel.PHONE_CAMERA }
            ShellCard { StartGroupRows(group, reach, noAccess.map { it.id }.toSet(), actions) }
        }
        if (active.gnss == null) {
            ShellCard { AddRow("Add receiver", actions.onAddReceiver, divider = false) }
        }
        if (!cameraAllowed && active.uses(EquipmentModel.PHONE_CAMERA)) {
            DimText(
                "The phone camera won't record without camera access. " +
                    "Allow it in Settings › App › Run setup again.",
            )
        }
        if (onStart != null) {
            Button(
                onClick = onStart,
                enabled = active.gnss != null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("startSession"),
            ) { Text(StartSessionLabel, style = MaterialTheme.typography.titleMedium) }
        }
    }
    if (pickingCar) {
        CarDialog(setup, actions, onClose = { pickingCar = false })
    }
}

@Composable
private fun StartGroupRows(
    group: StartGroup,
    reach: Map<String, Reach>,
    noCameraAccess: Set<String>,
    actions: SessionCardActions,
) {
    group.rows.forEachIndexed { i, row ->
        val choose = { on: Boolean -> actions.update { it.chooseAtStart(group.kind, row.id, on) } }
        ListRow(
            row.name,
            divider = i > 0,
            onClick = if (group.oneOf) ({ choose(true) }) else null,
            supporting = rowDetail(row, reach[row.id], noAccess = row.id in noCameraAccess),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                reach[row.id]?.state?.let { StatusDot(it) }
                if (group.oneOf) {
                    RadioButton(selected = row.on, onClick = { choose(true) })
                } else {
                    Switch(checked = row.on, onCheckedChange = choose)
                }
            }
        }
    }
    if (group.oneOf) {
        val none = { actions.update { it.chooseAtStart(group.kind, null) } }
        ListRow("None", onClick = none) { RadioButton(selected = group.noneOn, onClick = none) }
    }
}

/** What a row says under its name: whether it is in range, then what it is or its video mode. */
private fun rowDetail(row: StartRow, reach: Reach?, noAccess: Boolean): String {
    val what = if (noAccess) "No camera access" else row.detail
    return listOfNotNull(reach?.label?.takeUnless { it == what }, what).joinToString(" · ")
}

@Composable
private fun CarDialog(setup: Setup, actions: SessionCardActions, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Car") },
        text = {
            Column {
                val all: List<Pair<String?, String>> = setup.cars.map { it.id to it.name } + (null to "None")
                all.forEach { (id, name) ->
                    RadioLine(name, setup.session.carId == id) {
                        actions.update { it.chooseCar(id) }
                        onClose()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                actions.onAddCar()
            }) { Text("Add car") }
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
