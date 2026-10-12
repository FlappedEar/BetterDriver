package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.sharing.TransferQuestion
import com.flappedear.betterdriver.sharing.TransferState

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

/** The current or last import, at the top of Sessions and Tracks. */
@Composable
internal fun ImportCard(state: TransferState?) {
    if (state == null) return
    ShellCard {
        SectionLabel("Import")
        TransferLine(state)
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
