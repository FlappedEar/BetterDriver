@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.gopro.CameraFile
import com.flappedear.betterdriver.gopro.GoProPlacer
import com.flappedear.betterdriver.gopro.PlacementLog
import com.flappedear.betterdriver.gopro.Probe
import com.flappedear.betterdriver.gopro.byRecording
import com.flappedear.betterdriver.gopro.plainName
import com.flappedear.betterdriver.recorder.log.logInfo
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.video.ClipIndexJson
import com.flappedear.betterdriver.video.IoProblem
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.sync.GpsAligner
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSString
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970

/** A file the user picked: where the app can read it now, and its name as Files gave it. */
class PickedCameraFile(val path: String, val name: String?)

/**
 * Adds files the user picked for one camera to a session on the iPhone (FBBD-343), as Android's
 * FileImport does (FBBD-22, UI-32, FBBD-105), never putting a video the session already has at risk
 * (Audit A01).
 *
 * Each file is copied into a staging folder of its own inside `<session>/video`, under the name it
 * will have there, and lined up with [GoProPlacer] while still staged: files of one recording
 * (chapters GH01xxxx, GH02xxxx, ...) together, in chapter order. Only a file placed in the session
 * replaces the session's file of that name, and the clip index (clips.json, the Android format) is
 * written after it; the earlier file is put back if that fails. A journal of the clips is written
 * first, so an import killed half-way is finished or undone by a later one. Blocking; the app runs
 * it off the main thread.
 */
class GoProImport(private val sessionsRoot: String) {
    /**
     * Adds [picked] to session [sessionId] as camera [sourceId] of [kind]; returns the problems, one
     * line each. [onCopied] gets the bytes copied so far. Never throws: anything unexpected ends as a
     * problem, so the import always ends.
     */
    @Suppress("TooGenericExceptionCaught") // See the comment above: the import must always end.
    fun run(
        sessionId: String,
        sourceId: String,
        kind: VideoSourceKind,
        picked: List<PickedCameraFile>,
        onCopied: (Long) -> Unit,
    ): List<String> = try {
        runChecked(sessionId, sourceId, kind, picked, onCopied)
    } catch (e: Exception) {
        logInfo(GoProTag, "adding files to $sessionId failed: $e")
        listOf("Could not add the files: ${e.message ?: e::class.simpleName}")
    }

    private fun runChecked(
        sessionId: String,
        sourceId: String,
        kind: VideoSourceKind,
        picked: List<PickedCameraFile>,
        onCopied: (Long) -> Unit,
    ): List<String> {
        val sessionDir = "$sessionsRoot/$sessionId"
        if (!isDirectory(sessionDir)) return listOf("The session is no longer on this phone")
        val videoDir = "$sessionDir/${VideoFiles.DIR}"
        files.createDirectoryAtPath(videoDir, true, null, null)
        recoverStaging(videoDir)
        val staging = "$videoDir/$StagingPrefix${NSUUID().UUIDString}"
        files.createDirectoryAtPath(staging, true, null, null)
        try {
            val problems = mutableListOf<String>()
            val staged = stage(videoDir, staging, sourceId, picked, problems, onCopied)
            val clips = try {
                lineUp(sessionId, staged, kind, sourceId, problems)
            } catch (e: IoProblem) {
                problems += "Could not read the session: ${e.message}"
                emptyList()
            } catch (e: IllegalStateException) {
                problems += "Could not read the session: ${e.message}"
                emptyList()
            }
            if (clips.isNotEmpty()) {
                try {
                    commit(videoDir, staging, clips)
                } catch (e: IoProblem) {
                    problems += "Could not add the files to the session: ${e.message}"
                }
            }
            return problems
        } finally {
            files.removeItemAtPath(staging, null)
        }
    }

    /** Copies [picked] into [staging] under their names in the session; returns the copies that are whole. */
    @Suppress("LongParameterList")
    private fun stage(
        videoDir: String,
        staging: String,
        sourceId: String,
        picked: List<PickedCameraFile>,
        problems: MutableList<String>,
        onCopied: (Long) -> Unit,
    ): List<String> {
        val existing = list(videoDir).toSet()
        val taken = mutableSetOf<String>()
        val staged = mutableListOf<String>()
        var copied = 0L
        for ((i, file) in picked.withIndex()) {
            val name = sessionName(sourceId, file.name, i, existing, taken)
            if (name == null) {
                problems += "${file.name}: another picked file has the same name; not added"
                continue
            }
            taken += name
            val target = "$staging/$name"
            try {
                copyFile(file.path, target) { n ->
                    copied += n
                    onCopied(copied)
                }
                staged += target
            } catch (e: IoProblem) {
                files.removeItemAtPath(target, null)
                problems += "$name: ${e.message}"
            }
        }
        return staged
    }

