package com.flappedear.betterdriver

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
import androidx.navigation.NavHostController
import com.flappedear.betterdriver.feature.track.editor.TrackEditorScreen
import com.flappedear.betterdriver.feature.track.editor.TrackEditorState
import com.flappedear.betterdriver.shell.ShellIcons
import com.flappedear.betterdriver.shell.TracksScreen
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
        onCreateFromCircuit = { c -> tracks.save(newTrackFrom(c), openNew) },
        onCreateHere = { name, at -> tracks.save(newTrackAt(at, name), openNew) },
        onBack = { nav.popBackStack() },
    )
}

/** The track editor (FBBD-56); asks before dropping unsaved lines. */
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
    val state = remember(loaded) { TrackEditorState(loaded.track, loaded.sessions) }
    var leaving by remember { mutableStateOf(false) }
    val back: () -> Unit = {
        when {
            state.selected != null -> state.cancel()
            state.changed -> leaving = true
            else -> nav.popBackStack()
        }
    }
    BackHandler(onBack = back)
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
    )
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Discard changes?") },
            text = { Text("The lines you moved are not saved.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    nav.popBackStack()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Keep editing") } },
        )
    }
}
