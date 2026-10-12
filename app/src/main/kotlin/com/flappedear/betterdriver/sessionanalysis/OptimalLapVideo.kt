package com.flappedear.betterdriver.sessionanalysis

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
import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.videoexport.OptimalLap
import com.flappedear.betterdriver.videoexport.OptimalOption
import com.flappedear.betterdriver.videoexport.OptimalVideoAction
import com.flappedear.betterdriver.videoexport.OptimalVideoSheet
import com.flappedear.betterdriver.videoexport.VideoExports
import com.flappedear.betterdriver.videoexport.fileDurations
import com.flappedear.betterdriver.videoexport.optimalOption
import com.flappedear.betterdriver.videoexport.optimalVideo
import com.flappedear.betterdriver.videoexport.videoBlocked
import com.flappedear.betterdriver.videoexport.videoExportFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The optimal lap's video from the lap analysis (FBBD-163), under the theoretical best: the parts
 * of [analysis]'s theoretical best are looked for on the session's video ([lapVideo]) off the main
 * thread; the button opens the sheet that lists them and what is missing, then the file picker,
 * and the export runs in [VideoExports] as a lap's does, its progress shown here and on the
 * session's card.
 */
@Composable
internal fun OptimalLapVideo(sessionId: String, analysis: SessionAnalysis, lapVideo: LapVideo?) {
    val context = LocalContext.current
    val exports = remember(context) { VideoExports.get(context) }
    val recorder = remember(context) { RecorderClient.get(context) }
    val recording = recorder.state.recording || recorder.state.starting
    var option by remember(sessionId) { mutableStateOf<OptimalOption?>(null) }
    var open by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(analysis, lapVideo) {
        option = withContext(Dispatchers.IO) { optimalOptionOf(analysis, lapVideo) }
    }
    val pickFile = rememberLauncherForActivityResult(CreateDocument(Mp4MimeType)) { uri ->
        if (uri != null) exports.start(sessionId, OptimalLap, uri, recording)
    }
    val rendering = exports.running == sessionId
    OptimalVideoAction(
        option = option,
        state = exports.states[sessionId],
        rendering = rendering,
        onOpen = { open = true },
        onCancel = { exports.cancel(sessionId) },
    )
    val o = option
    if (open && o != null) {
        OptimalVideoSheet(
            option = o,
            blocked = videoBlocked(recording, busy = exports.running != null),
            onExport = {
                open = false
                pickFile.launch(videoExportFileName(sessionId, OptimalLap))
            },
            onDismiss = { open = false },
        )
    }
}

/** [OptimalLapVideo] for the lap analysis, once [analysis] is there. */
internal fun optimalVideoSlot(
    sessionId: String,
    analysis: SessionAnalysis?,
    lapVideo: LapVideo?,
): (@Composable () -> Unit)? = analysis?.let { a -> @Composable { OptimalLapVideo(sessionId, a, lapVideo) } }

/** What the optimal lap's video can be, read from the clip files; never fails the screen. */
@Suppress("TooGenericExceptionCaught")
private fun optimalOptionOf(analysis: SessionAnalysis, lapVideo: LapVideo?): OptimalOption = try {
    val video = lapVideo?.video
    val durations: (VideoClip) -> Long? = video?.let(::fileDurations) ?: { null }
    optimalVideo(analysis, video, lapVideo?.originMicros ?: 0L, durations).option
} catch (e: Exception) {
    DebugLog.w("SessionAnalysis", "finding the optimal lap on video failed", e)
    optimalOption(null, null).copy(unavailable = "The session's video could not be read.")
}

private const val Mp4MimeType = "video/mp4"
