package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val CopyBuffer = 1 shl 20
private const val StagingPrefix = ".import-"
private const val PreviousSuffix = ".previous"

/** A stale staging folder (the app was killed during an import) is removed after this long. */
private const val StaleStagingMillis = 60L * 60 * 1000

/** A GoPro file name, after the camera prefix the import adds ([plainName]): chapter, then recording number. */
private val GoProName = Regex("(?:.*-)?G[HXL](\\d{2})(\\d{4})\\.MP4", RegexOption.IGNORE_CASE)
private val PlainName = Regex("[A-Za-z0-9._-]+")

/** A file the user picked: its name as the picker gave it, if any, and a way to read it. */
class PickedFile(val name: String?, val open: () -> InputStream)

/**
 * Adds files the user picked for one camera to a session (FBBD-22, UI-32, FBBD-105), without ever
 * putting a video the session already has at risk (Audit A01).
 *
 * Each file is copied into a staging folder of its own inside `<session>/video`, under the name it will
 * have there, and lined up with [GoProImporter] while still staged: files of one recording (chapters
 * GH01xxxx, GH02xxxx, ...) together, in chapter order. Only a file placed in the session replaces the
 * session's file of that name, and the index is written after it; the earlier file is put back if
 * that fails. A copy that breaks off or a file that cannot be placed leaves the session's files and
 * index as they were. Picking the same GoPro file again replaces the earlier copy only once the new
 * one is placed; a second file of the same name in one pick is refused, and a file with no usable
 * name gets a number no other file of the session has. Blocking; run it off the main thread.
 *
 * [sourceId] is the camera ([CameraIds.goPro]); copies are named after it, so files of two cameras
 * with the same name never meet.
 */
