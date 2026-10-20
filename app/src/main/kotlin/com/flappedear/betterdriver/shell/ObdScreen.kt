package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.obd.ObdLinkState
import com.flappedear.betterdriver.obd.ObdReading
import com.flappedear.betterdriver.obd.ObdStatus
import com.flappedear.betterdriver.obd.valueText
import java.util.Locale

/** What the OBD page shows: whose connection it is, and that connection; null status while starting. */
data class ObdView(val recording: Boolean, val status: ObdStatus?)

/** Title of the OBD page and its row in Settings › Devices. */
const val ObdTitle = "OBD-II"

/**
 * The OBD-II page (FBBD-261, UI-104 B): the Wi-Fi adapter's connection and every value the car
 * sends, with how often each arrives. During a session it shows what the recorder receives; with no
 * session recording the page holds its own connection, so the adapter can be checked before driving.
 */
@Composable
fun ObdScreen(view: ObdView, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(ObdTitle, onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val status = view.status
            if (status == null) {
                Header(DeviceState.Off, "Not recorded", "This session does not record OBD-II (Demo GNSS).")
            } else {
                Header(dotState(status), obdHeaderTitle(view.recording, status), detail(status))
                if (status.readings.isNotEmpty()) {
                    SectionLabel("Values from the car", Modifier.padding(top = 8.dp, start = 4.dp))
                    ShellCard {
                        status.readings.forEachIndexed { i, r -> ReadingRow(r, divider = i > 0) }
                    }
                }
                DimText(note(view.recording, status), Modifier.padding(top = 8.dp, start = 4.dp))
            }
        }
    }
}

@Composable
private fun Header(state: DeviceState, title: String, detail: String) {
    ShellCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(state)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                DimText(detail)
            }
        }
    }
}

@Composable
private fun ReadingRow(r: ObdReading, divider: Boolean) {
    ListRow(r.name, divider = divider) {
        Text(r.valueText(), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
        Text(
            String.format(Locale.ROOT, "%.1f Hz", r.hz),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

private fun dotState(s: ObdStatus) = when (s.state) {
    ObdLinkState.Connected -> DeviceState.Ready
    ObdLinkState.Connecting, ObdLinkState.Searching -> DeviceState.Attention
}

internal fun obdHeaderTitle(recording: Boolean, s: ObdStatus): String = when (s.state) {
    ObdLinkState.Connected -> {
        val n = s.readings.size
        "${if (recording) "Recording" else "Connected"} · $n ${if (n == 1) "value" else "values"}"
    }
    ObdLinkState.Connecting -> "Connecting"
    ObdLinkState.Searching -> "No adapter"
}

private fun detail(s: ObdStatus): String =
    if (s.state == ObdLinkState.Searching) "${s.detail} · trying again" else s.detail

private fun note(recording: Boolean, s: ObdStatus): String = when {
    recording && s.state == ObdLinkState.Connected ->
        "Values as the recorder receives them. Only what the car answers is listed."
    recording -> "Recording carries on without OBD. Join the adapter's Wi-Fi and switch the ignition on."
    s.state == ObdLinkState.Connected ->
        "No session is recording, so this page connects to the adapter itself. It lets go when a session " +
            "starts, and the recorder takes over."
    else -> "Join the adapter's Wi-Fi (for example “WiFi_OBDII”) and switch the ignition on. No session is " +
        "recording, so this page connects to the adapter itself."
}

/** The OBD row's value in Settings › Devices: the recording's OBD state, or the adapter kind when idle. */
fun obdRowValue(recording: ObdStatus?): String = when (recording?.state) {
    ObdLinkState.Connected -> "Recording"
    ObdLinkState.Connecting -> "Connecting"
    ObdLinkState.Searching -> "No adapter"
    null -> "Wi-Fi adapter"
}

@Preview(widthDp = 411, heightDp = 914)
@Composable
private fun ObdScreenPreview() {
    BetterDriverTheme {
        ObdScreen(
            ObdView(
                recording = true,
                status = ObdStatus(
                    ObdLinkState.Connected,
                    "ELM327 v1.5 · ISO 15765-4 CAN (11 bit, 500 kbaud) · 12.4V",
                    listOf(
                        ObdReading("rpm", "RPM", "rpm", 2850.0, 9.1),
                        ObdReading("speed", "Speed", "km/h", 92.0, 9.0),
                        ObdReading("throttle", "Throttle", "%", 34.1, 9.0),
                    ),
                ),
            ),
            onBack = {},
        )
    }
}
