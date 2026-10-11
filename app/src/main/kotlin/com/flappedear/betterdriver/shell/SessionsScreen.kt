package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.ExportState
import com.flappedear.betterdriver.recording.sessionDetail
import com.flappedear.betterdriver.recording.sessionTitle
import java.io.File

/**
 * Saved sessions, newest first, each with VBO export (FBBD-66, FBBD-71). A session recovered after
 * a crash is marked and exports like any other. Built from the approved shell parts; the layout is
 * on the UI review page for approval.
 */
@Composable
fun SessionsScreen(
    sessions: List<SessionSummary>,
    exports: Map<String, ExportState>,
    onExport: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ShellTopBar("Sessions")
        if (sessions.isEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp)) { ShellCard { DimText("No sessions yet") } }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sessions, key = { it.id }) { s -> SessionCard(s, exports[s.id], onExport) }
            }
        }
    }
}

@Composable
private fun SessionCard(s: SessionSummary, export: ExportState?, onExport: (SessionSummary) -> Unit) {
    val c = TrackTheme.colors
    ShellCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                sessionTitle(s),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            when (s.state) {
                SessionState.RECORDING -> DimText("Recording", color = c.loss)
                SessionState.RECOVERED, SessionState.INTERRUPTED -> DimText("Recovered", color = c.warning)
                SessionState.FINISHED -> Unit
            }
        }
        val detail = sessionDetail(s)
        if (detail.isNotEmpty()) DimText(detail)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DimText(
                when (export) {
                    ExportState.Running -> "Exporting…"
                    ExportState.Done -> "Saved"
                    ExportState.Failed -> "Export failed"
                    null -> ""
                },
                modifier = Modifier.weight(1f),
                color = if (export == ExportState.Failed) c.loss else Color.Unspecified,
            )
            FilledTonalButton(
                onClick = { onExport(s) },
                enabled = s.state != SessionState.RECORDING && export != ExportState.Running,
            ) { Text("Export VBO") }
        }
    }
}

@Preview(widthDp = 411, heightDp = 860)
@Composable
private fun SessionsScreenPreview() {
    fun session(id: String, start: Long, state: SessionState, minutes: Long?) = SessionSummary(
        id,
        File(id),
        SessionInfo(start, car = "2023 Toyota Corolla", track = "Tor Poznań"),
        state,
        minutes?.let { it * 60_000_000 },
    )
    BetterDriverTheme(darkTheme = true) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            SessionsScreen(
                sessions = listOf(
                    session("c", 1_760_190_000_000, SessionState.RECORDING, null),
                    session("b", 1_760_180_000_000, SessionState.RECOVERED, 12),
                    session("a", 1_760_170_000_000, SessionState.FINISHED, 18),
                ),
                exports = mapOf("a" to ExportState.Done),
                onExport = {},
            )
        }
    }
}