class FileImport(
    private val sessionDir: File,
    private val sourceId: String,
    private val kind: VideoSourceKind = VideoSourceKind.GOPRO_FILES,
    private val importer: GoProImporter = GoProImporter(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val readSession: () -> SessionData = {
        SessionStore(sessionDir.parentFile ?: sessionDir).read(sessionDir)
    },
) {
    /** Returns the problems; [onCopied] gets the bytes copied so far. */
    fun run(files: List<PickedFile>, onCopied: (Long) -> Unit = {}): List<String> {
        val videoDir = File(sessionDir, VideoFiles.DIR).apply { mkdirs() }
        removeStaleStaging(videoDir)
        val staging = File(videoDir, StagingPrefix + UUID.randomUUID()).apply { mkdirs() }
        try {
            val problems = mutableListOf<String>()
            val staged = stage(videoDir, staging, files, problems, onCopied)
            val clips = try {
                lineUp(staged, problems)
            } catch (e: IOException) {
                problems += "Could not read the session: ${e.message}"
                emptyList()
            } catch (e: IllegalStateException) {
                problems += "Could not read the session: ${e.message}"
                emptyList()
            }
            if (clips.isNotEmpty()) {
                try {
                    commit(videoDir, staging, clips)
                } catch (e: IOException) {
                    problems += "Could not add the files to the session: ${e.message}"
                }
            }
            return problems
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Copies [files] into [staging] under their names in the session; returns the copies that are whole. */
    private fun stage(
        videoDir: File,
        staging: File,
        files: List<PickedFile>,
        problems: MutableList<String>,
        onCopied: (Long) -> Unit,
    ): List<File> {
        val existing = videoDir.list().orEmpty().toSet()
        val taken = mutableSetOf<String>()
        val staged = mutableListOf<File>()
        var copied = 0L
        for ((i, picked) in files.withIndex()) {
            val name = sessionName(picked.name, i, existing, taken)
            if (name == null) {
                problems += "${picked.name}: another picked file has the same name; not added"
                continue
            }
            taken += name
            val target = File(staging, name)
            try {
                copy(picked, target) { n ->
                    copied += n
                    onCopied(copied)
                }
                staged += target
            } catch (e: IOException) {
                target.delete()
                problems += "$name: ${e.message}"
            }
        }
        return staged
    }

    /**
     * The file's name in the session: its own GoPro-style name after the camera's id, or, for a file
     * with no usable name, the first number no file of the session or of this pick has. Null when
     * another file of this pick already has that name.
     */
    private fun sessionName(name: String?, index: Int, existing: Set<String>, taken: Set<String>): String? {
        val plain = plainName(sourceId, name, index)
        if (plain == VideoFiles.liveName(sourceId, index + 1, "MP4")) {
            return generateSequence(1) { it + 1 }
                .map { VideoFiles.liveName(sourceId, it, "MP4") }
                .first { it !in existing && it !in taken }
        }
        return plain.takeIf { it !in taken }
    }

    /** Places the staged files recording by recording; returns the clips placed in the session. */
    private fun lineUp(staged: List<File>, problems: MutableList<String>): List<VideoClip> {
        if (staged.isEmpty()) return emptyList()
        val data = readSession()
        val source = GoProImporter.Source(kind, sourceId)
        return byRecording(staged).flatMap { recording ->
            val result = importer.place(data, recording, source)
            problems += result.problems
            result.clips
        }
    }

    /**
     * Moves the placed files into the session, keeping each file they replace in [staging] until the
     * index is written; on any failure the replaced files are put back and the new ones taken out.
     */
    private fun commit(videoDir: File, staging: File, clips: List<VideoClip>) {
        val moved = mutableListOf<Pair<File, File?>>()
        try {
            for (clip in clips) {
                val target = File(videoDir, clip.fileName)
                val previous = File(staging, clip.fileName + PreviousSuffix).takeIf { target.exists() }
                previous?.let { move(target, it) }
                moved += target to previous
                move(File(staging, clip.fileName), target)
            }
            VideoIndex(sessionDir).putAll(clips)
        } catch (e: IOException) {
            for ((target, previous) in moved.asReversed()) {
                if (previous != null && previous.exists()) {
                    move(previous, target)
                } else {
                    target.delete()
                }
            }
            throw e
        }
    }

    private fun removeStaleStaging(videoDir: File) {
        videoDir.listFiles { f -> f.isDirectory && f.name.startsWith(StagingPrefix) }.orEmpty()
            .filter { nowMillis() - it.lastModified() > StaleStagingMillis }
            .forEach { it.deleteRecursively() }
    }
}

private fun move(from: File, to: File) {
    try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun copy(picked: PickedFile, target: File, onBytes: (Long) -> Unit) {
    picked.open().use { src ->
        target.outputStream().use { out ->
            copyStream(src, out, onBytes)
            out.fd.sync()
        }
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

/**
 * A safe file name for the [index]th file picked for camera [sourceId]: its own name after the
 * camera's id if it is a plain .MP4 name, else a numbered name.
 */
fun plainName(sourceId: String, name: String?, index: Int): String {
    val clean = name?.substringAfterLast('/')?.trim().orEmpty()
    val ok = clean.matches(PlainName) && clean.endsWith(".mp4", ignoreCase = true)
    return if (ok) "$sourceId-$clean" else VideoFiles.liveName(sourceId, index + 1, "MP4")
}

/**
 * Groups GoPro files by recording, chapters in order: GH010031.MP4 and GH020031.MP4 (with or
 * without the camera prefix) are chapters 1 and 2 of recording 0031. Files with other names are
 * each their own recording.
 */
fun byRecording(files: List<File>): List<List<File>> {
    fun part(f: File, group: Int) = GoProName.matchEntire(f.name)?.groupValues?.get(group).orEmpty()
    val (named, other) = files.partition { GoProName.matches(it.name) }
    val groups = named.groupBy { part(it, 2) }
        .toSortedMap()
        .values
        .map { group -> group.sortedBy { part(it, 1).toIntOrNull() ?: 0 } }
    return groups + other.map { listOf(it) }
}