    /** As Android's: its own GoPro-style name after the camera's id, or the first free number. */
    private fun sessionName(
        sourceId: String,
        name: String?,
        index: Int,
        existing: Set<String>,
        taken: Set<String>,
    ): String? {
        val plain = plainName(sourceId, name, index)
        if (plain == VideoFiles.liveName(sourceId, index + 1, "MP4")) {
            return generateSequence(1) { it + 1 }
                .map { VideoFiles.liveName(sourceId, it, "MP4") }
                .first { it !in existing && it !in taken }
        }
        return plain.takeIf { it !in taken }
    }

    /** Places the staged files recording by recording; returns the clips placed in the session. */
    private fun lineUp(
        sessionId: String,
        staged: List<String>,
        kind: VideoSourceKind,
        sourceId: String,
        problems: MutableList<String>,
    ): List<VideoClip> {
        if (staged.isEmpty()) return emptyList()
        val data = SessionStore(sessionsRoot).read(sessionId)
        val placer = GoProPlacer(GpsAligner(), ::localOffsetMillis, IosPlacementLog)
        return byRecording(staged) { it.substringAfterLast('/') }.flatMap { recording ->
            val cameraFiles = recording.map { path ->
                CameraFile(path.substringAfterLast('/')) { PosixMp4Source(path) }
            }
            val result = placer.place(data, cameraFiles, kind, sourceId)
            problems += result.problems
            result.clips
        }
    }

    /**
     * Moves the placed files into the session, keeping each file they replace in [staging] until the
     * index is written; on any failure the replaced files are put back and the new ones taken out.
     */
    private fun commit(videoDir: String, staging: String, clips: List<VideoClip>) {
        val journal = "$staging/$JournalName"
        writeBytesAtomically(journal, ClipIndexJson.encode(clips).encodeToByteArray())
        val moved = mutableListOf<Pair<String, String?>>()
        try {
            for (clip in clips) {
                val target = "$videoDir/${clip.fileName}"
                val previous = "$staging/${clip.fileName}$PreviousSuffix".takeIf { exists(target) }
                previous?.let { move(target, it) }
                moved += target to previous
                move("$staging/${clip.fileName}", target)
            }
            ClipIndex(videoDir).putAll(clips)
            files.removeItemAtPath(journal, null)
        } catch (e: IoProblem) {
            for ((target, previous) in moved.asReversed()) {
                if (previous != null && exists(previous)) {
                    platform.posix.rename(previous, target)
                } else {
                    files.removeItemAtPath(target, null)
                }
            }
            files.removeItemAtPath(journal, null)
            throw e
        }
    }

    /**
     * A staging folder nothing has touched for an hour belongs to an import that was killed. Without
     * a journal nothing in the session was changed yet: it is removed. With one, the index tells how
     * far it got: when it has the journal's clips the import is finished, else every file it replaced
     * is put back. Only then is the folder removed, so a replaced video is never lost.
     */
    private fun recoverStaging(videoDir: String) {
        val now = NSDate().timeIntervalSince1970
        val stale = list(videoDir)
            .filter { it.startsWith(StagingPrefix) && isDirectory("$videoDir/$it") }
            .map { "$videoDir/$it" }
            .filter { dir -> now - newest(dir) > StaleStagingSeconds }
        for (dir in stale) {
            val clips = readJournal("$dir/$JournalName")
            val indexed = ClipIndex(videoDir).clips()
            if (clips.isNotEmpty() && !indexed.containsAll(clips)) putBack(dir, videoDir, clips)
            files.removeItemAtPath(dir, null)
        }
    }

