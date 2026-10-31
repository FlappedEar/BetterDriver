package com.flappedear.betterdriver.videoexport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.sessionTitle
import com.flappedear.betterdriver.shell.SessionVideoExport
import com.flappedear.betterdriver.shell.VideoExportSheet

/**
 * Video with data from a session card (FBBD-152): the picker sheet (whole session or a lap), then
 * where to save the MP4, then [VideoExports] renders it in the background. [content] is the Sessions
 * screen, given the card state and actions. [recording]: a session is recording or starting, so no
 * video can be made. [onWatch] opens a session's video in the app instead (FBBD-341).
 */
@Composable
fun VideoExportFlow(
    sessions: List<SessionSummary>,
    recording: Boolean,
    onWatch: (SessionSummary) -> Unit,
    content: @Composable (SessionVideoExport) -> Unit,
) {
    val context = LocalContext.current
    val exports = remember(context) { VideoExports.get(context) }
    // Saveable: the activity can be re-created while the sheet or the file picker is open.
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    // "<id>:<part>:<handle seconds>" until a file is picked
    var saving by rememberSaveable { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<Pair<String, VideoExportOptions?>?>(null) }
    val pickFile = rememberLauncherForActivityResult(CreateDocument(Mp4MimeType)) { uri ->
        val target = saving?.let(::decodeTarget)
        saving = null
        if (uri != null && target != null) exports.start(target.id, target.part, uri, recording, target.handleSeconds)
    }
    content(
        SessionVideoExport(
            states = exports.states,
            running = exports.running,
            recording = recording,
            onExport = { s -> picking = s.id },
            onCancel = { s -> exports.cancel(s.id) },
            onWatch = onWatch,
        ),
    )
    val id = picking ?: return
    LaunchedEffect(id) {
        if (options?.first != id) options = null
        exports.options(id) { o -> if (picking == id) options = id to o }
    }
    val session = sessions.firstOrNull { it.id == id }
    if (session == null || recording) {
        // Deleted meanwhile, or a recording started: the sheet closes.
        LaunchedEffect(Unit) { picking = null }
        return
    }
    val loaded = options?.takeIf { it.first == id }
    VideoExportSheet(
        title = sessionTitle(session),
        loading = loaded == null,
        options = loaded?.second,
        onExport = { part, handleSeconds ->
            picking = null
            saving = "$id:$part:$handleSeconds"
            pickFile.launch(videoExportFileName(session.id, part, loaded?.second?.pointToPoint == true))
        },
        onDismiss = { picking = null },
    )
}

/** What to export once the file is picked: session [id], its [part], a lap with [handleSeconds] before and after. */
internal data class VideoExportTarget(val id: String, val part: VideoPart, val handleSeconds: Int)

/** "<id>:<part>:<handle seconds>" back to the target. */
internal fun decodeTarget(text: String): VideoExportTarget? {
    val j = text.lastIndexOf(':')
    val i = if (j > 0) text.lastIndexOf(':', j - 1) else -1
    val part = if (i > 0) text.substring(i + 1, j).toIntOrNull() else null
    val handle = text.substring(j + 1).toIntOrNull()
    return if (part == null || handle == null) null else VideoExportTarget(text.substring(0, i), part, handle)
}

internal const val Mp4MimeType = "video/mp4"
