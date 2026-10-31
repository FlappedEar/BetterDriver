package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ble.RaceBoxDiagnosticsText
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme

/**
 * RaceBox diagnostics (UI-39 option A): every value the RaceBox reports, in one live list. While a
 * session records, only what the recorder receives (UI-40 option B).
 * The screen only reads; it never changes a RaceBox setting.
 */
@Composable
fun RaceBoxDiagnosticsScreen(view: DiagnosticsView, onBack: () -> Unit, onCalibrate: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(RaceBoxDiagnosticsTitle, onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (view) {
                is DiagnosticsView.Recording -> Recording(view)
                DiagnosticsView.NoPermission -> Notice(
                    "Bluetooth access needed",
                    "Allow Nearby devices for BetterDriver in Android settings to connect to the RaceBox.",
                )
                is DiagnosticsView.Live -> Live(view, onCalibrate)
            }
        }
    }
}

@Composable
private fun Notice(title: String, text: String) {
    ShellCard {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        DimText(text)
    }
}

@Composable
private fun Recording(view: DiagnosticsView.Recording) {
    ShellCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(TrackTheme.colors.loss, CircleShape))
            Text("Recording. Showing what the recorder receives.", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (view.rows.isNotEmpty()) {
        SectionLabel("From the recording", Modifier.padding(top = 8.dp, start = 4.dp))
        ShellCard { view.rows.forEachIndexed { i, row -> ListRow(row.label, row.value, divider = i > 0) } }
    }
    DimText("Everything else is shown when no session is recording.", Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun Live(view: DiagnosticsView.Live, onCalibrate: () -> Unit) {
    ShellCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(view.header.state)
            Column {
                Text(
                    view.header.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                DimText(view.header.detail)
            }
        }
    }
    view.sections.forEach { section ->
        SectionLabel(section.title, Modifier.padding(top = 8.dp, start = 4.dp))
        ShellCard {
            section.rows.forEachIndexed { i, row -> ListRow(row.label, row.value, divider = i > 0) }
            section.note?.let { DimText(it) }
            section.action?.let { label ->
                FilledTonalButton(onClick = onCalibrate, enabled = section.actionEnabled) { Text(label) }
            }
        }
    }
    if (view.sections.isNotEmpty()) DimText(DiagNotReported, Modifier.padding(top = 8.dp, start = 4.dp))
}

@Preview(widthDp = 411, heightDp = 914)
@Composable
private fun RaceBoxDiagnosticsPreview() {
    BetterDriverTheme {
        RaceBoxDiagnosticsScreen(
            DiagnosticsView.Live(
                DiagHeader(DeviceState.Ready, "Connected · 25 Hz", "RaceBox Mini S 3241 · 3D · 18 satellites"),
                listOf(
                    DiagSection("GNSS", listOf(DiagRow("Fix", "3D · OK"), DiagRow("Satellites", "18"))),
                    DiagSection("Power", listOf(DiagRow("Input voltage", "12.1 V"))),
                ),
            ),
            onBack = {},
        )
    }
}

/**
 * The screen's title: the RaceBox's live values, not Settings › Diagnostics (the app's log), so the
 * two are never one name.
 */
const val RaceBoxDiagnosticsTitle = RaceBoxDiagnosticsText.TITLE
