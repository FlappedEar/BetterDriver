package com.flappedear.betterdriver.recording

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.recorder.store.SessionSummary

/**
 * Export from a session card: asks where to save the VBO, CSV or GPX file, then [Sessions] writes it
 * in the background (FBBD-150). VBO + video asks for a folder instead, because the VBO and its
 * video files must sit side by side (FBBD-25); plain VBO is one file, with or without video.
 */
@Composable
fun rememberSessionExport(sessions: Sessions): (SessionSummary, ExportFormat) -> Unit {
    // Saveable: the activity can be re-created while the file picker is open.
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    fun picked(uri: Uri?, export: (String, Uri) -> Unit) {
        val id = exporting
        exporting = null
        if (uri != null && id != null) export(id, uri)
    }
    // One picker per format: the document type is fixed when the picker is made.
    val pickVbo = rememberLauncherForActivityResult(CreateDocument(ExportFormat.Vbo.mimeType)) { uri ->
        picked(uri) { id, to -> sessions.export(id, to, ExportFormat.Vbo) }
    }
    val pickCsv = rememberLauncherForActivityResult(CreateDocument(ExportFormat.Csv.mimeType)) { uri ->
        picked(uri) { id, to -> sessions.export(id, to, ExportFormat.Csv) }
    }
    val pickGpx = rememberLauncherForActivityResult(CreateDocument(ExportFormat.Gpx.mimeType)) { uri ->
        picked(uri) { id, to -> sessions.export(id, to, ExportFormat.Gpx) }
    }
    val pickFolder = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        picked(uri, sessions::exportWithVideo)
    }
    return { s, format ->
        exporting = s.id
        val name = exportFileName(s, format)
        when (format) {
            ExportFormat.Vbo -> pickVbo.launch(name)
            ExportFormat.VboVideo -> pickFolder.launch(null)
            ExportFormat.Csv -> pickCsv.launch(name)
            ExportFormat.Gpx -> pickGpx.launch(name)
        }
    }
}
