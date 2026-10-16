package com.flappedear.betterdriver.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.recording.ExportFormat

/**
 * Export opens a short menu of formats (FBBD-150): VBO for Circuit Tools and other VBO readers,
 * plus VBO + video into a folder when the session has video (FBBD-25), CSV with every channel for
 * spreadsheets, GPX for maps. Choosing one asks where to save, as VBO export always did. A session
 * with video also offers Video with data ([dataVideo], FBBD-152): a lap or the whole video with the
 * data drawn in, chosen in a picker.
 */
@Composable
internal fun ExportMenu(
    video: Boolean,
    enabled: Boolean,
    onExport: (ExportFormat) -> Unit,
    dataVideo: DataVideoItem? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = enabled) { Text("Export ▾") }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            exportChoices(video).forEach { choice ->
                MenuLine(choice.label, choice.detail) {
                    open = false
                    onExport(choice.format)
                }
            }
            if (video && dataVideo != null) {
                MenuLine("Video with data", dataVideo.detail, dataVideo.enabled) {
                    open = false
                    dataVideo.onClick()
                }
            }
        }
    }
}

@Composable
private fun MenuLine(label: String, detail: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                DimText(detail)
            }
        },
        onClick = onClick,
        enabled = enabled,
    )
}

/** The Video with data line of the Export menu; off while a session records or another video renders. */
internal data class DataVideoItem(val enabled: Boolean, val detail: String, val onClick: () -> Unit)

/** What the Video with data line says under its name. */
internal fun dataVideoDetail(recording: Boolean, busy: Boolean): String = when {
    recording -> "Not while a session is recording"
    busy -> "Another video is being made"
    else -> "MP4 of a lap or all of it, with speed, times, G and map"
}

/** One line of the Export menu. */
internal data class ExportChoice(val format: ExportFormat, val label: String, val detail: String)

/**
 * The Export menu's lines, VBO first as the format most used. A session with video offers the plain
 * VBO too, one file without video columns (FBBD-227): before, VBO + video was its only VBO line.
 */
internal fun exportChoices(video: Boolean) = buildList {
    if (video) add(ExportChoice(ExportFormat.VboVideo, "VBO + video", "Circuit Tools, into a new folder"))
    add(ExportChoice(ExportFormat.Vbo, "VBO", if (video) "Data only, one file" else "Circuit Tools"))
    add(ExportChoice(ExportFormat.Csv, "CSV", "Every channel and lap, for spreadsheets"))
    add(ExportChoice(ExportFormat.Gpx, "GPX", "GPS track by lap, for maps"))
}
