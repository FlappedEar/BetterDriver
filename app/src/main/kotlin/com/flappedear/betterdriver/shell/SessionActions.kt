package com.flappedear.betterdriver.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.DeleteState
import com.flappedear.betterdriver.recording.ExportFormat
import com.flappedear.betterdriver.recording.ExportState
import com.flappedear.betterdriver.recording.sessionDetail
import com.flappedear.betterdriver.recording.sessionTitle
import java.util.Locale

/**
 * Everything a session offers, in the sheet its ⋮ opens (UI-139 C): its GoPro video, the coach's
 * review, watching its video (FBBD-341), export, share and delete, with their progress. The title
 * opens lap analysis, as the row does.
 */
@Suppress("LongParameterList")
@Composable
internal fun SessionActions(
    s: SessionSummary,
    export: ExportState?,
    video: Boolean,
    onExport: (SessionSummary, ExportFormat) -> Unit,
    goPro: GoProCardState,
    deleting: SessionDeleting,
    transfer: SessionTransferState,
    onAnalyse: (SessionSummary) -> Unit,
    coach: SessionCoach,
    videoExport: SessionVideoExport,
) {
    val c = TrackTheme.colors
    val delete = deleting.states[s.id]
    val sending = transfer.outgoing[s.id]
    var asking by rememberSaveable(s.id) { mutableStateOf(false) }
    // A video with data being rendered reads the session's files: nothing else may change them meanwhile.
    val rendering = videoExport.running == s.id
    val idle = s.state != SessionState.RECORDING && export != ExportState.Running && delete != DeleteState.Running &&
        !rendering
    val opens = opensAnalysis(s, delete)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.clickable(enabled = opens, onClickLabel = "Lap analysis") { onAnalyse(s) }) {
            CardTitle(s, opens)
        }
        val detail = sessionDetail(s)
        if (detail.isNotEmpty()) DimText(detail)
        ImportedLine(s.info)
        val busy = sending?.running == true || rendering
        if (s.state != SessionState.RECORDING) GoProSection(s, export, goPro, sharing = busy)
        SessionCoachRow(s, coach)
        val (status, bad) = cardStatus(delete, export)
        // A share copies the session folder, so it must not run while GoPro video is being added, and the
        // other way round (GoProSection).
        val free = idle && goPro.work[s.id] == null && sending?.running != true
        if (status.isNotEmpty()) DimText(status, color = if (bad) c.loss else Color.Unspecified)
        TransferLine(sending)
        if (free) SaveInstead(sending) { transfer.onSave(s) }
        VideoExportLine(videoExport.states[s.id], rendering) { videoExport.onCancel(s) }
        // Any session with video, with or without laps (FBBD-341): a commute's video can be watched too.
        if (video && s.state != SessionState.RECORDING) {
            FilledTonalButton(
                onClick = { videoExport.onWatch(s) },
                enabled = delete != DeleteState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Watch video") }
        }
        CardButtons(
            video = video,
            idle = free,
            canDelete = free,
            onDelete = { asking = true },
            onShare = { transfer.onSend(s) },
            onExport = { format -> onExport(s, format) },
            dataVideo = videoExport.menuItem { videoExport.onExport(s) },
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

/** Lap analysis opens for a session, except while it records or is being deleted (UI-61 B). */
internal fun opensAnalysis(s: SessionSummary, delete: DeleteState?): Boolean =
    s.state != SessionState.RECORDING && delete != DeleteState.Running

/** The session's title, its state and, when the card opens lap analysis, a chevron. */
@Composable
private fun CardTitle(s: SessionSummary, opens: Boolean) {
    val c = TrackTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            sessionTitle(s),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        when (s.state) {
            SessionState.RECORDING -> DimText("Recording", color = c.loss)
            SessionState.RECOVERED, SessionState.INTERRUPTED -> DimText("Recovered", color = c.warning)
            SessionState.FINISHED -> if (s.info.imported != null) DimText("Imported")
        }
        if (opens) DimText("›")
    }
}

/** Delete, Share and Export at the bottom of a session card. */
@Suppress("LongParameterList")
@Composable
private fun CardButtons(
    video: Boolean,
    idle: Boolean,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    dataVideo: DataVideoItem,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDelete, enabled = canDelete) {
            Text("Delete", color = if (canDelete) TrackTheme.colors.loss else Color.Unspecified)
        }
        ShareButton(enabled = idle, onShare = onShare)
        Spacer(Modifier.weight(1f))
        ExportMenu(video = video, enabled = idle, onExport = onExport, dataVideo = dataVideo)
    }
}

/** The card's status line and whether it reports a failure; a delete outranks an export. */
internal fun cardStatus(delete: DeleteState?, export: ExportState?): Pair<String, Boolean> = when (delete) {
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
private fun GoProSection(s: SessionSummary, export: ExportState?, goPro: GoProCardState, sharing: Boolean) {
    val work = goPro.work[s.id]
    GoProLines(
        goPro = goPro.sessions[s.id] ?: SessionGoPro(),
        work = work,
        problems = goPro.problems[s.id].orEmpty(),
        enabled = goPro.enabled && work == null && export != ExportState.Running && !sharing,
        listed = goPro.listed,
        nameOf = goPro.nameOf,
        onDownload = { id -> goPro.onDownload(s, id) },
        onAddFiles = { id -> goPro.onAddFiles(s, id) },
    )
}
