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
 * Export opens a short menu of formats (FBBD-150): VBO (with its video when the session has any)
 * for Circuit Tools, CSV with every channel for spreadsheets, GPX for maps. Choosing one asks
 * where to save, as VBO export always did.
 */
@Composable
internal fun ExportMenu(video: Boolean, enabled: Boolean, onExport: (ExportFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = enabled) { Text("Export ▾") }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            exportChoices(video).forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                            DimText(choice.detail)
                        }
                    },
                    onClick = {
                        open = false
                        onExport(choice.format)
                    },
                )
            }
        }
    }
}

/** One line of the Export menu. */
internal data class ExportChoice(val format: ExportFormat, val label: String, val detail: String)

/** The Export menu's lines, VBO first as the format most used. */
internal fun exportChoices(video: Boolean) = listOf(
    if (video) {
        ExportChoice(ExportFormat.Vbo, "VBO + video", "Circuit Tools, into a new folder")
    } else {
        ExportChoice(ExportFormat.Vbo, "VBO", "Circuit Tools")
    },
    ExportChoice(ExportFormat.Csv, "CSV", "Every channel and lap, for spreadsheets"),
    ExportChoice(ExportFormat.Gpx, "GPX", "GPS track by lap, for maps"),
)
