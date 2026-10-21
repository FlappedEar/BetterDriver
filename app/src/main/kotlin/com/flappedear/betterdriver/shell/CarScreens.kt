package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.setup.CarProfile

/**
 * Settings › Cars: names and notes only (UI-135 B). The next session's car says so; tapping a car
 * opens its page (UI-133 B). No car comes with the app.
 */
@Composable
internal fun CarList(cars: List<CarProfile>, chosenId: String?, onOpen: (CarProfile) -> Unit, onAdd: () -> Unit) {
    if (cars.isEmpty()) {
        DimText("No cars yet. Add the car you drive; its name goes into each session and its export.")
    }
    ShellCard {
        // The next session's car first.
        cars.sortedByDescending { it.id == chosenId }.forEachIndexed { i, car ->
            ListRow(
                car.name,
                if (car.id == chosenId) "Next session" else "",
                divider = i > 0,
                onClick = { onOpen(car) },
                supporting = car.notes.ifBlank { null },
            )
        }
        AddRow("Add car", onAdd, divider = cars.isNotEmpty())
    }
}

/**
 * A car's page (UI-133 B), titled with its name; Edit opens its name and notes. The next session's
 * car shows the adapter's state and the car's live values ([obd]), since the adapter reads the car
 * it is plugged into. Another car offers to become the next session's. [onBack] is null on the Car
 * destination of the bottom bar (UI-136 B).
 */
@Suppress("LongParameterList") // One callback per action on the page.
@Composable
fun CarPage(
    car: CarProfile,
    chosen: Boolean,
    obd: ObdView?,
    onEdit: () -> Unit,
    onChoose: () -> Unit,
    onBack: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(car.name, onBack = onBack) { TextButton(onClick = onEdit) { Text("Edit") } }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val line = listOf(car.notes, if (chosen) "next session" else "").filter { it.isNotBlank() }
            if (line.isNotEmpty()) DimText(line.joinToString(" · "))
            if (chosen && obd != null) {
                ObdContent(obd)
            } else {
                DimText("Live values show on the next session's car: the adapter reads the car it is plugged into.")
                FilledTonalButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                    Text("Use for the next session")
                }
            }
        }
    }
}

/**
 * The Car destination with no car for the next session (UI-136 B): the cars to choose from, or Add
 * car when there is none.
 */
@Composable
fun NoCarPage(cars: List<CarProfile>, onChoose: (CarProfile) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("Car")
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (cars.isEmpty()) {
                DimText("No cars yet. Add the car you drive; its name goes into each session and its export.")
                FilledTonalButton(onClick = onAdd) { Text("Add car") }
            } else {
                DimText("No car is chosen for the next session. Pick one to see its live values.")
                ShellCard {
                    cars.forEachIndexed { i, car ->
                        ListRow(car.name, "Use", divider = i > 0, onClick = { onChoose(car) })
                    }
                    AddRow("Add car", onAdd)
                }
            }
        }
    }
}

/**
 * Adding or editing a car (UI-35): name and notes. [car] is null when adding. [chosen] is true for
 * the next session's car. Its live values are on the car's page (UI-133 B), not here.
 */
@Suppress("LongParameterList") // One callback per action on the screen.
@Composable
fun CarScreen(
    car: CarProfile?,
    chosen: Boolean,
    onSave: (name: String, notes: String) -> Unit,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(car?.name.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(car?.notes.orEmpty()) }
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(if (car == null) "Add car" else "Edit car", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
            )
            DimText("The name goes into each session and its VBO export.")
            Button(onClick = { onSave(name, notes) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
            if (car != null && !chosen) {
                FilledTonalButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                    Text("Use for the next session")
                }
            }
            if (car != null) {
                OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove car", color = TrackTheme.colors.loss)
                }
            }
        }
    }
}
