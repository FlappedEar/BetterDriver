package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.sharing.TransferQuestion
import com.flappedear.betterdriver.sharing.TransferState
import com.flappedear.betterdriver.timing.track.Track

/**
 * Share on a session card or a track row (UI-55 option A): one quiet button that opens a two-item
 * menu, Send (the share sheet, with Quick Share) and Save file (a file the user picks, for USB or a
 * file manager). Both carry the session's video and its track (FBBD-23, FBBD-80).
 */
@Composable
internal fun ShareButton(enabled: Boolean, onSend: () -> Unit, onSave: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text("Share") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Send (Quick Share)") },
                onClick = {
                    open = false
                    onSend()
                },
            )
            DropdownMenuItem(
                text = { Text("Save file") },
                onClick = {
                    open = false
                    onSave()
                },
            )
        }
    }
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

/** Send, save and import on the Tracks screen (FBBD-80). */
data class TrackTransferState(
    /** By track id. */
    val outgoing: Map<String, TransferState> = emptyMap(),
    val incoming: TransferState? = null,
    val importEnabled: Boolean = true,
    val onSend: (Track) -> Unit = {},
    val onSave: (Track) -> Unit = {},
    val onImport: () -> Unit = {},
)
