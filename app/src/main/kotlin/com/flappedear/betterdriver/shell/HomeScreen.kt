package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.HealthCard
import com.flappedear.betterdriver.core.ui.component.HealthDetail
import com.flappedear.betterdriver.core.ui.component.HealthIcon
import com.flappedear.betterdriver.core.ui.component.HealthValue
import com.flappedear.betterdriver.core.ui.component.HealthValueRow
import com.flappedear.betterdriver.core.ui.component.MiniBars
import com.flappedear.betterdriver.core.ui.component.SectionTitle
import com.flappedear.betterdriver.format.formatLapTime
import com.flappedear.betterdriver.setup.EquipmentKind
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup

/**
 * Drive (UI-130 C, UI-131 A, UI-132 B; cards since UI-308 A): a large title, one START ring in the
 * accent colour, then Pinned cards in the session summary's style: what to work on next time here
 * and how the last day here went ([pinned], each only with data), and the devices this session uses
 * with Change, which opens a sheet with the car, the receiver and a switch per camera. The last
 * session is not on Drive (FBBD-276); it is the first row in Sessions. Without a GNSS receiver START
 * is greyed out and a card offers to add one (UI-37 option B), so nothing is recorded from made-up
 * positions. While a session starts or records ([recording]), START goes back to it and the choice is
 * locked, because the running session has already started what it uses. The page scrolls when the
 * font is large or the window is short.
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
    pinned: DrivePinned = DrivePinned(),
) {
    val canStart = recording || setup.active().gnss != null
    var choosing by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        ShellTopBar("Drive")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StartButton(
                if (canStart) trackName ?: "No track detected" else "No GNSS receiver",
                enabled = canStart,
                onStart = onStart,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp),
            )
            if (!canStart) {
                HealthCard {
                    Text(
                        "Add a GNSS receiver to start",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    DimText("Lap timing needs one. Add a RaceBox, or Demo GNSS to try the app.")
                    FilledTonalButton(onClick = onAddReceiver) { Text("Add receiver") }
                }
            }
            SectionTitle("Pinned", Modifier.padding(top = 4.dp))
            pinned.focus?.let { NextSessionHere(it, trackName) }
            pinned.lastTime?.let { LastTimeHereCard(it) }
            ThisSession(setup, recording, cameraAllowed, onChange = { choosing = true })
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

/** "Next session here": the corners the last session here left to work on, and their time a lap. */
@Composable
private fun NextSessionHere(focus: DriveFocus, trackName: String?) {
    HealthCard(title = "Next session here", icon = HealthIcon.Coach, trailing = trackName) {
        HealthValue(focus.corners.joinToString(" · "), size = 22.dp)
        val first = focus.corners.first()
        HealthDetail(listOfNotNull(focus.secondsToFind?.let(::focusTimeText), "$first first").joinToString(" · "))
    }
}

/** "Last time here": the last day's best lap in purple, and each session's best as a bar. */
@Composable
private fun LastTimeHereCard(last: LastTimeHere) {
    val c = TrackTheme.colors
    HealthCard(title = "Last time here", icon = HealthIcon.Laps, trailing = dayLabel(last.day)) {
        HealthValueRow(
            chart = { MiniBars(last.sessionBests.map { it.toDouble() }, MaterialTheme.colorScheme.primary, c.best) },
        ) {
            HealthValue(formatLapTime(last.bestMillis), color = c.best)
            val n = last.sessionBests.size
            HealthDetail("best · $n session${if (n == 1) "" else "s"}")
        }
    }
}

/**
 * The devices this session uses (UI-308 A): how many, their names, the car and the cameras, with
 * Change. A card names devices; it does not say whether they answer.
 */
@Composable
private fun ThisSession(setup: Setup, recording: Boolean, cameraAllowed: Boolean, onChange: () -> Unit) {
    val active = setup.active()
    val names = listOfNotNull(active.gnss?.name) +
        active.carData.map { it.name } +
        active.sensors.map { it.name } +
        active.cameras.map { it.name }
    val car = active.car?.name ?: if (setup.cars.isEmpty()) "No car added" else "No car"
    HealthCard(title = "This session", icon = HealthIcon.Device, trailing = car) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                HealthValue("${names.size}", unit = if (names.size == 1) "device" else "devices")
                if (names.isNotEmpty()) HealthDetail(names.joinToString(" · "))
                HealthDetail(camerasLine(setup))
            }
            if (!recording) TextButton(onClick = onChange) { Text("Change") }
        }
        if (active.gnss == null) HealthDetail("No GNSS receiver")
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
            .border(8.dp, if (enabled) scheme.primary.copy(alpha = 0.25f) else fill, CircleShape)
            .padding(8.dp)
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

/** The ring as in the picked mockup (UI-308 A), with the Pinned cards under it on one screen. */
private val StartSize = 170.dp

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
