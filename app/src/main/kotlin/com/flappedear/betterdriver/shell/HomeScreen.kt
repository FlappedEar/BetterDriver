package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.NumberFont
import com.flappedear.betterdriver.setup.EquipmentKind
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup

/**
 * Drive (UI-130 C, UI-131 A, UI-132 B): what this session uses as chips on top, one big START ring
 * in the accent colour (UI-144 B, "start even bigger"), and the session's choice in one line under
 * it. Change opens a sheet with the car, the receiver and a switch per camera. The last session is
 * not on Drive (FBBD-276); it is the first row in Sessions. Without a GNSS receiver START is greyed
 * out and a card offers to add one (UI-37 option B), so nothing is recorded from made-up positions.
 * While a session starts or records ([recording]), START goes back to it and the choice is locked,
 * because the running session has already started what it uses. The page scrolls when the font is
 * large or the window is short.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    setup: Setup,
    recording: Boolean,
    trackName: String?,
    actions: SessionCardActions,
    onStart: () -> Unit,
    onAddReceiver: () -> Unit,
    modifier: Modifier = Modifier,
    cameraAllowed: Boolean = true,
) {
    val canStart = recording || setup.active().gnss != null
    var choosing by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        ShellTopBar("BetterDriver")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceChips(setup)
            Spacer(Modifier.size(8.dp))
            StartButton(
                if (canStart) trackName ?: "No track detected" else "No GNSS receiver",
                enabled = canStart,
                onStart = onStart,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.size(8.dp))
            ChoiceLine(setup, recording, cameraAllowed, onChange = { choosing = true })
            if (!canStart) {
                ShellCard {
                    Text(
                        "Add a GNSS receiver to start",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    DimText("Lap timing needs one. Add a RaceBox, or Demo GNSS to try the app.")
                    FilledTonalButton(onClick = onAddReceiver) { Text("Add receiver") }
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
    if (choosing && !recording) {
        ModalBottomSheet(onDismissRequest = { choosing = false }) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).navigationBarsPadding()) {
                SessionCard(setup, actions, cameraAllowed = cameraAllowed)
            }
        }
    }
}

/**
 * The devices this session uses, one chip each: the receiver, the OBD-II adapters, the sensors and
 * the cameras turned on. A chip names a device; it does not say whether it answers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceChips(setup: Setup) {
    val active = setup.active()
    val names = listOfNotNull(active.gnss?.name) +
        active.carData.map { it.name } +
        active.sensors.map { it.name } +
        active.cameras.map { it.name }
    if (names.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { name ->
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline, ChipShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private val ChipShape = RoundedCornerShape(16.dp)

/** "Corolla · RaceBox Micro 3241" over "2 of 3 cameras on", and Change (UI-130 C, UI-132 B). */
@Composable
private fun ChoiceLine(setup: Setup, recording: Boolean, cameraAllowed: Boolean, onChange: () -> Unit) {
    val active = setup.active()
    val car = active.car?.name ?: if (setup.cars.isEmpty()) "No car added" else "No car"
    val gnss = active.gnss?.name ?: "No GNSS receiver"
    ShellCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    "$car · $gnss",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                DimText(camerasLine(setup))
            }
            if (!recording) TextButton(onClick = onChange) { Text("Change") }
        }
        if (!cameraAllowed && active.uses(EquipmentModel.PHONE_CAMERA)) {
            DimText(
                "The phone camera won't record without camera access. " +
                    "Allow it in Settings › App › Run setup again.",
            )
        }
        if (recording) DimText("Recording. This session's choice is fixed until it stops.")
    }
}

/** How many of the listed cameras record this session. */
internal fun camerasLine(setup: Setup): String {
    val all = setup.ofKind(EquipmentKind.CAMERA)
    val on = all.count { it.id in setup.session.cameraIds }
    return when {
        all.isEmpty() -> "No cameras"
        all.size == 1 -> if (on == 1) "${all[0].name} on" else "${all[0].name} off"
        else -> "$on of ${all.size} cameras on"
    }
}

@Composable
private fun StartButton(label: String, enabled: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (enabled) scheme.primary else scheme.outline
    val text = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant
    Box(
        modifier
            .size(StartSize)
            .border(12.dp, if (enabled) scheme.primary.copy(alpha = 0.25f) else fill, CircleShape)
            .padding(12.dp)
            .background(fill, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Start session", onClick = onStart),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "START",
                color = text,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = NumberFont,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                label,
                color = text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** Arek's note on UI-130 C: "start even bigger" than the 190 dp ring before. */
private val StartSize = 250.dp

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun HomeScreenPreview() {
    var n = 0
    val ids = { "id${++n}" }
    val setup = Setup.fresh(ids)
        .addEquipment(EquipmentModel.RACEBOX, "RaceBox Mini S 3241", "AA", ids)
        .addEquipment(EquipmentModel.GOPRO, "GoPro 1234", "BB", ids, GoProModel.HERO13_BLACK)
        .addEquipment(EquipmentModel.GOPRO, "GoPro 5678", "CC", ids, GoProModel.HERO10_BLACK)
        .addCar("Track car", "Any car", ids)
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            HomeScreen(
                setup = setup,
                recording = false,
                trackName = "Tor Poznań",
                actions = SessionCardActions(),
                onStart = {},
                onAddReceiver = {},
            )
        }
    }
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun HomeNothingSetUpPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            HomeScreen(
                setup = Setup.fresh { "phone" },
                recording = false,
                trackName = null,
                actions = SessionCardActions(),
                onStart = {},
                onAddReceiver = {},
            )
        }
    }
}
