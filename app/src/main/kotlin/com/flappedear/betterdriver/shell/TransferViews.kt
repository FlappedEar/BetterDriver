package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.sharing.TransferQuestion
import com.flappedear.betterdriver.sharing.TransferState
import com.flappedear.betterdriver.sharing.bytesText
import com.flappedear.betterdriver.sharing.sizeText
import com.flappedear.betterdriver.sharing.timeLeftText
import com.flappedear.betterdriver.transfer.ImportProgress

/**
 * Share on a session card (FBBD-23): prepares the session as one file, with its video and its
 * track, then opens Android's own share sheet (Quick Share, Files, Drive and the rest). Arek asked
 * for the system sharing options instead of an app menu (UI-55, 2026-10-11).
 */
@Composable
internal fun ShareButton(enabled: Boolean, onShare: () -> Unit) {
    TextButton(onClick = onShare, enabled = enabled) { Text("Share") }
}

/**
 * Offered only when Share could not prepare the file (usually too little free space for the copy
 * the share sheet needs): Save file writes straight to a file the user picks, with no copy.
 */
@Composable
internal fun SaveInstead(state: TransferState?, onSave: () -> Unit) {
    if (state?.failed == true) TextButton(onClick = onSave) { Text("Save file instead") }
}

/** Progress or result of a send, save or import, as one line; a bar while it runs. */
@Composable
internal fun TransferLine(state: TransferState?) {
    if (state == null) return
    val fraction = state.fraction
    if (state.running && fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else if (state.running) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    DimText(state.text, color = if (state.failed) TrackTheme.colors.loss else Color.Unspecified)
}

/** The current or last import, at the top of Sessions and Tracks (UI-194 A). */
@Composable
internal fun ImportCard(state: TransferState?) {
    if (state == null) return
    ShellCard {
        SectionLabel("Import")
        val progress = state.progress
        if (state.running && progress != null) ImportProgressLines(progress, state.fileName) else TransferLine(state)
    }
}

/**
 * How far an import is (FBBD-314): with several files, which one and all their bytes on a bar of
 * its own (UI-193 A); then the file's name, its bar, its bytes and percent, and the time left
 * (UI-192 B). A file of unknown size gets a moving bar and the bytes read.
 */
@Composable
private fun ImportProgressLines(p: ImportProgress.Snapshot, fileName: String) {
    if (p.files > 1) {
        val total = if (p.totalSize > 0) "${sizeText(p.totalDone)} of ${sizeText(p.totalSize)}" else ""
        EndsRow("File ${p.file + 1} of ${p.files}", total)
        ImportBar(p.totalFraction, 8.dp)
    }
    if (fileName.isNotBlank()) {
        Text(
            fileName,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    ImportBar(p.fileFraction, if (p.files > 1) 5.dp else 8.dp)
    EndsRow(bytesText(p.fileDone, p.fileSize), timeLeftText(p.secondsLeft).orEmpty())
}

@Composable
private fun ImportBar(fraction: Float?, height: Dp) {
    val modifier = Modifier.fillMaxWidth().height(height)
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

/** [start] on the left, [end] dimmed on the right. */
@Composable
private fun EndsRow(start: String, end: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(start, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (end.isNotEmpty()) DimText(end)
    }
}

/** On the card of a session imported from another app's file (FBBD-164): where it came from. */
@Composable
internal fun ImportedLine(info: SessionInfo) {
    importedFrom(info)?.let { DimText(it) }
}

/** "From lap-12.vbo (VBO)" for a session imported from another app's file (FBBD-164), else null. */
internal fun importedFrom(info: SessionInfo): String? = info.imported?.let { o ->
    if (o.fileName.isBlank()) "From a ${o.format} file" else "From ${o.fileName} (${o.format})"
}

/** Keep or replace, for a session or track that is already on this device. */
@Composable
internal fun TransferQuestionDialog(question: TransferQuestion, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(question.title) },
        text = { Text(question.text) },
        confirmButton = { TextButton(onClick = { question.answer(true) }) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { question.answer(false) }) { Text("Keep") } },
    )
}

/** Send, save and import on the Sessions screen (FBBD-23). */
data class SessionTransferState(
    /** By session id. */
    val outgoing: Map<String, TransferState> = emptyMap(),
    val incoming: TransferState? = null,
    /** False while a session records: nothing is imported then, and that session cannot be sent. */
    val importEnabled: Boolean = true,
    val onSend: (SessionSummary) -> Unit = {},
    val onSave: (SessionSummary) -> Unit = {},
    val onImport: () -> Unit = {},
)

/** Import on the Tracks screen (FBBD-80); a track is shared from its editor (UI-56 option B). */
data class TrackTransferState(
    val incoming: TransferState? = null,
    val importEnabled: Boolean = true,
    val onImport: () -> Unit = {},
)
