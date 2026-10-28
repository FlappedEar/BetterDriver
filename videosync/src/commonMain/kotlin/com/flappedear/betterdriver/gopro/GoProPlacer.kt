package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.IoProblem
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoSourceKind
import com.flappedear.betterdriver.video.gpmf.GpmfGps
import com.flappedear.betterdriver.video.gpmf.GpsSample
import com.flappedear.betterdriver.video.mp4.Mp4Parser
import com.flappedear.betterdriver.video.mp4.Mp4Source
import com.flappedear.betterdriver.video.sync.AlignmentResult
import com.flappedear.betterdriver.video.sync.GpsAligner
import com.flappedear.betterdriver.video.sync.SpeedPoint
import com.flappedear.betterdriver.video.sync.sessionSpeedPoint

/** A camera file to place: its [name] in the session's video directory and a way to read it. */
class CameraFile(val name: String, val open: () -> Mp4Source)

/** The clips placed in a session, and a line for each file that was not. */
data class Placement(val clips: List<VideoClip>, val problems: List<String>)

/**
 * Where placing goes in the drive's log: the Android app keeps it in its continuous log and the
 * session's check log (FBBD-82, FBBD-183), iOS in its continuous log.
 */
interface PlacementLog {
    fun line(text: String, warning: Boolean = false)

    fun placed(sessionId: String, sourceId: String, clip: VideoClip, probes: List<Probe>)

    fun rejected(sessionId: String, sourceId: String, file: String, why: String)
}

/**
 * Places camera files on a session's timeline (FBBD-22), on Android and iOS alike.
 *
 * Each file is aligned with [GpsAligner]: the camera's GPMF GPS speed against the session's GNSS
 * speed. The starting guess, best first:
 * - the chapter before it, plus that chapter's duration (chapters of one recording are back to back);
 * - for the first chapter of an Open GoPro recording, the session time at which the camera acknowledged Set Shutter on;
 * - the file's creation time on the camera's clock, read as local time ([localOffsetMillis] gives the
 *   phone's UTC offset at a time). A camera may write UTC there instead, as the MP4 format says to,
 *   so the time of a file the user picked is read as UTC when only that puts the file inside the
 *   session, within the uncertainty below.
 * GPS UTC on both sides, when present, beats all of these inside the aligner.
 *
 * A file is in the session when it overlaps it somewhere within its placement's uncertainty: a
 * hand-started camera's clock or the shutter acknowledgement is only a rough start time, and a
 * short session is shorter than that error (FBBD-201). Such a file is moved by no more than that
 * uncertainty to overlap the session as much as it can, and keeps its weak alignment method. A
 * file that cannot be placed inside the session even so is not placed and is reported.
 *
 * Each placement goes to [log] with the session's id, and for a file matched by GPS so do
 * two-minute stretches of it aligned on their own, which show the camera clock's drift (FBBD-82).
 */
