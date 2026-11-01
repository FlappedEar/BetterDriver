package com.flappedear.betterdriver.videoexport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.shell.DimText
import com.flappedear.betterdriver.shell.HandleRow
import com.flappedear.betterdriver.shell.PartList
import com.flappedear.betterdriver.shell.VideoExportLine

/**
 * A session's Video tab (FBBD-392), as the iOS app's: Video with data in sight rather than in the
 * card's export menu. The parts of Video with data (FBBD-152): the whole session, the composite
 * laps, each lap with its handle; Watch plays the picked part in the app (FBBD-341), Save video
 * renders it to a file the phone picks. [onWatch] gets the part and its handle seconds.
 */
@Suppress("LongMethod") // One tab: the parts, the handle, the progress and the two buttons.
@Composable
fun SessionVideoTab(sessionId: String, title: String, onWatch: (VideoPart, Int) -> Unit) {
    val context = LocalContext.current
    val exports = remember(context) { VideoExports.get(context) }
    var options by remember(sessionId) { mutableStateOf<VideoLoad>(VideoLoad.Loading) }
    LaunchedEffect(sessionId) {
        exports.options(sessionId) { o -> options = o?.let { VideoLoad.Ready(it) } ?: VideoLoad.Unreadable }
    }
    val ready = (options as? VideoLoad.Ready)?.options
    // Kept across Watch and back: the default is set once the parts are read, only if nothing was picked.
    var picked by rememberSaveable(sessionId) { mutableStateOf<VideoPart?>(null) }
    LaunchedEffect(ready) {
        if (picked == null) picked = ready?.preselected
    }
    var handle by rememberSaveable { mutableStateOf(DefaultLapHandleSeconds) }
    // "<part>:<handle seconds>" until a file is picked.
    var saving by rememberSaveable { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(CreateDocument(Mp4MimeType)) { uri ->
        val target = saving?.let { decodeTarget("$sessionId:$it") }
        saving = null
        // No recording runs here: one opens the track screen (FollowRecording) and stops any export.
        if (uri != null && target != null) {
            exports.start(sessionId, target.part, uri, recording = false, handleSeconds = target.handleSeconds)
        }
    }
    val rendering = exports.running == sessionId
    val busy = exports.running != null
    Column(Modifier.fillMaxWidth().testTag(VideoTabTag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DimText(
            "$title. Speed, lap time, delta to your best lap, G and the track map are drawn over the video " +
                "on this phone.",
        )
        Parts(options, picked) { picked = it }
        // A part to act on: read, and one picked.
        val part = picked.takeIf { ready != null }
        if (part != null && isSingleLap(part)) HandleRow(handle) { handle = it }
        VideoExportLine(exports.states[sessionId], rendering) { exports.cancel(sessionId) }
        VideoTabButtons(
            canSave = part != null && !busy,
            canWatchPart = part != null,
            onSave = {
                if (part != null) {
                    saving = "$part:${handleFor(part, handle)}"
                    pickFile.launch(videoExportFileName(sessionId, part, ready?.pointToPoint == true))
                }
            },
            onWatch = { if (part != null) onWatch(part, handleFor(part, handle)) },
        )
    }
}

/** The parts to pick from once read; while reading, or when the video cannot be read, says so. */
@Composable
private fun Parts(options: VideoLoad, picked: VideoPart?, onPick: (VideoPart) -> Unit) {
    when (options) {
        VideoLoad.Loading -> {
            DimText("Reading laps and video…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        VideoLoad.Unreadable -> DimText("This session's video could not be read.", color = TrackTheme.colors.loss)
        is VideoLoad.Ready -> PartList(options.options, picked, onPick)
    }
}

/** Save video and Watch, at the end of the tab. */
@Composable
private fun VideoTabButtons(canSave: Boolean, canWatchPart: Boolean, onSave: () -> Unit, onWatch: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        OutlinedButton(onClick = onSave, enabled = canSave, modifier = Modifier.testTag(SaveVideoTag)) {
            Text("Save video")
        }
        Button(onClick = onWatch, enabled = canWatchPart, modifier = Modifier.testTag(WatchVideoTag)) {
            Text("Watch")
        }
    }
}

/** The Video tab's parts: being read, read, or the session's video could not be read. */
private sealed interface VideoLoad {
    data object Loading : VideoLoad
    data object Unreadable : VideoLoad
    class Ready(val options: VideoExportOptions) : VideoLoad
}

const val VideoTabTag = "session-video-tab"
const val SaveVideoTag = "session-video-save"
const val WatchVideoTag = "session-video-watch"
