package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.gpmf.GpmfGps
import com.flappedear.betterdriver.video.mp4.Mp4Reader
import com.flappedear.betterdriver.video.sync.AlignmentResult
import com.flappedear.betterdriver.video.sync.GpsAligner
import com.flappedear.betterdriver.video.sync.SpeedPoint
import com.flappedear.betterdriver.video.sync.sessionSpeedPoint
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * Places GoPro files on a session's timeline and adds them to its [VideoIndex] (FBBD-22).
 *
 * Each file is aligned with [GpsAligner]: the camera's GPMF GPS speed against the session's GNSS
 * speed. The starting guess, best first:
 * - the chapter before it, plus that chapter's duration (chapters of one recording are back to back);
 * - for the first chapter of an Open GoPro recording, the session time at which the camera acknowledged Set Shutter on;
 * - the file's creation time on the camera's clock, read as local time in [zone].
 * GPS UTC on both sides, when present, beats all of these inside the aligner.
 *
 * A file that cannot be placed inside the session is not indexed and is reported.
 */
class GoProImporter(
    private val aligner: GpsAligner = GpsAligner(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    data class Result(val clips: List<VideoClip>, val problems: List<String>)

    /** Which camera the files came from. */
    data class Source(val kind: VideoSourceKind, val id: String)

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
    ): Result {
        val speeds = sessionSpeeds(session)
        val durationUs = session.sampleSets.lastOrNull()?.tMicros ?: 0L
        val clips = mutableListOf<VideoClip>()
        val problems = mutableListOf<String>()
        var previous: VideoClip? = null
        for ((index, file) in files.withIndex()) {
            val placed = try {
                place(file, index + 1, source, previous, recording, session, speeds)
            } catch (e: IOException) {
                problems += "${file.name}: ${e.message}"
                null
            }
            if (placed == null) {
                if (problems.none { it.startsWith(file.name) }) problems += "${file.name}: could not be placed in time"
                continue
            }
            val end = placed.sessionOffsetUs + (placed.durationUs ?: 0)
            if (end < 0 || placed.sessionOffsetUs > durationUs) {
                problems += "${file.name}: recorded outside this session"
            } else {
                clips += placed
            }
            previous = placed
        }
        if (clips.isNotEmpty()) VideoIndex(sessionDir).putAll(clips)
        return Result(clips, problems)
    }

    @Suppress("LongParameterList")
    private fun place(
        file: File,
        chapter: Int,
        source: Source,
        previous: VideoClip?,
        recording: GoProRecording?,
        session: SessionData,
        speeds: List<SpeedPoint>,
    ): VideoClip? {
        val info = Mp4Reader.read(file)
        val prior = previous?.let {
            AlignmentResult(
                it.sessionOffsetUs + (it.durationUs ?: 0),
                Alignment(it.alignment.method, it.alignment.uncertaintyUs + ChapterJoinUncertaintyUs),
            )
        } ?: recording?.takeIf { chapter == 1 }?.let {
            AlignmentResult(it.startAckSessionUs, Alignment(AlignmentMethod.COMMAND_ACK, CommandAckUncertaintyUs))
        } ?: info.creationTimeMillis?.let { created ->
            val asLocal = Instant.ofEpochMilli(created)
            val offsetMillis = recording?.cameraUtcOffsetMinutes?.let { it * MillisPerMinute }
                ?: zone.rules.getOffset(asLocal).totalSeconds * MillisPerSecond
            val utc = created - offsetMillis
            val uncertainty = if (recording != null) SetClockUncertaintyUs else UnsetClockUncertaintyUs
            val offsetUs = (utc - session.info.startUtcMillis) * MicrosPerMilli
            AlignmentResult(offsetUs, Alignment(AlignmentMethod.CAMERA_CLOCK, uncertainty))
        }
        val result = aligner.align(GpmfGps.read(file), speeds, prior) ?: return null
        return VideoClip(
            fileName = file.name,
            kind = source.kind,
            sourceId = source.id,
            sessionOffsetUs = result.sessionOffsetUs,
            alignment = result.alignment,
            durationUs = info.durationUs.takeIf { it > 0 },
            chapter = chapter,
        )
    }

    private companion object {
        const val MicrosPerMilli = 1000L
        const val MillisPerSecond = 1000L
        const val MillisPerMinute = 60_000L
        const val ChapterJoinUncertaintyUs = 100_000L

        /** Guess: shutter ack to first frame. Not measured; the GPS match refines it. */
        const val CommandAckUncertaintyUs = 2_000_000L

        /** Clock set by the app at start; mvhd has whole seconds. */
        const val SetClockUncertaintyUs = 5_000_000L

        /** Clock of a GoPro started by hand, set by someone else at some point: allow half an hour either way. */
        const val UnsetClockUncertaintyUs = 30L * 60 * 1_000_000
    }
}

/** The session's GNSS speed with its UTC time, for alignment. */
fun sessionSpeeds(session: SessionData): List<SpeedPoint> = session.sampleSets.mapNotNull { set ->
    val speed = set.values.firstOrNull { it.channelId == GnssChannels.SPEED_KMH.id }?.value ?: return@mapNotNull null
    val utc = set.values.firstOrNull { it.channelId == GnssChannels.UTC_MS.id }?.value?.toLong()
    sessionSpeedPoint(set.tMicros, speed, utc)
}
