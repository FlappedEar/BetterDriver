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

/** A session's GoPro video, for its card (UI-31, UI-32). */
data class SessionGoPro(
    /** A HERO11 recorded with the session and its files are still on the camera. */
    val hero11OnCamera: Boolean = false,
    val hero11: GoProClips? = null,
    val hero8: GoProClips? = null,
)

/** GoPro state and actions for the whole Sessions list. */
data class GoProCardState(
    val sessions: Map<String, SessionGoPro> = emptyMap(),
    val work: Map<String, GoProWork> = emptyMap(),
    val problems: Map<String, List<String>> = emptyMap(),
    /** False while a session records: nothing is downloaded or copied then. */
    val enabled: Boolean = true,
    val onDownload: (SessionSummary) -> Unit = {},
    val onAddHero8: (SessionSummary) -> Unit = {},
)

/**
 * The GoPro lines of a session card, built to option A of UI-31 (tap Download) and UI-32 (add
 * HERO8 files from the session). [enabled] is false while a session records or other work runs.
 */
@Composable
internal fun GoProLines(
    goPro: SessionGoPro,
    work: GoProWork?,
    problems: List<String>,
    enabled: Boolean,
    onDownload: () -> Unit,
    onAddHero8: () -> Unit,
) {
    val c = TrackTheme.colors
    when {
        work is GoProWork.Downloading -> {
            val file = "GoPro HERO11 · downloading file ${work.index}"
            ValueLine(c.warning, file, if (work.total > 0) gb(work.bytes, work.total) else null)
            Progress(work.bytes, work.total)
            DimText("Keep the camera on and close to the phone.")
        }
        goPro.hero11OnCamera -> StatusLine(c.warning, "GoPro HERO11 · video on the camera") {
            FilledTonalButton(onClick = onDownload, enabled = enabled) { Text("Download") }
        }
        goPro.hero11 != null -> ClipsLine("GoPro HERO11", goPro.hero11)
    }
    when {
        work is GoProWork.Adding -> {
            ValueLine(c.warning, "Copying and lining up ${files(work.files)}", null)
            Progress(work.bytes, work.total)
        }
        goPro.hero8 != null -> ClipsLine("GoPro HERO8", goPro.hero8)
        else -> StatusLine(MaterialTheme.colorScheme.outline, "GoPro HERO8 video") {
            OutlinedButton(onClick = onAddHero8, enabled = enabled) { Text("Add") }
        }
    }
    problems.take(MaxProblems).forEach { DimText(it, color = c.loss) }
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
