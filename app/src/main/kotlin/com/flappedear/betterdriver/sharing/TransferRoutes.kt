package com.flappedear.betterdriver.sharing

import android.widget.Toast
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
import androidx.navigation.NavHostController
import com.flappedear.betterdriver.Routes
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.shell.SessionTransferState
import com.flappedear.betterdriver.shell.TrackTransferState
import com.flappedear.betterdriver.shell.TransferQuestionDialog
import com.flappedear.betterdriver.timing.track.Track

/** Any file: a bundle or track file has no registered type, and the app checks what it is. */
private val AnyFile = arrayOf("*/*")

/**
 * Share, save and import for the Sessions screen (FBBD-23). Share prepares the bundle, then opens
 * Android's share sheet; Save file (offered when Share cannot prepare it) and Import go through
 * Android's file picker.
 */
@Composable
internal fun rememberSessionTransfer(recording: Boolean): SessionTransferState {
    val context = LocalContext.current
    val transfers = remember(context) { Transfers.get(context) }
    val imports = remember(context) { Imports.get(context) }
    // Saveable: the activity can be re-created while the file picker is open.
    var saving by rememberSaveable { mutableStateOf<String?>(null) }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Transfers.MimeType)) {
        val id = saving
        saving = null
        if (it != null && id != null) transfers.saveSession(id, it)
    }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) imports.import(it)
    }
    return SessionTransferState(
        outgoing = transfers.outgoing
            .filterKeys { it.startsWith(Transfers.sessionKey("")) }
            .mapKeys { it.key.removePrefix(Transfers.sessionKey("")) },
        incoming = imports.state,
        importEnabled = !recording,
        onSend = { s -> transfers.sendSession(s.id) { uri -> share(context, uri, "Share session") } },
        onSave = { s ->
            saving = s.id
            pickTarget.launch(Transfers.bundleName(s.id))
        },
        onImport = { pickImport.launch(AnyFile) },
    )
}

/** Import for the Tracks screen (FBBD-80). */
@Composable
internal fun rememberTrackTransfer(): TrackTransferState {
    val context = LocalContext.current
    val imports = remember(context) { Imports.get(context) }
    val recorder = RecorderClient.get(context)
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) imports.import(it)
    }
    return TrackTransferState(
        incoming = imports.state,
        importEnabled = !(recorder.state.recording || recorder.state.starting),
        onImport = { pickImport.launch(AnyFile) },
    )
}

/**
 * Share in the track editor (FBBD-80, UI-56 option B): the saved track, through Android's share
 * sheet. The editor has no status line, so a failure to prepare the file shows as a toast.
 */
@Composable
internal fun rememberTrackShare(trackId: String): (Track) -> Unit {
    val context = LocalContext.current
    val transfers = remember(context) { Transfers.get(context) }
    val key = Transfers.trackKey(trackId)
    val state = transfers.outgoing[key]
    LaunchedEffect(state) {
        if (state?.failed == true) {
            Toast.makeText(context, "Share failed: ${state.text}", Toast.LENGTH_LONG).show()
            transfers.outgoing.remove(key) // shown once, not again when the editor reopens
        }
    }
    return { t -> transfers.sendTrack(t) { uri -> share(context, uri, "Share track") } }
}

/**
 * Asks keep or replace wherever the user is (only after they started an import, so never during an
 * outing), and shows Sessions when a file was opened with the app from Quick Share or a file manager.
 */
@Composable
internal fun FollowTransfers(nav: NavHostController, recorder: RecorderClient) {
    val context = LocalContext.current
    val imports = remember(context) { Imports.get(context) }
    val opened = imports.openedFromOutside
    LaunchedEffect(opened) {
        val current = nav.currentDestination?.route
        val recording = recorder.state.recording || recorder.state.starting
        val onShell = current != null && current in Routes.TopLevel && current != Routes.Sessions
        if (opened > 0 && !recording && onShell) {
            nav.navigate(Routes.Sessions) {
                popUpTo(Routes.Drive) { saveState = true }
                launchSingleTop = true
            }
        }
    }
    imports.question?.let { q -> TransferQuestionDialog(q, onDismiss = imports::dismissQuestion) }
}
