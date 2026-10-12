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
 * video can be made.
 */
@Composable
fun VideoExportFlow(
    sessions: List<SessionSummary>,
    recording: Boolean,
    content: @Composable (SessionVideoExport) -> Unit,
) {
    val context = LocalContext.current
    val exports = remember(context) { VideoExports.get(context) }
    // Saveable: the activity can be re-created while the sheet or the file picker is open.
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf<String?>(null) } // "<id>:<part>" until a file is picked
    var options by remember { mutableStateOf<Pair<String, VideoExportOptions?>?>(null) }
    val pickFile = rememberLauncherForActivityResult(CreateDocument(Mp4MimeType)) { uri ->
        val target = saving?.let(::decodeTarget)
        saving = null
        if (uri != null && target != null) exports.start(target.first, target.second, uri, recording)
    }
    content(
        SessionVideoExport(
            states = exports.states,
            running = exports.running,
            recording = recording,
            onExport = { s -> picking = s.id },
            onCancel = { s -> exports.cancel(s.id) },
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
        onExport = { part ->
            picking = null
            saving = "$id:$part"
            pickFile.launch(videoExportFileName(session.id, part, loaded?.second?.pointToPoint == true))
        },
        onDismiss = { picking = null },
    )
}

/** "<id>:<part>" back to the session id and part. */
private fun decodeTarget(text: String): Pair<String, VideoPart>? {
    val i = text.lastIndexOf(':')
    val part = text.substring(i + 1).toIntOrNull()
    return if (i <= 0 || part == null) null else text.substring(0, i) to part
}

private const val Mp4MimeType = "video/mp4"
