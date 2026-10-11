package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.NumberFont
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup

/**
 * Home (UI-12 option B, UI-36 option A): what this session uses as a card on top, one big start
 * button, and the last session below. Without a GNSS receiver START is greyed out and a card
 * offers to add one (UI-37 option B), so nothing is recorded from made-up positions. While a
 * session starts or records ([recording]), START goes back to it and the session card is locked,
 * because the running session has already started what it uses. The page scrolls when the card
 * lists many cameras or the font is large.
 */
@Suppress("LongParameterList") // Home links to every part of the session choice.
@Composable
fun HomeScreen(
    setup: Setup,
    recording: Boolean,
    trackName: String?,
    lastSession: String?,
    actions: SessionCardActions,
    onStart: () -> Unit,
    onAddReceiver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canStart = recording || setup.active().gnss != null
    Column(modifier.fillMaxSize()) {
        ShellTopBar("BetterDriver")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SessionCard(setup, actions, locked = recording)
            Spacer(Modifier.size(12.dp))
            StartButton(
                if (canStart) trackName ?: "No track detected" else "No GNSS receiver",
                enabled = canStart,
                onStart = onStart,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.size(12.dp))
            if (canStart) {
                ShellCard {
                    SectionLabel("Last session")
                    DimText(lastSession ?: "No sessions yet")
                }
            } else {
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
}

@Composable
private fun StartButton(label: String, enabled: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    val fill = if (enabled) c.gain else MaterialTheme.colorScheme.outline
    val text = if (enabled) c.onGain else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .size(190.dp)
            .border(10.dp, if (enabled) c.gain.copy(alpha = 0.25f) else fill, CircleShape)
            .padding(10.dp)
            .background(fill, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Start session", onClick = onStart),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "START",
                color = text,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = NumberFont,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                label,
                color = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun HomeScreenPreview() {
    var n = 0
    val ids = { "id${++n}" }
    val setup = Setup.fresh(ids)
        .addEquipment(EquipmentModel.RACEBOX_MICRO, "RaceBox Micro 3241", "AA", ids)
        .addEquipment(EquipmentModel.GOPRO_HERO11, "GoPro 1234", "BB", ids)
        .addCar("FR-S", "2013 Scion FR-S", ids)
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            HomeScreen(
                setup = setup,
                recording = false,
                trackName = "Tor Poznań",
                lastSession = "Yesterday · 14 laps · best 1:52.41",
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
                lastSession = null,
                actions = SessionCardActions(),
                onStart = {},
                onAddReceiver = {},
            )
        }
    }
}
