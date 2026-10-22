package com.flappedear.betterdriver.gopro

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.flappedear.betterdriver.video.VideoSourceKind
import java.io.File
import java.io.IOException

/**
 * Adds files the user picked for one GoPro to a session (FBBD-22, UI-32, FBBD-105): a camera
 * without Open GoPro (HERO8 and earlier, the first MAX) or any GoPro started by hand. Its files
 * come from a card reader or the GoPro Quik app. Another action camera's MP4 files are added the same
 * way ([kind] CAMERA_FILES, FBBD-120); with no GoPro GPS in them, they are placed by the camera's
 * clock. [sourceId] is the camera ([CameraIds.goPro]).
 *
 * The copying, lining up and replacing is [FileImport]'s: a file is staged and placed before it
 * replaces anything in the session (Audit A01). Blocking; run it off the main thread.
 */
internal class GoProFileImport(
    private val context: Context,
    private val sourceId: String,
    private val kind: VideoSourceKind = VideoSourceKind.GOPRO_FILES,
) {

    fun sizeOf(uri: Uri): Long = query(uri, OpenableColumns.SIZE) { it.getLong(0) } ?: 0L

    /** Returns the problems; [onCopied] gets the bytes copied so far. */
    fun run(sessionDir: File, uris: List<Uri>, onCopied: (Long) -> Unit): List<String> {
        val files = uris.map { uri ->
            PickedFile(displayName(uri)) {
                context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
            }
        }
        return FileImport(sessionDir, sourceId, kind).run(files, onCopied)
    }

    private fun displayName(uri: Uri): String? = query(uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }

    private fun <T> query(uri: Uri, column: String, read: (Cursor) -> T): T? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) read(c) else null
        }
    } catch (_: SecurityException) {
        null
    }
}
