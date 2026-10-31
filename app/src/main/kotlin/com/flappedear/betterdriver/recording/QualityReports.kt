package com.flappedear.betterdriver.recording

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import com.flappedear.betterdriver.analysis.quality.FileFrames
import com.flappedear.betterdriver.analysis.quality.isWide
import com.flappedear.betterdriver.analysis.quality.QualityReport
import com.flappedear.betterdriver.analysis.quality.QualityReportFiles
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import java.io.File

/** The reports of the app's saved sessions. Reads nothing until a report is asked for. */
internal fun qualityReports(context: Context) =
    QualityReports(SessionStore(sessionsDir(context)), TrackStore(tracksDir(context)))

/**
 * The validation report of each saved session (FBBD-183), kept as `quality.json` in the session's
 * folder by the shared core's [QualityReportFiles], as on iOS (FBBD-365). Android adds the frames of
 * each phone file, which it can count. Blocking: call it off the main thread.
 */
internal class QualityReports(store: SessionStore, tracks: TrackStore) {
    private val files = QualityReportFiles(store.root.path, tracks::all, System::currentTimeMillis) { dir, clips ->
        val videoDir = VideoIndex(File(dir)).videoDir
        clips.filter { it.kind == VideoSourceKind.PHONE }
            .mapNotNull { countFrames(it.sourceId, File(videoDir, it.fileName)) }
    }

    /**
     * The report of session [id], made when there is none yet; null while it records, or unreadable.
     * One at a time, so the screen, the session list and the debug zip never make the same one twice.
     */
    fun load(id: String): QualityReport? = synchronized(Lock) { files.load(id) }

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

    companion object {
        /** The report's file in the session's folder. */
        const val FileName = QualityReportFiles.FileName

        private const val Tag = "QualityReports"
        private const val MicrosPerMilli = 1_000L
        private val Lock = Any()
    }
}
