package com.flappedear.betterdriver.shell

import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.feature.track.TrackBrightness
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.VideoMode
import java.util.Locale

private const val BytesPerGb = 1_000_000_000.0

/** Track screen choices kept in [AppPrefs]: brightness (FBBD-63) and the GNSS sound (FBBD-64). */
data class TrackScreenPrefs(
    val brightness: TrackBrightness = TrackBrightness.Max,
    val gnssSound: Boolean = false,
)

/**
 * Settings (UI-137 B): with no [topic], one short page of topics, each saying what is set; a topic
 * opens its own page ([onOpenTopic]), shown here with [topic] and a back button. The rows inside
 * each page are the ones the tabs had. The track screen's tiles are set on Settings › Track screen
 * ([trackLayout], FBBD-345) and on the track screen itself (UI-123 B).
 */
@Suppress("LongParameterList") // One callback per place Settings links to.
@Composable
fun SettingsScreen(
    setup: Setup,
    version: String,
    topic: SettingsTopic?,
    onOpenTopic: (SettingsTopic) -> Unit,
    onOpenEquipment: (Equipment) -> Unit,
    onAddEquipment: () -> Unit,
    onOpenCar: (CarProfile) -> Unit,
    onAddCar: () -> Unit,
    onRunSetup: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trackCount: Int = 0,
    onChooseVideo: (Equipment, VideoMode) -> Unit = { _, _ -> },
    updateRow: @Composable () -> Unit = {},
    debugRows: @Composable () -> Unit = {},
    backupRows: @Composable () -> Unit = {},
    coach: CoachSettings = CoachSettings(),
    onOpenCoach: () -> Unit = {},
    trackScreen: TrackScreenPrefs = TrackScreenPrefs(),
    onTrackScreen: (TrackScreenPrefs) -> Unit = {},
    onOpenAbout: () -> Unit = {},
    obdRow: String = obdRowValue(null, null),
    onOpenObd: () -> Unit = {},
    reach: Map<String, Reach> = emptyMap(),
    trackLayout: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        ShellTopBar(topic?.title ?: "Settings", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (topic) {
                null -> ShellCard {
                    val freeGb = rememberFreeGb()
                    SettingsTopic.entries.forEachIndexed { i, t ->
                        val summary = topicSummary(t, setup, trackCount, trackScreen, coach, version, freeGb)
                        ListRow(t.title, divider = i > 0, supporting = summary, onClick = { onOpenTopic(t) }) {
                            Chevron()
                        }
                    }
                }
                SettingsTopic.Equipment ->
                    EquipmentGroups(setup.equipment, reach, onOpenEquipment, onAddEquipment, obdRow, onOpenObd)
                SettingsTopic.Cars -> {
                    CarList(setup.cars, setup.session.carId, onOpenCar, onAddCar)
                    ShellCard { ListRow("Units", "km/h, °C", divider = false) }
                }
                SettingsTopic.TrackScreen -> {
                    trackLayout()
                    TrackScreenCard(trackScreen, onTrackScreen)
                }
                SettingsTopic.Video -> VideoPage(setup, onChooseVideo)
                SettingsTopic.App ->
                    AppPage(version, onRunSetup, updateRow, backupRows, coach, onOpenCoach, onOpenAbout)
                // Settings › Diagnostics: the app's one log, to share after a drive or clear (FBBD-183).
                SettingsTopic.Diagnostics -> ShellCard { debugRows() }
                // Their own pages, opened by onOpenTopic.
                SettingsTopic.Tracks, SettingsTopic.Coach -> Unit
            }
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        ShellIcons.Chevron,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun rememberFreeGb(): Double {
    val context = LocalContext.current
    return remember { StatFs(context.filesDir.path).availableBytes / BytesPerGb }
}

/** Settings › Video: one row per camera the app sets (UI-39), and storage. */
@Composable
private fun VideoPage(setup: Setup, onChooseVideo: (Equipment, VideoMode) -> Unit) {
    val freeGb = rememberFreeGb()
    var choosingVideo by rememberSaveable { mutableStateOf<String?>(null) }
    ShellCard {
        val cams = setup.equipment.filter { it.videoMode != null }
        cams.forEachIndexed { i, cam ->
            ListRow(cam.name, videoLabel(cam), divider = i > 0, onClick = { choosingVideo = cam.id })
        }
        ListRow("Storage", String.format(Locale.ROOT, "%.0f GB free", freeGb), divider = cams.isNotEmpty())
    }
    setup.equipment(choosingVideo)?.let { cam ->
        VideoModeDialog(cam, rememberVideoModes(cam), { onChooseVideo(cam, it) }, onClose = { choosingVideo = null })
    }
}

/**
 * How the track screen behaves: full brightness (on by default; off leaves the phone's own
 * brightness) and a short sound when GNSS is lost and when it comes back (off by default).
 */
@Composable
private fun TrackScreenCard(prefs: TrackScreenPrefs, onChange: (TrackScreenPrefs) -> Unit) {
    ShellCard {
        ListRow("Track screen brightness", prefs.brightness.label, divider = false) {
            Switch(checked = prefs.brightness == TrackBrightness.Max, onCheckedChange = { on ->
                onChange(prefs.copy(brightness = if (on) TrackBrightness.Max else TrackBrightness.System))
            })
        }
        ListRow("Sound when GNSS is lost", if (prefs.gnssSound) "On" else "Off") {
            Switch(checked = prefs.gnssSound, onCheckedChange = { on -> onChange(prefs.copy(gnssSound = on)) })
        }
    }
}

/** Permissions lists what each one that is off stops (FBBD-35); About and credits is a page of its own (FBBD-79). */
@Suppress("LongParameterList")
@Composable
private fun AppPage(
    version: String,
    onRunSetup: () -> Unit,
    updateRow: @Composable () -> Unit,
    backupRows: @Composable () -> Unit,
    coach: CoachSettings,
    onOpenCoach: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    ShellCard {
        ListRow("Theme", "Always dark", divider = false)
        ListRow("Track screen colours", "Green / red")
        ListRow("Spoken coach", coachLabel(coach), onClick = onOpenCoach)
        PermissionRows()
        ListRow("Version", version)
        updateRow()
        backupRows()
        ListRow("Run setup again", onClick = onRunSetup)
        ListRow("About and credits", onClick = onOpenAbout)
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
                topic = null,
                onOpenTopic = {},
                onOpenEquipment = {},
                onAddEquipment = {},
                onOpenCar = {},
                onAddCar = {},
                onRunSetup = {},
            )
        }
    }
}
