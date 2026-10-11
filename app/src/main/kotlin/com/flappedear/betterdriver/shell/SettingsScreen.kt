package com.flappedear.betterdriver.shell

import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.TrackChannel
import com.flappedear.betterdriver.setup.VideoMode
import java.util.Locale

private const val BytesPerGb = 1_000_000_000.0

/**
 * Settings (UI-15 option B): tabs for Devices, Car, Recording (with the track-screen tiles) and App.
 * [initialTab] lets the first-run checklist open a tab directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // One callback per place Settings links to.
@Composable
fun SettingsScreen(
    setup: Setup,
    version: String,
    initialTab: SettingsTab,
    onOpenEquipment: (Equipment) -> Unit,
    onAddEquipment: () -> Unit,
    onOpenCar: (CarProfile) -> Unit,
    onAddCar: () -> Unit,
    onRunSetup: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trackCount: Int = 0,
    onTracks: () -> Unit = {},
    onChooseTiles: (List<TrackChannel>) -> Unit = {},
    onChooseVideo: (Equipment, VideoMode) -> Unit = { _, _ -> },
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    Column(modifier.fillMaxSize()) {
        ShellTopBar("Settings", onBack = onBack)
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            SettingsTab.entries.forEach { t ->
                Tab(selected = tab == t.ordinal, onClick = { tab = t.ordinal }, text = { Text(t.title) })
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (SettingsTab.entries[tab]) {
                SettingsTab.Devices -> EquipmentList(setup.equipment, onOpenEquipment, onAddEquipment)
                SettingsTab.Car -> CarTab(setup, onOpenCar, onAddCar)
                SettingsTab.Recording -> RecordingTab(setup, onChooseTiles, onChooseVideo, trackCount, onTracks)
                SettingsTab.App -> AppTab(version, onRunSetup)
            }
        }
    }
}

@Composable
private fun CarTab(setup: Setup, onOpenCar: (CarProfile) -> Unit, onAddCar: () -> Unit) {
    CarCards(setup.cars, setup.session.carId, onOpenCar, onAddCar)
    ShellCard {
        ListRow("Units", "km/h, °C", divider = false)
        ListRow("Car data (OBD-II)", "Later")
    }
}

/**
 * Recording settings, including the track-screen tiles for every session (UI-38 option A) and one
 * Video row per camera the app sets (UI-39).
 */
@Composable
private fun RecordingTab(
    setup: Setup,
    onChooseTiles: (List<TrackChannel>) -> Unit,
    onChooseVideo: (Equipment, VideoMode) -> Unit,
    trackCount: Int,
    onTracks: () -> Unit,
) {
    val tiles = setup.session.tiles
    val context = LocalContext.current
    val freeGb = remember { StatFs(context.filesDir.path).availableBytes / BytesPerGb }
    var choosingTiles by rememberSaveable { mutableStateOf(false) }
    var choosingVideo by rememberSaveable { mutableStateOf<String?>(null) }
    ShellCard {
        val tracks = if (trackCount == 1) "1 track" else "$trackCount tracks"
        ListRow("Tracks and timing lines", tracks, divider = false, onClick = onTracks)
        ListRow("Track screen tiles", tilesLabel(tiles), onClick = { choosingTiles = true })
        setup.equipment.filter { it.videoMode != null }.forEach { cam ->
            ListRow("Video · ${cam.name}", videoLabel(cam), onClick = { choosingVideo = cam.id })
        }
        ListRow("Storage", String.format(Locale.ROOT, "%.0f GB free", freeGb))
    }
    if (choosingTiles) TilesDialog(tiles, onChooseTiles, onClose = { choosingTiles = false })
    setup.equipment(choosingVideo)?.let { cam ->
        VideoModeDialog(cam, rememberVideoModes(cam), { onChooseVideo(cam, it) }, onClose = { choosingVideo = null })
    }
}

@Composable
private fun AppTab(version: String, onRunSetup: () -> Unit) {
    ShellCard {
        ListRow("Theme", "Always dark", divider = false)
        ListRow("Track screen colours", "Green / red")
        ListRow("Spoken coach cues", "Later")
        ListRow("Version", version)
        ListRow("Run setup again", onClick = onRunSetup)
    }
}

@Preview(widthDp = 411, heightDp = 700)
@Composable
private fun SettingsScreenPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            SettingsScreen(
                setup = Setup.fresh { "phone" },
                version = "0.1.4",
                initialTab = SettingsTab.Devices,
                onOpenEquipment = {},
                onAddEquipment = {},
                onOpenCar = {},
                onAddCar = {},
                onRunSetup = {},
            )
        }
    }
}
