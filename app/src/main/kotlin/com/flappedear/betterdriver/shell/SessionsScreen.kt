package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.flappedear.betterdriver.recording.DeleteState
import com.flappedear.betterdriver.recording.ExportState
import com.flappedear.betterdriver.recording.sessionDetail
import com.flappedear.betterdriver.recording.sessionTitle
import com.flappedear.betterdriver.sharing.TransferState
import java.io.File
import java.util.Locale

/**
 * Saved sessions, newest first, each with VBO export (FBBD-66, FBBD-71), plus its video when it
 * has any (FBBD-25). A session recovered after a crash is marked and exports like any other. Built
 * from the approved shell parts; the layout is on the UI review page for approval.
 *
 * A finished session also shows its GoPro video (FBBD-22): Download for HERO11 files still on the
 * camera (UI-31) and Add for HERO8 files (UI-32). Both are off while a session records.
 *
 * Delete (FBBD-90, UI-48) asks first, saying how much space the session and its video free, then
 * removes both. It is off while the session records, exports or fetches GoPro video.
 *
 * Share sends the session with its video and track to the other device (FBBD-23, UI-55): Send
 * through the share sheet (Quick Share), or Save file. Import at the top brings one in.
 *
 * A session the coach spoke in opens its cue review (FBBD-99): each cue and whether the next pass
 * through that corner got better.
 */
@Composable
fun SessionsScreen(
    sessions: List<SessionSummary>,
    exports: Map<String, ExportState>,
    onExport: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
    withVideo: Set<String> = emptySet(),
    goPro: GoProCardState = GoProCardState(),
    deleting: SessionDeleting = SessionDeleting(),
    transfer: SessionTransferState = SessionTransferState(),
    coach: SessionCoach = SessionCoach(),
    header: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        ShellTopBar("Sessions") {
            TextButton(onClick = transfer.onImport, enabled = transfer.importEnabled) { Text("Import") }
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            header()
            ImportCard(transfer.incoming)
        }
        if (sessions.isEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp)) { ShellCard { DimText("No sessions yet") } }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sessions, key = { it.id }) { s ->
                    SessionCard(s, exports[s.id], s.id in withVideo, onExport, goPro, deleting, transfer, coach)
                }
            }
        }
    }
}

/** Cue counts of coached sessions, by id, and opening one's cue review (FBBD-99). */
data class SessionCoach(
    val cues: Map<String, Int> = emptyMap(),
    val onOpen: (SessionSummary) -> Unit = {},
)

/** Delete state and action for the whole Sessions list (FBBD-90). */
data class SessionDeleting(
    /** Bytes on disk of each session, video included. */
    val sizes: Map<String, Long> = emptyMap(),
    val states: Map<String, DeleteState> = emptyMap(),
    val onDelete: (SessionSummary) -> Unit = {},
)

@Suppress("LongParameterList")
@Composable
private fun SessionCard(
    s: SessionSummary,
    export: ExportState?,
    video: Boolean,
    onExport: (SessionSummary) -> Unit,
    goPro: GoProCardState,
    deleting: SessionDeleting,
    transfer: SessionTransferState,
    coach: SessionCoach,
) {
    val c = TrackTheme.colors
    val delete = deleting.states[s.id]
    val sending = transfer.outgoing[s.id]
    var asking by rememberSaveable(s.id) { mutableStateOf(false) }
    val idle = s.state != SessionState.RECORDING && export != ExportState.Running && delete != DeleteState.Running
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
        if (s.state != SessionState.RECORDING) GoProSection(s, export, goPro)
        CoachRow(s, coach)
        val (status, bad) = cardStatus(delete, export)
        val canDelete = idle && goPro.work[s.id] == null && sending?.running != true
        if (status.isNotEmpty()) DimText(status, color = if (bad) c.loss else Color.Unspecified)
        TransferLine(sending)
        CardButtons(
            video = video,
            idle = idle && sending?.running != true,
            canDelete = canDelete,
            onDelete = { asking = true },
            onSend = { transfer.onSend(s) },
            onSave = { transfer.onSave(s) },
            onExport = { onExport(s) },
        )
    }
    if (asking) {
        DeleteDialog(
            s,
            deleting.sizes[s.id],
            video,
            onDelete = {
                asking = false
                deleting.onDelete(s)
            },
            onDismiss = { asking = false },
        )
    }
}

