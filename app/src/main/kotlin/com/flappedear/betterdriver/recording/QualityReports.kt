package com.flappedear.betterdriver.recording

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import com.flappedear.betterdriver.analysis.quality.FileFrames
import com.flappedear.betterdriver.analysis.quality.isWide
import com.flappedear.betterdriver.analysis.quality.PreviousSession
import com.flappedear.betterdriver.analysis.quality.QualityClip
import com.flappedear.betterdriver.analysis.quality.QualityReport
import com.flappedear.betterdriver.analysis.quality.Verdict
import com.flappedear.betterdriver.analysis.quality.previousSession
import com.flappedear.betterdriver.analysis.quality.sessionQuality
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.sessionanalysis.trackFor
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.video.ClipStatus
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import java.io.File
import java.io.IOException

/** The reports of the app's saved sessions. Reads nothing until a report is asked for. */
internal fun qualityReports(context: Context) =
    QualityReports(SessionStore(sessionsDir(context)), TrackStore(tracksDir(context)))

/**
 * The validation report of each saved session (FBBD-183), kept as `quality.json` in the session's
 * folder. Made the first time it is asked for, so sessions recorded before it existed get one when
 * opened; made again when the session's state or its clip index changed since. Blocking: call it
 * off the main thread.
 */
internal class QualityReports(private val store: SessionStore, private val tracks: TrackStore) {
    /**
     * The report of session [id], made when there is none yet; null while it records, or unreadable.
     * One at a time, so the screen, the session list and the debug zip never make the same one twice.
     */
    fun load(id: String): QualityReport? = synchronized(Lock) {
        val dir = store.dir(id)
        val state = store.state(dir)
        val done = state == SessionState.FINISHED || state == SessionState.RECOVERED
        if (done) stored(dir, state) ?: make(id, dir) else null
    }

    // A damaged session must not fail the screen or the zip.
    @Suppress("TooGenericExceptionCaught")
    private fun make(id: String, dir: File): QualityReport? = try {
        val data = store.read(dir)
        val now = System.currentTimeMillis()
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
        val frames = local.filter { it.kind == VideoSourceKind.PHONE }
            .mapNotNull { countFrames(it.sourceId, File(VideoIndex(dir).videoDir, it.fileName)) }
        val report = sessionQuality(data, trackFor(data, tracks.all()), clips, previous(data), now, frames)
        write(File(dir, FileName), report.toJson())
        DebugLog.i(Tag, "report of $id: ${report.count(Verdict.PASS)} pass, ${report.count(Verdict.FAIL)} fail")
        report
    } catch (e: Exception) {
        DebugLog.w(Tag, "report of $id failed", e)
        null
    }

    /**
     * The stored report, if this version wrote it for the session as it is now. The clip index counts
     * against when the report was computed, not when its file was written: on Arek's 2026-10-15 13:04
     * drive the report was computed at the stop, the camera wrote the file's last length into the index
     * while it ran, and the report written after that kept the length from 11 s before the end (video
     * coverage 97.8 %, FAIL, with no video missing).
     */
    private fun stored(dir: File, state: SessionState): QualityReport? {
        val file = File(dir, FileName)
        if (!file.isFile) return null
        val report = try {
            QualityReport.fromJson(file.readText())
        } catch (e: IOException) {
            DebugLog.w(Tag, "unreadable ${file.path}", e)
            null
        }
        val index = File(VideoIndex(dir).videoDir, VideoIndex.IndexFileName)
        return report?.takeIf {
            val computed = it.computedAtUtcMillis.takeIf { at -> at > 0 } ?: file.lastModified()
            it.sessionState == state.name && !(index.isFile && index.lastModified() >= computed)
        }
    }

    /** The session's local clips; none when its index is unreadable. */
    private fun localClips(dir: File): List<VideoClip> = try {
        VideoIndex(dir).clips().filter { it.status == ClipStatus.LOCAL }
    } catch (e: IOException) {
        DebugLog.w(Tag, "unreadable clip index in $dir", e)
        emptyList()
    }

    /** The session a recorder restart carried on from (FBBD-72), when it names one that is still there. */
    @Suppress("TooGenericExceptionCaught") // Deleted or damaged: the loss is then not measured.
    private fun previous(data: SessionData): PreviousSession? {
        val event = data.events.firstOrNull { it.kind == EventKinds.RECORDER_RESTART } ?: return null
        val id = event.text.substringAfter("previous=", "").substringBefore(' ')
        val dir = store.dir(id)
        return try {
            if (id.isEmpty() || !dir.isDirectory) null else previousSession(store.read(dir))
        } catch (e: Exception) {
            DebugLog.w(Tag, "previous session $id unreadable", e)
            null
        }
    }

    /**
     * The frames [file] holds and its media time, from its MP4 (FBBD-246); null below Android 9, which
     * cannot count them, or for a file that cannot be read.
     */
    @Suppress("TooGenericExceptionCaught") // MediaMetadataRetriever reports a bad file as a runtime exception.
    private fun countFrames(camera: String, file: File): FileFrames? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !file.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull()
            val frames = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            val millis = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (frames == null || millis == null) {
                null
            } else {
                val w = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                val h = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                FileFrames(camera, file.name, frames, millis * MicrosPerMilli, wide = isWide(w, h))
            }
        } catch (e: Exception) {
            DebugLog.w(Tag, "frames of ${file.name} not counted", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Written whole, so a crash mid-write leaves the old report or none, never half of one. */
    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, "$FileName.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("cannot replace ${file.path}")
        }
    }

    companion object {
        /** The report's file in the session's folder. */
        const val FileName = "quality.json"

        private const val Tag = "QualityReports"
        private const val MicrosPerMilli = 1_000L
        private val Lock = Any()
    }
}
