package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.sharing.TransferState
import com.flappedear.betterdriver.transfer.BackupSummary
import com.flappedear.betterdriver.transfer.BackupTexts

/**
 * Back up and Restore in Settings › App (FBBD-156): two rows like the others in the card, and the
 * progress or result of the last one as a line under them. Both are off while a session records.
 */
@Composable
internal fun BackupRowsView(recording: Boolean, state: TransferState?, onBackUp: () -> Unit, onRestore: () -> Unit) {
    val idle = !recording && state?.running != true
    ListRow("Back up", if (recording) "Not while recording" else "To a file", onClick = onBackUp.takeIf { idle })
    ListRow("Restore", if (recording) "Not while recording" else "From a file", onClick = onRestore.takeIf { idle })
    TransferLine(state)
}

/** Before the file picker: what goes in, and the "Include sessions" choice with their count and size. */
@Composable
internal fun BackupDialog(
    sessions: Pair<Int, Long>?,
    onBackUp: (includeSessions: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var include by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(BackupTexts.WhatGoesIn)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = include, role = Role.Checkbox, onValueChange = { include = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = include, onCheckedChange = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Include sessions (without video)")
                        DimText(sessionsOptionText(sessions))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onBackUp(include) }) { Text("Choose file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The restore sheet: when the checked backup was made and what it holds, then Merge (recommended,
 * the filled button) or Replace, which asks once more. Closing it changes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RestoreSheet(summary: BackupSummary, onMerge: () -> Unit, onReplace: () -> Unit, onDismiss: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Restore backup", style = MaterialTheme.typography.titleLarge)
            DimText(backupMadeText(summary))
            ShellCard {
                backupContentRows(summary).forEachIndexed { i, (label, value) ->
                    ListRow(label, value, divider = i > 0)
                }
            }
            DimText(BackupTexts.MergeOrReplace)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { confirming = true }, modifier = Modifier.weight(1f)) {
                    Text("Replace…")
                }
                Button(onClick = onMerge, modifier = Modifier.weight(1f)) { Text("Merge") }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Replace with the backup?") },
            text = {
                Text(BackupTexts.ReplaceWarning)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onReplace()
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Shown instead of the app when a restore the app was stopped in could not be finished at start
 * (FBBD-156): the data is part restored, so nothing reads it until Try again finishes it.
 */
@Composable
internal fun RestoreUnfinished(onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("The restore could not be finished", style = MaterialTheme.typography.titleLarge)
        DimText(
            "BetterDriver stopped while restoring a backup, and finishing it failed, often because the " +
                "phone is out of space. The app stays closed until it is finished, so old and restored " +
                "data never mix. Free some space, then try again.",
        )
        Button(onClick = onRetry) { Text("Try again") }
    }
}