/** "Coach review · 12 cues" on a session the coach spoke in (FBBD-99). */
@Composable
private fun CoachRow(s: SessionSummary, coach: SessionCoach) {
    val n = coach.cues[s.id] ?: return
    ListRow("Coach review", if (n == 1) "1 cue" else "$n cues", onClick = { coach.onOpen(s) })
}

/** Delete, Share and Export at the bottom of a session card. */
@Suppress("LongParameterList")
@Composable
private fun CardButtons(
    video: Boolean,
    idle: Boolean,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDelete, enabled = canDelete) {
            Text("Delete", color = if (canDelete) TrackTheme.colors.loss else Color.Unspecified)
        }
        ShareButton(enabled = idle, onSend = onSend, onSave = onSave)
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = onExport, enabled = idle) {
            Text(if (video) "Export VBO + video" else "Export VBO")
        }
    }
}

/** The card's status line and whether it reports a failure; a delete outranks an export. */
private fun cardStatus(delete: DeleteState?, export: ExportState?): Pair<String, Boolean> = when (delete) {
    DeleteState.Running -> "Deleting…" to false
    DeleteState.Recording -> "Recording, not deleted" to true
    DeleteState.Failed -> "Delete failed" to true
    null -> when (export) {
        ExportState.Running -> "Exporting…" to false
        ExportState.Done -> "Saved" to false
        ExportState.Failed -> "Export failed" to true
        null -> "" to false
    }
}

/** Confirms a delete, naming the session and what it frees (UI-48 option A). */
@Composable
private fun DeleteDialog(
    s: SessionSummary,
    bytes: Long?,
    video: Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = listOf(sessionTitle(s), s.info.track).filter { it.isNotBlank() }.joinToString(" · ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete session?") },
        text = { Text(deleteText(name, bytes, video)) },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = TrackTheme.colors.loss) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The delete question's text: what goes and how much space it frees. */
internal fun deleteText(title: String, bytes: Long?, video: Boolean): String {
    val what = if (video) "its data and video" else "its data"
    val size = bytes?.let { " (${sizeText(it)})" }.orEmpty()
    return "$title: $what$size will be removed from this phone. " +
        "Files still on a GoPro stay there. This can't be undone."
}

/** "820 KB", "350 MB", "2.4 GB". */
internal fun sizeText(bytes: Long): String = when {
    bytes >= GB -> String.format(Locale.ROOT, "%.1f GB", bytes / GB.toDouble())
    bytes >= MB -> "${bytes / MB} MB"
    else -> "${maxOf(1, bytes / KB)} KB"
}

private const val KB = 1_000L
private const val MB = 1_000_000L
private const val GB = 1_000_000_000L

@Composable
private fun GoProSection(s: SessionSummary, export: ExportState?, goPro: GoProCardState) {
    val work = goPro.work[s.id]
    GoProLines(
        goPro = goPro.sessions[s.id] ?: SessionGoPro(),
        work = work,
        problems = goPro.problems[s.id].orEmpty(),
        enabled = goPro.enabled && work == null && export != ExportState.Running,
        hero8Listed = goPro.hero8Listed,
        canDownload = goPro.canDownload,
        onDownload = { goPro.onDownload(s) },
        onAddHero8 = { goPro.onAddHero8(s) },
    )
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
                transfer = SessionTransferState(
                    outgoing = mapOf("b" to TransferState("Preparing…", 0.42f, running = true)),
                    incoming = TransferState("Imported the session of 11 Oct 10:42. Added track Tor Poznań."),
                ),
                withVideo = setOf("a"),
                goPro = GoProCardState(
                    sessions = mapOf(
                        "a" to SessionGoPro(hero11 = GoProClips(2, "by GPS ±0.05 s", byGps = true)),
                        "b" to SessionGoPro(hero11OnCamera = true),
                    ),
                ),
            )
        }
    }
}
