package com.flappedear.betterdriver

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.feature.track.editor.TrackEditorScreen
import com.flappedear.betterdriver.feature.track.editor.TrackEditorState
import com.flappedear.betterdriver.shell.ShellIcons
import com.flappedear.betterdriver.shell.TracksScreen
import com.flappedear.betterdriver.sharing.rememberTrackShare
import com.flappedear.betterdriver.sharing.rememberTrackTransfer
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.newTrackAt
import com.flappedear.betterdriver.timing.track.newTrackFrom
import com.flappedear.betterdriver.tracks.EditorData
import com.flappedear.betterdriver.tracks.Tracks

/** Tracks (UI-27 option A): where you are, then your tracks; creating one opens the editor. */
@Composable
internal fun TracksRoute(nav: NavHostController, tracks: Tracks) {
    LaunchedEffect(Unit) {
        tracks.refresh()
        tracks.locate()
    }
    val openNew: (Track) -> Unit = { t -> nav.navigate(Routes.trackEditor(t.id)) }
    TracksScreen(
        here = tracks.here,
        tracks = tracks.list,
        onOpen = { t -> nav.navigate(Routes.trackEditor(t.id)) },
        onCreateFromCircuit = { c -> tracks.save(newTrackFrom(c), onSaved = openNew) },
        onCreateHere = { name, at -> tracks.save(newTrackAt(at, name), onSaved = openNew) },
        onBack = { nav.popBackStack() },
        transfer = rememberTrackTransfer(),
    )
}

/**
 * The track editor (FBBD-56); asks before dropping unsaved lines, or a track proposed from a session
 * (FBBD-151), and before deleting a saved track (FBBD-157).
 */
@Composable
internal fun TrackEditorRoute(nav: NavHostController, tracks: Tracks, id: String) {
    var data by remember(id) { mutableStateOf<EditorData?>(null) }
    var missing by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { tracks.openEditor(id) { d -> if (d == null) missing = true else data = d } }
    val loaded = data
    if (missing) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val state = remember(loaded) { TrackEditorState(loaded.track, loaded.sessions, loaded.stored, loaded.proposed) }
    var leaving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (removing) {
        // Nothing to tap while the delete runs, so a Save cannot bring the track back.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val back: () -> Unit = {
        when {
            state.selected != null -> state.cancel()
            state.changed -> leaving = true
            else -> nav.popBackStack()
        }
    }
    BackHandler(onBack = back)
    val share = rememberTrackShare(id)
    TrackEditorScreen(
        state = state,
        sessions = loaded.sessions,
        tiles = tracks.tiles,
        backIcon = ShellIcons.Back,
        onSave = {
            val sent = state.track
            tracks.save(sent) { stored -> state.saved(sent, stored) }
        },
        onBack = back,
        onShare = { share(state.track) },
        // A track proposed from a session is not stored yet: there is nothing to delete.
        onDelete = if (state.proposed) null else ({ deleting = true }),
    )
    if (deleting) {
        DeleteTrackDialog(state.track.displayName, onDismiss = { deleting = false }) {
            removing = true
            deleteAndLeave(nav, tracks, id, context) { removing = false }
        }
    }
    if (leaving) DiscardDialog(state.proposed, onDiscard = { nav.popBackStack() }, onDismiss = { leaving = false })
}

/** Deletes the track and leaves the editor, or says the delete failed and calls [onFailed]. */
private fun deleteAndLeave(
    nav: NavHostController,
    tracks: Tracks,
    id: String,
    context: Context,
    onFailed: () -> Unit,
) {
    val editor = nav.currentBackStackEntry
    tracks.delete(id) { gone ->
        if (!gone) {
            Toast.makeText(context, "Delete failed", Toast.LENGTH_LONG).show()
            onFailed()
        } else if (nav.currentBackStackEntry == editor) {
            // Only while the editor still shows, so a Back pressed meanwhile is not doubled.
            nav.popBackStack()
        }
    }
}

/** Asks before dropping unsaved lines, or a track [proposed] from a session; either button closes it. */
@Composable
private fun DiscardDialog(proposed: Boolean, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard changes?") },
        text = {
            val what = if (proposed) "The track made from your session is" else "The lines you moved are"
            Text("$what not saved.")
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onDiscard()
            }) { Text("Discard") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep editing") } },
    )
}

/** Confirms deleting a track (FBBD-157), like a session delete (UI-48 option A); either button closes it. */
@Composable
private fun DeleteTrackDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete track?") },
        text = { Text(deleteTrackText(name)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onDelete()
            }) { Text("Delete", color = TrackTheme.colors.loss) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The delete question's text: what goes and what stays. */
internal fun deleteTrackText(name: String): String =
    "$name: its lines and coach reference will be removed from this phone. " +
        "Sessions recorded there stay. This can't be undone."
