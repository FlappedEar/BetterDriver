package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.mp4.FileMp4Source
import com.flappedear.betterdriver.video.sync.GpsAligner
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Places GoPro files on a session's timeline and adds them to its [VideoIndex] (FBBD-22): the
 * common [GoProPlacer] on java.io files, with times read as local time in [zone]. Each import goes in
 * the drive's log ([DebugLog]) and the session's check log (FBBD-82, FBBD-183).
 */
class GoProImporter(
    aligner: GpsAligner = GpsAligner(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    data class Result(val clips: List<VideoClip>, val problems: List<String>)

    /** Which camera the files came from. */
    data class Source(val kind: VideoSourceKind, val id: String)

    private val placer = GoProPlacer(
        aligner,
        localOffsetMillis = { utc -> zone.rules.getOffset(Instant.ofEpochMilli(utc)).totalSeconds * MillisPerSecond },
        log = ImportLog,
    )

    /**
     * [files] are already in the session's video directory, all from one recording, in chapter
     * order. [recording] is the Open GoPro start record, if there is one.
     */
    fun import(
        sessionDir: File,
        session: SessionData,
        files: List<File>,
        source: Source,
        recording: GoProRecording? = null,
    ): Result = place(session, files, source, recording).also { result ->
        if (result.clips.isNotEmpty()) VideoIndex(sessionDir).putAll(result.clips)
    }

    /**
     * Places [files] as [import] does, without adding them to the session's index: they may still be
     * in a staging folder, under the names they will have in the session's video directory.
     */
    fun place(
        session: SessionData,
        files: List<File>,
        source: Source,
        recording: GoProRecording? = null,
    ): Result {
        val cameraFiles = files.map { f -> CameraFile(f.name) { FileMp4Source(f) } }
        val placed = placer.place(session, cameraFiles, source.kind, source.id, recording)
        return Result(placed.clips, placed.problems)
    }

    /** The Android app's drive log and check log. */
    private object ImportLog : PlacementLog {
        override fun line(text: String, warning: Boolean) =
            if (warning) DebugLog.w(Tag, text) else DebugLog.i(Tag, text)

        override fun placed(sessionId: String, sourceId: String, clip: VideoClip, probes: List<Probe>) =
            logImport(sessionId, sourceId, clip, probes)

        override fun rejected(sessionId: String, sourceId: String, file: String, why: String) =
            logRejected(sessionId, sourceId, file, why)
    }

    private companion object {
        const val Tag = "GoProImport"
        const val MillisPerSecond = 1000L
    }
}
