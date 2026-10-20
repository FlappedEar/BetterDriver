package com.flappedear.betterdriver.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentKind
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup

private enum class Picker { Car, Gnss }

/**
 * "This session" on home (UI-36 option A): the car, the GNSS receiver and a switch per camera.
 * Track-screen tiles are changed on the track screen (UI-123 B). The last choice is kept, so on a normal
 * day Arek only presses START. A camera turned off here does not start. While a session starts or
 * records the card is [locked]: it shows what the running session uses and nothing can change.
 * Without camera access ([cameraAllowed] false) the phone camera says so instead of silently not recording.
 */
@Composable
internal fun SessionCard(
    setup: Setup,
    actions: SessionCardActions,
    locked: Boolean = false,
    cameraAllowed: Boolean = true,
) {
    val active = setup.active()
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    ShellCard {
        SectionLabel("This session")
        val car = active.car?.name ?: if (setup.cars.isEmpty()) "Add a car" else "None"
        ListRow("Car", car, divider = false, onClick = { picker = Picker.Car }.takeUnless { locked })
        ListRow("GNSS", active.gnss?.name ?: "None", onClick = { picker = Picker.Gnss }.takeUnless { locked })
        setup.ofKind(EquipmentKind.CAMERA).forEach { cam ->
            val on = cam.id in setup.session.cameraIds
            ListRow(cam.name, cameraNote(cam, cameraAllowed)) {
                Switch(
                    checked = on,
                    onCheckedChange = { v -> actions.update { it.useCamera(cam.id, v) } },
                    enabled = !locked,
                )
            }
        }
        if (!cameraAllowed && active.uses(EquipmentModel.PHONE_CAMERA)) {
            DimText(
                "The phone camera won't record without camera access. " +
                    "Allow it in Settings › App › Run setup again.",
            )
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
        null -> Unit
    }
}

private fun cameraNote(cam: Equipment, cameraAllowed: Boolean): String? = when {
    !cam.model.controlledByApp -> "Start by hand"
    cam.model == EquipmentModel.PHONE_CAMERA && !cameraAllowed -> "No camera access"
    else -> null
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
