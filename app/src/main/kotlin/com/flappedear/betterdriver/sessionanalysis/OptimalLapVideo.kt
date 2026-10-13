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
import com.flappedear.betterdriver.analysis.optimal.OptimalKind
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.videoexport.CompositeOptions
import com.flappedear.betterdriver.videoexport.OptimalVideoAction
import com.flappedear.betterdriver.videoexport.OptimalVideoSheet
import com.flappedear.betterdriver.videoexport.RealisticOptimalLap
import com.flappedear.betterdriver.videoexport.VideoExports
import com.flappedear.betterdriver.videoexport.fileDurations
import com.flappedear.betterdriver.videoexport.optimalOption
import com.flappedear.betterdriver.videoexport.optimalVideo
import com.flappedear.betterdriver.videoexport.videoBlocked
import com.flappedear.betterdriver.videoexport.videoExportFileName
import com.flappedear.betterdriver.videoexport.videoPartOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The composite lap video from the lap analysis (FBBD-163, FBBD-178), under the lap times: the
 * parts of [analysis]'s realistic optimal and raw theoretical best are looked for on the session's
 * video ([lapVideo]) off the main thread; the button opens the sheet that offers both (the
 * realistic optimal first) and lists the parts and what is missing, then the file picker, and the
 * export runs in [VideoExports] as a lap's does, its progress shown here and on the session's card.
 */
@Composable
internal fun OptimalLapVideo(sessionId: String, analysis: SessionAnalysis, lapVideo: LapVideo?) {
    val context = LocalContext.current
    val exports = remember(context) { VideoExports.get(context) }
    val recorder = remember(context) { RecorderClient.get(context) }
    val recording = recorder.state.recording || recorder.state.starting
    var options by remember(sessionId) { mutableStateOf<CompositeOptions?>(null) }
    var open by rememberSaveable(sessionId) { mutableStateOf(false) }
    var part by rememberSaveable(sessionId) { mutableStateOf(RealisticOptimalLap) }
    LaunchedEffect(analysis, lapVideo) {
        options = withContext(Dispatchers.IO) { compositeOptionsOf(analysis, lapVideo) }
    }
    val pickFile = rememberLauncherForActivityResult(CreateDocument(Mp4MimeType)) { uri ->
        if (uri != null) exports.start(sessionId, part, uri, recording)
    }
    val rendering = exports.running == sessionId
    OptimalVideoAction(
        options = options,
        state = exports.states[sessionId],
        rendering = rendering,
        onOpen = { open = true },
        onCancel = { exports.cancel(sessionId) },
    )
    val o = options
    if (open && o != null) {
        OptimalVideoSheet(
            options = o,
            blocked = videoBlocked(recording, busy = exports.running != null),
            onExport = { kind ->
                open = false
                part = videoPartOf(kind)
                pickFile.launch(videoExportFileName(sessionId, part))
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

/** What the composite laps' videos can be, read from the clip files; never fails the screen. */
@Suppress("TooGenericExceptionCaught")
private fun compositeOptionsOf(analysis: SessionAnalysis, lapVideo: LapVideo?): CompositeOptions = try {
    val video = lapVideo?.video
    val durations: (VideoClip) -> Long? = video?.let(::fileDurations) ?: { null }
    val films = optimalVideo(analysis, video, lapVideo?.originMicros ?: 0L, durations)
    CompositeOptions(films.realistic.option, films.raw.option)
} catch (e: Exception) {
    DebugLog.w("SessionAnalysis", "finding the composite laps on video failed", e)
    fun unreadable(kind: OptimalKind) =
        optimalOption(kind, null, null).copy(unavailable = "The session's video could not be read.")
    CompositeOptions(unreadable(OptimalKind.RealisticOptimal), unreadable(OptimalKind.RawTheoreticalBest))
}

private const val Mp4MimeType = "video/mp4"
