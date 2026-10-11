package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.gopro.GoProWork
import com.flappedear.betterdriver.recorder.store.SessionSummary
import java.util.Locale

private const val BytesPerGb = 1_000_000_000.0

/** Lined-up clips of one GoPro in a session: how many, and how they were placed ("by GPS ±0.05 s"). */
data class GoProClips(val count: Int, val how: String, val byGps: Boolean)

/**
 * One GoPro's video in a session. [sourceId] is the camera (CameraIds.goPro); [onCamera] is true
 * while an Open GoPro camera still holds files it recorded with the session.
 */
data class SessionCamera(val sourceId: String, val onCamera: Boolean = false, val clips: GoProClips? = null)

/** A session's GoPro video, for its card (UI-31, UI-32), one entry per camera that has any. */
data class SessionGoPro(val cameras: List<SessionCamera> = emptyList())

/**
 * A GoPro in the equipment list, as the session cards need it: its name, and whether the app
 * downloads its files ([download], Open GoPro) or Arek adds them (a camera started by hand).
 */
data class ListedGoPro(val sourceId: String, val name: String, val download: Boolean)

/** GoPro state and actions for the whole Sessions list. */
data class GoProCardState(
    val sessions: Map<String, SessionGoPro> = emptyMap(),
    val work: Map<String, GoProWork> = emptyMap(),
    val problems: Map<String, List<String>> = emptyMap(),
    /** False while a session records: nothing is downloaded or copied then. */
    val enabled: Boolean = true,
    /** The GoPros in the equipment list (FBBD-83, FBBD-105); only these offer Download or Add. */
    val listed: List<ListedGoPro> = emptyList(),
    /** The name to show for a camera id that is no longer listed, such as one from an older session. */
    val nameOf: (String) -> String = { "GoPro" },
    val onDownload: (SessionSummary, String) -> Unit = { _, _ -> },
    val onAddFiles: (SessionSummary, String) -> Unit = { _, _ -> },
)

/**
 * The GoPro lines of a session card, built to option A of UI-31 (tap Download) and UI-32 (add a
 * GoPro's files from the session), one line per camera. A camera with video in the session shows
 * its state; each listed camera started by hand that has none yet offers Add. Download needs the
 * camera to be listed. [enabled] is false while a session records or other work runs.
 */
@Suppress("LongParameterList")
@Composable
internal fun GoProLines(
    goPro: SessionGoPro,
    work: GoProWork?,
    problems: List<String>,
    enabled: Boolean,
    listed: List<ListedGoPro>,
    nameOf: (String) -> String,
    onDownload: (String) -> Unit,
    onAddFiles: (String) -> Unit,
) {
    val c = TrackTheme.colors
    val withVideo = goPro.cameras.map { it.sourceId }.toSet()
    for (cam in goPro.cameras) {
        val name = listed.firstOrNull { it.sourceId == cam.sourceId }?.name ?: nameOf(cam.sourceId)
        val canDownload = listed.any { it.sourceId == cam.sourceId && it.download }
        CameraLine(cam, name, work, enabled, canDownload) { onDownload(cam.sourceId) }
    }
    for (cam in listed.filter { !it.download && it.sourceId !in withVideo }) {
        if (work is GoProWork.Adding && work.sourceId == cam.sourceId) {
            ValueLine(c.warning, "${cam.name} · copying and lining up ${files(work.files)}", null)
            Progress(work.bytes, work.total)
        } else {
            StatusLine(MaterialTheme.colorScheme.outline, "${cam.name} video") {
                OutlinedButton(onClick = { onAddFiles(cam.sourceId) }, enabled = enabled) { Text("Add") }
            }
        }
    }
    problems.take(MaxProblems).forEach { DimText(it, color = c.loss) }
}

/** One camera's video in the session: downloading, still on the camera, or lined up. */
@Suppress("LongParameterList")
@Composable
private fun CameraLine(
    cam: SessionCamera,
    name: String,
    work: GoProWork?,
    enabled: Boolean,
    canDownload: Boolean,
    onDownload: () -> Unit,
) {
    val c = TrackTheme.colors
    when {
        work is GoProWork.Downloading && work.sourceId == cam.sourceId -> {
            val size = if (work.total > 0) gb(work.bytes, work.total) else null
            ValueLine(c.warning, "$name · downloading file ${work.index}", size)
            Progress(work.bytes, work.total)
            DimText("Keep the camera on and close to the phone.")
        }
        cam.onCamera -> StatusLine(c.warning, "$name · video on the camera") {
            if (canDownload) FilledTonalButton(onClick = onDownload, enabled = enabled) { Text("Download") }
        }
        cam.clips != null -> ClipsLine(name, cam.clips)
    }
}

@Composable
private fun ClipsLine(camera: String, clips: GoProClips) {
    val c = TrackTheme.colors
    ValueLine(if (clips.byGps) c.gain else c.warning, "$camera · ${clipsText(clips.count)} lined up", clips.how)
}

@Composable
private fun ValueLine(dot: Color, text: String, value: String?) = StatusLine(dot, text) {
    if (value != null) DimText(value)
}

@Composable
private fun StatusLine(dot: Color, text: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Progress(bytes: Long, total: Long) {
    Column(Modifier.fillMaxWidth()) {
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private const val MaxProblems = 3

private fun gb(bytes: Long, total: Long) =
    String.format(Locale.ROOT, "%.1f / %.1f GB", bytes / BytesPerGb, total / BytesPerGb)

private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

private fun clipsText(n: Int) = if (n == 1) "1 clip" else "$n clips"
