package com.flappedear.betterdriver.gopro

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.video.VideoSourceKind
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val CopyBuffer = 1 shl 20
private val GoProName = Regex("G[HXL](\\d{2})(\\d{4})\\.MP4", RegexOption.IGNORE_CASE)
private val PlainName = Regex("[A-Za-z0-9._-]+")

/**
 * Adds HERO8 files the user picked to a session (FBBD-22, UI-32). The HERO8 has no Open GoPro
 * support, so its files come from a card reader or the GoPro Quik app.
 *
 * Copies each file into `<session>/video`, then lines up each recording with [GoProImporter]:
 * files of one recording (chapters GH01xxxx, GH02xxxx, ...) together, in chapter order. A file
 * that cannot be placed in the session is reported and its copy deleted. Blocking; run it off the
 * main thread.
 */
internal class Hero8Import(private val context: Context) {

    fun sizeOf(uri: Uri): Long = query(uri, OpenableColumns.SIZE) { it.getLong(0) } ?: 0L

    /** Returns the problems; [onCopied] gets the bytes copied so far. */
    fun run(sessionDir: File, uris: List<Uri>, onCopied: (Long) -> Unit): List<String> {
        val videoDir = File(sessionDir, VideoFiles.DIR).apply { mkdirs() }
        val problems = mutableListOf<String>()
        val copies = mutableListOf<File>()
        var copied = 0L
        for ((i, uri) in uris.withIndex()) {
            val target = File(videoDir, plainName(displayName(uri), i))
            try {
                copy(uri, target) { n ->
                    copied += n
                    onCopied(copied)
                }
                copies += target
            } catch (e: IOException) {
                target.delete()
                problems += "${target.name}: ${e.message}"
            }
        }
        val placed = try {
            lineUp(sessionDir, copies, problems)
        } catch (e: IOException) {
            problems += "Could not read the session: ${e.message}"
            emptySet()
        } catch (e: IllegalStateException) {
            problems += "Could not read the session: ${e.message}"
            emptySet()
        }
        copies.filter { it.name !in placed }.forEach(File::delete)
        return problems
    }

    /** Imports [copies] recording by recording; returns the names of the files placed in the session. */
    private fun lineUp(sessionDir: File, copies: List<File>, problems: MutableList<String>): Set<String> {
        val data = SessionStore(sessionDir.parentFile ?: sessionDir).read(sessionDir)
        val importer = GoProImporter()
        val source = GoProImporter.Source(VideoSourceKind.GOPRO_HERO8, CameraIds.GOPRO_HERO8)
        val placed = mutableSetOf<String>()
        for (recording in byRecording(copies)) {
            val result = importer.import(sessionDir, data, recording, source)
            placed += result.clips.map { it.fileName }
            problems += result.problems
        }
        return placed
    }

    private fun copy(uri: Uri, target: File, onBytes: (Long) -> Unit) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
        input.use { src ->
            target.outputStream().use { out ->
                copyStream(src, out, onBytes)
                out.fd.sync()
            }
        }
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

private fun copyStream(src: InputStream, out: OutputStream, onBytes: (Long) -> Unit) {
    val buffer = ByteArray(CopyBuffer)
    while (true) {
        val n = src.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        onBytes(n.toLong())
    }
}

/** A safe file name for the [index]th picked file: its own name if it is a plain .MP4 name. */
internal fun plainName(name: String?, index: Int): String {
    val clean = name?.substringAfterLast('/')?.trim().orEmpty()
    val ok = clean.matches(PlainName) && clean.endsWith(".mp4", ignoreCase = true)
    return if (ok) clean else VideoFiles.liveName(CameraIds.GOPRO_HERO8, index + 1, "MP4")
}

/**
 * Groups GoPro files by recording, chapters in order: GH010031.MP4 and GH020031.MP4 are chapters 1
 * and 2 of recording 0031. Files with other names are each their own recording.
 */
internal fun byRecording(files: List<File>): List<List<File>> {
    fun part(f: File, group: Int) = GoProName.matchEntire(f.name)?.groupValues?.get(group).orEmpty()
    val (named, other) = files.partition { GoProName.matches(it.name) }
    val groups = named.groupBy { part(it, 2) }
        .toSortedMap()
        .values
        .map { group -> group.sortedBy { part(it, 1).toIntOrNull() ?: 0 } }
    return groups + other.map { listOf(it) }
}
