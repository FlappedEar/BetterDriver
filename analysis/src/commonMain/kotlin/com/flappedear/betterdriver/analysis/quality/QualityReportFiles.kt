package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.log.CheckLog
import com.flappedear.betterdriver.recorder.log.CoreLog
import com.flappedear.betterdriver.recorder.log.logLine
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionFolders
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.video.ClipIndexJson
import com.flappedear.betterdriver.video.ClipStatus
import com.flappedear.betterdriver.video.VideoClip

/**
 * The validation report of each saved session (FBBD-183), kept as `quality.json` in the session's
 * folder, the same on Android and iOS (FBBD-365). Made the first time it is asked for, so sessions
 * recorded before it existed get one when opened; made again when the session's state or its clip
 * index changed since. The clips come from the session's clip index (video/clips.json); the platform
 * counts the frames of its local clips' files where it can ([frames], FBBD-246) and serialises the
 * calls; [tracks] are the saved tracks. Blocking: call it off the main thread.
 */
class QualityReportFiles(
    root: String,
    private val tracks: () -> List<Track>,
    private val nowMillis: () -> Long,
    private val frames: (dir: String, clips: List<VideoClip>) -> List<FileFrames> = { _, _ -> emptyList() },
) {
    private val folders = SessionFolders(root)

    /** The report of session [id], made when there is none yet; null while it records, or unreadable. */
    fun load(id: String): QualityReport? {
        val dir = folders.dir(id)
        if (!CoreFiles.isDirectory(dir)) return null
        val state = folders.state(dir)
        val done = state == SessionState.FINISHED || state == SessionState.RECOVERED
        return if (done) stored(dir, state) ?: make(id, dir) else null
    }

    // A damaged session must not fail the screen, the zip or the stop.
    @Suppress("TooGenericExceptionCaught")
    private fun make(id: String, dir: String): QualityReport? = try {
        val data = folders.read(dir)
        val now = nowMillis()
        val local = localClips(dir)
        val clips = local.map {
            QualityClip(
                it.sourceId,
                it.fileName,
                it.sessionOffsetUs,
                it.durationUs?.let(it::sessionTimeUs),
                it.alignment.method.name,
                it.alignment.uncertaintyUs,
            )
        }
        val report = sessionQuality(data, trackFor(data, tracks()), clips, previous(data), now, frames(dir, local))
        CoreFiles.writeTextAtomically(CoreFiles.join(dir, FileName), report.toJson())
        logLine(
            CoreLog.LevelInfo,
            Tag,
            "report of $id: ${report.count(Verdict.PASS)} pass, ${report.count(Verdict.FAIL)} fail",
        )
        report
    } catch (e: Exception) {
        logLine(CoreLog.LevelWarn, Tag, "report of $id failed", e)
        null
    }

    /**
     * The stored report, if this version wrote it for the session as it is now. The clip index counts
     * against when the report was computed, not when its file was written: on Arek's 2026-10-15 13:04
     * drive the report was computed at the stop, the camera wrote the file's last length into the index
     * while it ran, and the report written after that kept the length from 11 s before the end (video
     * coverage 97.8 %, FAIL, with no video missing).
     */
    private fun stored(dir: String, state: SessionState): QualityReport? {
        val file = CoreFiles.join(dir, FileName)
        if (!CoreFiles.isFile(file)) return null
        val report = try {
            QualityReport.fromJson(CoreFiles.readText(file))
        } catch (e: IOException) {
            logLine(CoreLog.LevelWarn, Tag, "unreadable $file", e)
            null
        }
        val index = clipIndexPath(dir)
        return report?.takeIf {
            val computed = it.computedAtUtcMillis.takeIf { at -> at > 0 } ?: CoreFiles.lastModifiedMillis(file)
            val indexChanged = CoreFiles.isFile(index) && CoreFiles.lastModifiedMillis(index) >= computed
            it.sessionState == state.name && !indexChanged
        }
    }

    /** The session's local clips; none when it has no index or one this build cannot read. */
    @Suppress("TooGenericExceptionCaught") // A damaged index only leaves the video out.
    private fun localClips(dir: String): List<VideoClip> {
        val index = clipIndexPath(dir)
        if (!CoreFiles.isFile(index)) return emptyList()
        return try {
            ClipIndexJson.decode(CoreFiles.readText(index)).filter { it.status == ClipStatus.LOCAL }
        } catch (e: Exception) {
            logLine(CoreLog.LevelWarn, Tag, "unreadable clip index in $dir", e)
            emptyList()
        }
    }

    /** The session a recorder restart carried on from (FBBD-72), when it names one that is still there. */
    @Suppress("TooGenericExceptionCaught") // Deleted or damaged: the loss is then not measured.
    private fun previous(data: SessionData): PreviousSession? {
        val event = data.events.firstOrNull { it.kind == EventKinds.RECORDER_RESTART } ?: return null
        val id = event.text.substringAfter("previous=", "").substringBefore(' ')
        val dir = folders.dir(id)
        return try {
            if (id.isEmpty() || !CoreFiles.isDirectory(dir)) null else previousSession(folders.read(dir))
        } catch (e: Exception) {
            logLine(CoreLog.LevelWarn, Tag, "previous session $id unreadable", e)
            null
        }
    }

    companion object {
        /** The report's file in the session's folder. */
        const val FileName = "quality.json"

        /** The clip index in the session's video folder (Android's VideoIndex, iOS's ClipIndex). */
        const val ClipIndexFileName = "clips.json"

        private const val Tag = "QualityReports"

        /** The session's clip index, whose change since the report makes it again. */
        fun clipIndexPath(dir: String): String = CoreFiles.join(CoreFiles.join(dir, VideoFiles.DIR), ClipIndexFileName)
    }
}

/**
 * The report of recording [id] in the drive's log when it ends (FBBD-183): its [logLines] under the
 * Quality tag, the same lines from both apps, so one set of checks reads both logs.
 */
fun logQuality(id: String, report: QualityReport) =
    report.logLines().forEach { CoreLog.forSession(id, CheckLog.QUALITY, it) }