    /**
     * Undoes the import of [clips]: each file it replaced is put back from [staging], and each file it
     * moved into the session without replacing one (no longer in [staging], no earlier copy) is removed,
     * so no unindexed copy is left taking space. Files it never moved are still in [staging].
     */
    private fun putBack(staging: String, videoDir: String, clips: List<VideoClip>) {
        for (clip in clips) {
            val target = "$videoDir/${clip.fileName}"
            val previous = "$staging/${clip.fileName}$PreviousSuffix"
            when {
                exists(previous) -> platform.posix.rename(previous, target)
                !exists("$staging/${clip.fileName}") -> files.removeItemAtPath(target, null)
            }
        }
    }

    /** The journal's clips; none when there is no journal or it was cut short before anything moved. */
    private fun readJournal(journal: String): List<VideoClip> = readText(journal)?.let {
        try {
            ClipIndexJson.decode(it)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }.orEmpty()

    private companion object {
        const val StagingPrefix = ".import-"
        const val PreviousSuffix = ".previous"
        const val JournalName = "commit.json"
        const val StaleStagingSeconds = 60.0 * 60
    }
}

private const val MillisPerSecond = 1000L

private val files get() = NSFileManager.defaultManager

private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * MillisPerSecond).toLong()

private fun newest(dir: String): Double = (listOf(dir) + list(dir).map { "$dir/$it" }).maxOf { modified(it) }

private fun modified(path: String): Double =
    (files.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 ?: 0.0

private fun list(dir: String): List<String> =
    files.contentsOfDirectoryAtPath(dir, null)?.filterIsInstance<String>().orEmpty()

private fun exists(path: String) = files.fileExistsAtPath(path)

private fun isDirectory(path: String) = exists(path) && files.contentsOfDirectoryAtPath(path, null) != null

private fun move(from: String, to: String) {
    if (platform.posix.rename(from, to) != 0) throw IoProblem("cannot move ${from.substringAfterLast('/')}", null)
}

/** The phone's UTC offset at [utcMillis], in milliseconds, for a camera clock read as local time. */
internal fun localOffsetMillis(utcMillis: Long): Long {
    val at = NSDate.dateWithTimeIntervalSince1970(utcMillis.toDouble() / MillisPerSecond)
    return NSTimeZone.localTimeZone.secondsFromGMTForDate(at) * MillisPerSecond
}

/** The text of the file at [path], or null when there is none or it cannot be read. */
internal fun readText(path: String): String? =
    NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

/**
 * A session's clip index, `<session>/video/clips.json`, in the Android app's format (VideoIndex).
 * Reading never changes it: an index that cannot be read has no clips here, and is moved aside only
 * when [putAll] writes a new one, rather than overwritten.
 */
internal class ClipIndex(private val videoDir: String) {
    private val path = "$videoDir/$IndexFileName"

    fun clips(): List<VideoClip> = read().orEmpty()

    /** Adds [added], each replacing any clip with the same file name. */
    fun putAll(added: List<VideoClip>) = lock.withLock {
        val existing = read()
        if (existing == null) platform.posix.rename(path, "$path.unreadable-${nowMillis()}")
        val merged = ClipIndexJson.merge(existing.orEmpty(), added)
        writeBytesAtomically(path, ClipIndexJson.encode(merged).encodeToByteArray())
    }

    /** The clips; none when there is no index, null when there is one this build cannot read. */
    private fun read(): List<VideoClip>? {
        val text = readText(path) ?: return emptyList()
        return try {
            ClipIndexJson.decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val IndexFileName = "clips.json"
        private val lock = platform.Foundation.NSLock()
    }
}

private inline fun <T> platform.Foundation.NSLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** Placing in the app's continuous log, in the words of Android's check log. */
internal object IosPlacementLog : PlacementLog {
    override fun line(text: String, warning: Boolean) = logInfo(GoProTag, text)

    override fun placed(sessionId: String, sourceId: String, clip: VideoClip, probes: List<Probe>) {
        logInfo(
            GoProTag,
            "$sessionId camera=$sourceId file=${clip.fileName} result=aligned method=${clip.alignment.method} " +
                "offset_us=${clip.sessionOffsetUs} duration_us=${clip.durationUs ?: 0} probes=${probes.size}",
        )
    }

    override fun rejected(sessionId: String, sourceId: String, file: String, why: String) =
        logInfo(GoProTag, "$sessionId camera=$sourceId file=$file result=rejected why=${why.replace(' ', '_')}")
}
