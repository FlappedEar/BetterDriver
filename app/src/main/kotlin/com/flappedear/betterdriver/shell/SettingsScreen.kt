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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import java.util.Locale

private const val BytesPerGb = 1_000_000_000.0

/**
 * Settings (UI-15 option B): tabs for Devices, Car, Recording and App.
 * [initialTab] lets the first-run checklist open a tab directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    version: String,
    initialTab: SettingsTab,
    onPair: () -> Unit,
    onRunSetup: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trackCount: Int = 0,
    onTracks: () -> Unit = {},
    onPairGoPro: () -> Unit = {},
    updateRow: @Composable () -> Unit = {},
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
                SettingsTab.Devices -> DevicesTab(prefs, onPair, onPairGoPro)
                SettingsTab.Car -> CarTab(prefs)
                SettingsTab.Recording -> RecordingTab(trackCount, onTracks)
                SettingsTab.App -> AppTab(version, onRunSetup, updateRow)
            }
        }
    }
}

@Composable
private fun DevicesTab(prefs: AppPrefs, onPair: () -> Unit, onPairGoPro: () -> Unit) {
    ShellCard {
        ListRow(
            "RaceBox Micro",
            prefs.raceBox?.let { "Paired · ${it.serial}" } ?: "Not paired",
            divider = false,
            onClick = onPair,
        )
        ListRow("Phone camera") {
            Switch(checked = prefs.phoneCamera, onCheckedChange = prefs::updatePhoneCamera)
        }
        ListRow(
            "GoPro HERO11",
            prefs.goPro?.let { "Paired · ${it.serial}" } ?: "Not paired",
            onClick = onPairGoPro,
        )
        ListRow("GoPro HERO8", "Start by hand")
    }
    DimText("GoPro HERO8 cannot be started from the app. Press record by hand; videos are lined up after the session.")
}

@Composable
private fun CarTab(prefs: AppPrefs) {
    ShellCard {
        Cars.forEachIndexed { i, car ->
            ListRow(car, divider = i > 0, onClick = { prefs.updateCar(car) }) {
                RadioButton(selected = prefs.car == car, onClick = { prefs.updateCar(car) })
            }
        }
    }
    ShellCard {
        ListRow("Units", "km/h, °C", divider = false)
        ListRow("Car data (OBD-II)", "Later")
    }
}

@Composable
private fun RecordingTab(trackCount: Int, onTracks: () -> Unit) {
    val context = LocalContext.current
    val freeGb = remember { StatFs(context.filesDir.path).availableBytes / BytesPerGb }
    ShellCard {
        val tracks = if (trackCount == 1) "1 track" else "$trackCount tracks"
        ListRow("Tracks and timing lines", tracks, divider = false, onClick = onTracks)
        ListRow("Video quality", "4K · 60 fps")
        ListRow("Storage", String.format(Locale.ROOT, "%.0f GB free", freeGb))
    }
}

@Composable
private fun AppTab(version: String, onRunSetup: () -> Unit, updateRow: @Composable () -> Unit) {
    ShellCard {
        ListRow("Theme", "Always dark", divider = false)
        ListRow("Track screen colours", "Green / red")
        ListRow("Spoken coach cues", "Later")
        ListRow("Version", version)
        updateRow()
        ListRow("Run setup again", onClick = onRunSetup)
    }
}

@Preview(widthDp = 411, heightDp = 700)
@Composable
private fun SettingsScreenPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.padding(16.dp)) {
                ShellCard {
                    ListRow("RaceBox Micro", "Not paired", divider = false, onClick = {})
                    ListRow("Phone camera") { Switch(checked = true, onCheckedChange = {}) }
                    ListRow("GoPro HERO11", "Not paired")
                    ListRow("GoPro HERO8", "Start by hand")
                }
            }
        }
    }
}
