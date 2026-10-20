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

/** Title of the OBD page and its row in Settings › Devices. */
const val ObdTitle = "OBD-II"

/**
 * The OBD-II page (FBBD-261, UI-104 B): the listed adapter's connection (FBBD-121, UI-105 C) and every
 * value the car sends, with how often each arrives. During a session it shows what the recorder
 * receives; with no session recording the page holds its own connection, so the adapter can be
 * checked before driving. Texts name the adapter (UI-106 A).
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
            val adapter = view.adapter
            when {
                adapter == null -> {
                    Header(DeviceState.Off, "No adapter added", "Settings › Devices › Add equipment")
                    DimText(NoAdapterNote, Modifier.padding(top = 8.dp, start = 4.dp))
                }
                status == null -> Header(DeviceState.Off, "Not recorded", NotRecordedNote)
                else -> {
                    Header(dotState(status), obdHeaderTitle(view.recording, status), detail(status, adapter))
                    if (status.readings.isNotEmpty()) {
                        SectionLabel("Values from the car", Modifier.padding(top = 8.dp, start = 4.dp))
                        ShellCard {
                            status.readings.forEachIndexed { i, r -> ReadingRow(r, divider = i > 0) }
                        }
                    }
                    DimText(note(view.recording, status, adapter), Modifier.padding(top = 8.dp, start = 4.dp))
                }
            }
        }
    }
}

private const val NotRecordedNote = "This session does not record OBD-II (Demo GNSS)."

private const val NoAdapterNote = "Only adapters added as equipment read the car. Pair a Bluetooth adapter in " +
    "Android's Bluetooth settings first; a Bluetooth LE one is found by the search there. A Wi-Fi adapter " +
    "is added under Add without Bluetooth."

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

/** While no adapter answers, which one is not answering (UI-106 A); otherwise the connection's line. */
private fun detail(s: ObdStatus, adapter: ObdAdapterLabel): String =
    if (s.state == ObdLinkState.Searching) "${adapter.name} not answering · trying again" else s.detail

private fun note(recording: Boolean, s: ObdStatus, adapter: ObdAdapterLabel): String {
    val reach = if (adapter.wifi) {
        "join the adapter's Wi-Fi (for example “WiFi_OBDII”) and switch the ignition on"
    } else {
        "switch the ignition on and keep the ${adapter.name} in range"
    }
    return when {
        recording && s.state == ObdLinkState.Connected ->
            "Values as the recorder receives them. Only what the car answers is listed."
        recording -> "Recording carries on without OBD. To read the car, $reach; the ${adapter.name} is tried " +
            "again every 20 s."
        s.state == ObdLinkState.Connected ->
            "No session is recording, so this page connects to the adapter itself. It lets go when a session " +
                "starts, and the recorder takes over."
        else -> "${reach.replaceFirstChar { it.uppercase() }}. No session is recording, so this page connects to " +
            "the adapter itself."
    }
}

/**
 * The OBD row's value in Settings › Devices: the recording's OBD state, "Not connected" when idle,
 * or "None added" with no adapter listed (UI-105 C). The adapter itself shows under the row's title.
 */
fun obdRowValue(recording: ObdStatus?, adapter: ObdAdapterLabel?): String = when {
    adapter == null -> "None added"
    recording == null -> "Not connected"
    recording.state == ObdLinkState.Connected -> "Recording"
    recording.state == ObdLinkState.Connecting -> "Connecting"
    else -> "No adapter"
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
                    "vLinker MC+ · ELM327 v2.3 · ISO 15765-4 CAN (11 bit, 500 kbaud) · 12.4V",
                    listOf(
                        ObdReading("rpm", "RPM", "rpm", 2850.0, 9.1),
                        ObdReading("speed", "Speed", "km/h", 92.0, 9.0),
                        ObdReading("throttle", "Throttle", "%", 34.1, 9.0),
                    ),
                ),
                adapter = ObdAdapterLabel("vLinker MC+", "Bluetooth"),
            ),
            onBack = {},
        )
    }
}