class GoProPlacer(
    private val aligner: GpsAligner = GpsAligner(),
    private val localOffsetMillis: (utcMillis: Long) -> Long,
    private val log: PlacementLog,
) {
    /**
     * Places [files], all from one recording, in chapter order, as camera [sourceId] of [kind].
     * [recording] is the Open GoPro start record, if there is one.
     */
    fun place(
        session: SessionData,
        files: List<CameraFile>,
        kind: VideoSourceKind,
        sourceId: String,
        recording: GoProRecording? = null,
    ): Placement {
        val speeds = sessionSpeeds(session)
        val durationUs = sessionEndUs(session)
        val clips = mutableListOf<VideoClip>()
        val problems = mutableListOf<String>()
        var previous: VideoClip? = null
        for ((index, file) in files.withIndex()) {
            val placed = try {
                file.open().use { source ->
                    placeFile(source, file.name, index + 1, kind, sourceId, previous, recording, session, speeds)
                }
            } catch (e: IoProblem) {
                problems += "${file.name}: ${e.message}"
                null
            }
            if (placed == null) {
                if (problems.none { it.startsWith(file.name) }) problems += "${file.name}: could not be placed in time"
                log.rejected(session.id, sourceId, file.name, "not placed")
                continue
            }
            val fitted = fitIntoSession(placed.clip, durationUs)
            val clip = placed.clip
            val moved = fitted?.takeIf { it != clip }?.let { " -> ${it.sessionOffsetUs} us" }.orEmpty()
            log.line(
                "${file.name}: ${clip.alignment.method} at ${clip.sessionOffsetUs} us " +
                    "± ${clip.alignment.uncertaintyUs} us, ${clip.durationUs ?: 0} us long, " +
                    "session $durationUs us$moved",
            )
            if (fitted == null) {
                log.line("${file.name}: recorded outside this session", warning = true)
                problems += "${file.name}: recorded outside this session"
                log.rejected(session.id, sourceId, file.name, "outside session")
            } else {
                clips += fitted
                log.placed(session.id, sourceId, fitted, probes(aligner, placed.gps, speeds, fitted))
            }
            previous = fitted ?: clip
        }
        return Placement(clips, problems)
    }

    @Suppress("LongParameterList")
    private fun placeFile(
        source: Mp4Source,
        name: String,
        chapter: Int,
        kind: VideoSourceKind,
        sourceId: String,
        previous: VideoClip?,
        recording: GoProRecording?,
        session: SessionData,
        speeds: List<SpeedPoint>,
    ): Placed? {
        val info = Mp4Parser.read(source, name)
        val prior = previous?.let {
            AlignmentResult(
                it.sessionOffsetUs + (it.durationUs ?: 0),
                Alignment(it.alignment.method, it.alignment.uncertaintyUs + ChapterJoinUncertaintyUs),
            )
        } ?: recording?.takeIf { chapter == 1 }?.let {
            AlignmentResult(it.startAckSessionUs, Alignment(AlignmentMethod.COMMAND_ACK, CommandAckUncertaintyUs))
        } ?: info.creationTimeMillis?.let { cameraClock(it, info.durationUs, kind, recording, session) }
        val gps = GpmfGps.read(source, info)
        val result = aligner.align(gps, speeds, prior) ?: return null
        val clip = VideoClip(
            fileName = name,
            kind = kind,
            sourceId = sourceId,
            sessionOffsetUs = result.sessionOffsetUs,
            alignment = result.alignment,
            durationUs = info.durationUs.takeIf { it > 0 },
            chapter = chapter,
        )
        return Placed(clip, gps)
    }

    /** A file placed in time, with its GPS for the drift probes. */
    private class Placed(val clip: VideoClip, val gps: List<GpsSample>)

    /** The session time of a file created at [created] on the camera's clock; see the class comment. */
    private fun cameraClock(
        created: Long,
        durationUs: Long,
        kind: VideoSourceKind,
        recording: GoProRecording?,
        session: SessionData,
    ): AlignmentResult {
        val offsetMillis = recording?.cameraUtcOffsetMinutes?.let { it * MillisPerMinute } ?: localOffsetMillis(created)
        fun sessionUs(utc: Long) = (utc - session.info.startUtcMillis) * MicrosPerMilli
        val sessionEndUs = sessionEndUs(session)
        val uncertainty = if (recording != null) SetClockUncertaintyUs else UnsetClockUncertaintyUs
        // Overlaps the session somewhere within the uncertainty, as fitIntoSession places it.
        fun inside(offsetUs: Long) = offsetUs + durationUs >= -uncertainty && offsetUs <= sessionEndUs + uncertainty
        val local = sessionUs(created - offsetMillis)
        val asUtc = sessionUs(created)
        // Only for files the user picked: an Open GoPro camera's clock was just set by the app.
        val utcWritten = kind != VideoSourceKind.GOPRO && !inside(local) && inside(asUtc)
        return AlignmentResult(if (utcWritten) asUtc else local, Alignment(AlignmentMethod.CAMERA_CLOCK, uncertainty))
    }

    private companion object {
        const val MicrosPerMilli = 1000L
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

/**
 * When the session ended on its own clock: the latest of its sample sets, events and end record.
 * A session whose GNSS never delivered a sample (indoors, receiver not connected) still has events
 * and an end record, so its end is not 0 (FBBD-201: a 20 s session with no samples rejected its
 * GoPro clip as recorded outside it).
 */
fun sessionEndUs(session: SessionData): Long = maxOf(
    session.sampleSets.maxOfOrNull { it.tMicros } ?: 0L,
    session.events.maxOfOrNull { it.tMicros } ?: 0L,
    session.end?.tMicros ?: 0L,
)

/**
 * [clip] moved by no more than its alignment uncertainty to overlap a session that runs from 0 to
 * [sessionEndUs] as much as it can, or null when no such move makes it overlap. A clip that
 * already overlaps stays where it is.
 */
fun fitIntoSession(clip: VideoClip, sessionEndUs: Long): VideoClip? {
    val offset = clip.sessionOffsetUs
    val length = clip.durationUs ?: 0L
    val overlaps = offset + length >= 0 && offset <= sessionEndUs
    if (overlaps) return clip
    // Starts with the most overlap: the clip inside the session, or the session inside the clip.
    val bestStart = offset.coerceIn(minOf(0L, sessionEndUs - length), maxOf(0L, sessionEndUs - length))
    val u = clip.alignment.uncertaintyUs
    val moved = bestStart.coerceIn(offset - u, offset + u)
    return if (moved + length >= 0 && moved <= sessionEndUs) clip.copy(sessionOffsetUs = moved) else null
}

/** The session's GNSS speed with its UTC time, for alignment. */
fun sessionSpeeds(session: SessionData): List<SpeedPoint> = session.sampleSets.mapNotNull { set ->
    val speed = set.values.firstOrNull { it.channelId == GnssChannels.SPEED_KMH.id }?.value ?: return@mapNotNull null
    val utc = set.values.firstOrNull { it.channelId == GnssChannels.UTC_MS.id }?.value?.toLong()
    sessionSpeedPoint(set.tMicros, speed, utc)
}

/** Length of the stretches of an imported file aligned on their own, for its drift (FBBD-82). */
const val ProbeStretchUs = 120_000_000L

/** One stretch of a file aligned on its own: its middle on the session clock, and its offset. */
data class Probe(val middleUs: Long, val offsetUs: Long)

/**
 * Each [ProbeStretchUs] stretch of [clip]'s GPS aligned on its own against the session's [speeds],
 * starting from the whole file's offset. A stretch whose speeds give no match (the car parked) is
 * left out. How far the stretches' offsets move along the file is the camera clock's drift.
 */
internal fun probes(aligner: GpsAligner, gps: List<GpsSample>, speeds: List<SpeedPoint>, clip: VideoClip): List<Probe> {
    if (clip.alignment.method != AlignmentMethod.GPS_MATCH || gps.isEmpty()) return emptyList()
    val prior = AlignmentResult(clip.sessionOffsetUs, Alignment(AlignmentMethod.GPS_MATCH, 0))
    return gps.groupBy { it.videoTimeUs / ProbeStretchUs }.entries.sortedBy { it.key }.mapNotNull { (index, stretch) ->
        val result = aligner.align(stretch, speeds, prior)?.takeIf { it.alignment.method == AlignmentMethod.GPS_MATCH }
        result?.let { Probe(clip.sessionOffsetUs + index * ProbeStretchUs + ProbeStretchUs / 2, it.sessionOffsetUs) }
    }
}
