package com.flappedear.betterdriver.sharing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.shell.BackupDialog
import com.flappedear.betterdriver.shell.BackupRowsView
import com.flappedear.betterdriver.shell.RestoreSheet
import com.flappedear.betterdriver.shell.backupFileName
import com.flappedear.betterdriver.transfer.RestoreMode

/**
 * Settings › App › Back up and Restore (FBBD-156). Back up asks whether to include sessions, then
 * Android's file picker for where to write; Restore picks a file, checks it and shows the restore
 * sheet. The work runs in [Backups], off the main thread.
 */
@Composable
fun BackupRows() {
    val context = LocalContext.current
    val backups = remember(context) { Backups.get(context) }
    val recorder = RecorderClient.get(context)
    val recording = recorder.state.recording || recorder.state.starting
    var asking by rememberSaveable { mutableStateOf(false) }
    // Saveable: the activity can be re-created while the file picker is open.
    var includeSessions by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var sessions by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Transfers.MimeType)) {
        val include = includeSessions
        includeSessions = null
        if (it != null && include != null) backups.backUp(it, include)
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) backups.open(it)
    }
    LaunchedEffect(asking) {
        if (asking) backups.sessionsSize { sessions = it }
    }
    BackupRowsView(
        recording = recording,
        state = backups.state,
        onBackUp = { asking = true },
        onRestore = { pickBackup.launch(arrayOf("*/*")) },
    )
    if (asking) {
        BackupDialog(
            sessions = sessions,
            onBackUp = { include ->
                asking = false
                includeSessions = include
                pickTarget.launch(backupFileName(System.currentTimeMillis()))
            },
            onDismiss = { asking = false },
        )
    }
    backups.offer?.let { offer ->
        RestoreSheet(
            summary = offer.summary,
            onMerge = { backups.restore(RestoreMode.MERGE) },
            onReplace = { backups.restore(RestoreMode.REPLACE) },
            onDismiss = backups::cancel,
        )
    }
}
