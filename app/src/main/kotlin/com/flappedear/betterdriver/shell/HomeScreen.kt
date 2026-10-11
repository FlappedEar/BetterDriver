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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Home (UI-12 option B): devices as small status chips, one big start
 * button, and the last session below. Track detection is not built yet, so
 * the button says so instead of naming a track.
 */
@Composable
fun HomeScreen(
    devices: List<DeviceStatus>,
    trackName: String?,
    lastSession: String?,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ShellTopBar("BetterDriver")
        Column(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceChips(devices)
            Spacer(Modifier.weight(1f))
            StartButton(trackName, onStart, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.weight(1f))
            ShellCard {
                SectionLabel("Last session")
                DimText(lastSession ?: "No sessions yet")
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceChips(devices: List<DeviceStatus>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        devices.forEach { d ->
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(d.state)
                Text(d.shortName, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StartButton(trackName: String?, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    Box(
        modifier
            .size(190.dp)
            .border(10.dp, c.gain.copy(alpha = 0.25f), CircleShape)
            .padding(10.dp)
            .background(c.gain, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Start session", onClick = onStart),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "START",
                color = c.onGain,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = NumberFont,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                trackName ?: "No track detected",
                color = c.onGain,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun HomeScreenPreview() {
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            HomeScreen(
                devices = listOf(
                    DeviceStatus("RaceBox Micro", "RaceBox", "25 Hz", DeviceState.Ready),
                    DeviceStatus("GoPro HERO11", "GoPro 11", "Ready", DeviceState.Ready),
                    DeviceStatus("Phone camera", "Phone cam", "4K", DeviceState.Ready),
                    DeviceStatus("GoPro HERO8", "GoPro 8", "Start by hand", DeviceState.Off),
                ),
                trackName = "Tor Poznań",
                lastSession = "Yesterday · 14 laps · best 1:52.41",
                onStart = {},
            )
        }
    }
}
