package com.flappedear.betterdriver.gopro

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Asks for the video files of one GoPro started by hand, then adds them to the session (UI-32).
 * Returns a function of the session id and the camera id.
 */
@Composable
internal fun rememberGoProFilePicker(goPros: GoPros): (String, String) -> Unit {
    // Session id and camera id, kept apart because rememberSaveable stores plain strings. Saveable:
    // the activity can be re-created while the file picker is open.
    var addingTo by rememberSaveable { mutableStateOf<String?>(null) }
    var addingFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = addingTo
        val camera = addingFrom
        addingTo = null
        addingFrom = null
        if (id != null && camera != null) goPros.addFiles(id, camera, uris)
    }
    return { id, camera ->
        addingTo = id
        addingFrom = camera
        pick.launch(arrayOf("video/mp4"))
    }
}
