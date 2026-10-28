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
import com.flappedear.betterdriver.obd.OwnChannels
import com.flappedear.betterdriver.obd.valueText
import com.flappedear.betterdriver.setup.CarParameter
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

/** What the car's page can do (FBBD-344), one callback each; the defaults do nothing. */
data class CarPageActions(
    val onEdit: () -> Unit = {},
    val onChoose: () -> Unit = {},
    val onBack: (() -> Unit)? = null,
    val onPids: () -> Unit = {},
    val onCan: () -> Unit = {},
    val onAddParameter: () -> Unit = {},
    val onParameter: (CarParameter) -> Unit = {},
)

/**
 * A car's page (UI-133 B, FBBD-344 UI-300 A), titled with its name; Edit opens its name and notes. On
 * one page: the next session's car shows the adapter's state and the car's live values ([obd]),
 * since the adapter reads the car it is plugged into; then the driver's own parameters, then what the
 * car offers at its OBD port (the PIDs it reports, the CAN ids heard), from the last scan. Scan car
 * ([scan]) runs one only when tapped (UI-303 B). Another car offers to become the next session's.
 * [CarPageActions.onBack] is null on the Car destination of the bottom bar (UI-136 B).
 */
@Composable
fun CarPage(
    car: CarProfile,
    chosen: Boolean,
    obd: ObdView?,
    actions: CarPageActions = CarPageActions(),
    scan: CarScanState = CarScanState(),
) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(car.name, onBack = actions.onBack) { TextButton(onClick = actions.onEdit) { Text("Edit") } }
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
                FilledTonalButton(onClick = actions.onChoose, modifier = Modifier.fillMaxWidth()) {
                    Text("Use for the next session")
                }
            }
            SectionLabel("Your parameters", Modifier.padding(top = 8.dp, start = 4.dp))
            ShellCard {
                val readings = obd?.status?.readings.orEmpty().associateBy { it.id }
                car.parameters.forEachIndexed { i, p ->
                    val reading = readings[OwnChannels.id(p.key)]
                    ListRow(
                        p.name,
                        reading?.valueText() ?: p.unit.ifBlank { "–" },
                        divider = i > 0,
                        onClick = { actions.onParameter(p) },
                        supporting = parameterSource(p),
                    )
                }
                AddRow("Add parameter", actions.onAddParameter, divider = car.parameters.isNotEmpty())
            }
            if (car.parameters.isEmpty()) {
                DimText(
                    "A value the app does not read: a CAN id or an OBD request with a formula. Recorded in every " +
                        "session with this car and in its VBO export.",
                )
            }
            CarOffers(car, chosen, scan, actions)
        }
    }
}

/**
 * A scan of what the car offers (UI-303 B): running, why the last one failed, and how to start one
 * (null: cannot now).
 */
data class CarScanState(val running: Boolean = false, val note: String? = null, val start: (() -> Unit)? = null)

@Composable
private fun CarOffers(car: CarProfile, chosen: Boolean, scan: CarScanState, actions: CarPageActions) {
    SectionLabel("What the car offers", Modifier.padding(top = 8.dp, start = 4.dp))
    val last = car.scan
    ShellCard {
        if (last == null) {
            ListRow(
                "Not scanned yet",
                supporting = "Scan car lists the OBD-II PIDs it reports and the CAN ids at its port.",
            )
        } else {
            val at = scanTime(last.atMs)
            ListRow(
                "OBD-II",
                "",
                divider = false,
                onClick = actions.onPids,
                supporting = "${reportedPids(last).size} PIDs reported · scanned $at",
            )
            ListRow(
                "CAN bus",
                "",
                onClick = actions.onCan,
                supporting = if (last.canHeard) "${last.canIds.size} ids heard" else "Not heard: ${noCanReason(last)}",
            )
        }
    }
    when {
        scan.running -> {
            FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Scanning the car…")
            }
            DimText("Connecting again, asking the car for its PIDs, then listening to the CAN bus. About 15 s.")
        }
        chosen && scan.start != null -> {
            FilledTonalButton(onClick = scan.start, modifier = Modifier.fillMaxWidth()) {
                Text(if (last == null) "Scan car" else "Scan again")
            }
            DimText(scan.note ?: "Takes about 15 s with the engine on.")
        }
        chosen -> DimText("Scan car works outside a session, with an OBD-II adapter added.")
        else -> DimText("Only the next session's car can be scanned: the adapter reads the car it is plugged into.")
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
