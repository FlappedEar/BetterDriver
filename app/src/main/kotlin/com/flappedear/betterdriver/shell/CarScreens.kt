package com.flappedear.betterdriver.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.setup.CarProfile

/**
 * Settings › Car (UI-35 option B): the next session's car as a big card on top, the other cars
 * below as cards. No car comes with the app. Tapping a card opens it for editing.
 */
@Composable
internal fun CarCards(cars: List<CarProfile>, chosenId: String?, onOpen: (CarProfile) -> Unit, onAdd: () -> Unit) {
    val chosen = cars.firstOrNull { it.id == chosenId }
    if (cars.isEmpty()) {
        DimText("No cars yet. Add the car you drive; its name goes into each session and its export.")
    }
    if (chosen != null) {
        ShellCard(
            Modifier
                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                .clickable { onOpen(chosen) },
        ) {
            SectionLabel("Next session")
            Text(chosen.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            if (chosen.notes.isNotBlank()) DimText(chosen.notes)
        }
    }
    cars.filter { it.id != chosenId }.forEach { car ->
        ShellCard(Modifier.clickable { onOpen(car) }) {
            Text(car.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            if (car.notes.isNotBlank()) DimText(car.notes)
        }
    }
    OutlinedButton(onClick = onAdd) { Text("+ Add car") }
}

/**
 * Adding or editing a car (UI-35): name and notes. [car] is null when adding. Car data (OBD-II)
 * attaches here in Phase 5. [chosen] is true for the next session's car.
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
            ShellCard { ListRow("Car data (OBD-II)", "Later", divider = false) }
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
