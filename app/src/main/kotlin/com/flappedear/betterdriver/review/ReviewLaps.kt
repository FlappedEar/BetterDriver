package com.flappedear.betterdriver.review

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.feature.review.ReviewLap
import com.flappedear.betterdriver.feature.review.ReviewLoad
import com.flappedear.betterdriver.feature.review.ReviewSession
import com.flappedear.betterdriver.feature.review.ReviewTrack
import com.flappedear.betterdriver.feature.review.SessionVideo
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import com.flappedear.betterdriver.recording.sessionDetail
import com.flappedear.betterdriver.recording.sessionTitle
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.sessionanalysis.trackFor
import com.flappedear.betterdriver.analysis.review.recordedFacts
import com.flappedear.betterdriver.analysis.review.withLaps
import com.flappedear.betterdriver.shell.SessionFacts
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.video.ClipStatus
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.mp4.Mp4Reader
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Laps of saved sessions for the lap review (FBBD-92), in the UI process. Each session is read
 * once, replayed into laps on the saved track it was recorded at, and kept with its video; a
 * session is read again only when its track's lines, its video or its own files change (a session
 * replaced by an import of the same id, audit A10). Disk work runs on one background
 * thread; [state] is Compose state.
 */
class ReviewLaps private constructor(context: Context) {
    // Made on the io thread when first used: making them reads the disk (FBBD-41).
    private val store by lazy { SessionStore(sessionsDir(context)) }
    private val tracks by lazy { TrackStore(tracksDir(context)) }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "review-io") }
    private val main = Handler(Looper.getMainLooper())

    /** By session id. io thread only. */
    private val cache = HashMap<String, Cached>()

    /** Row facts of the sessions past [MaxSessions], which the review does not read. */
    private val older = HashMap<String, Cached>()

    var state by mutableStateOf<ReviewLoad>(ReviewLoad.Loading(0, 0))
        private set

    /** What each read session's row in Sessions says (UI-139 C), by session id. */
    var facts by mutableStateOf<Map<String, SessionFacts>>(emptyMap())
        private set

    /**
     * Reads new and changed sessions, then publishes every track with laps. Sessions [include]d are
     * read even when older than the newest [MaxSessions]: the laps another screen opens the review
     * on (FBBD-153), which must be there to show.
     */
    fun load(include: Set<String> = emptySet()) = io.execute {
        val saved = tracks.all().filter { it.timeable }
        val all = store.list().filter { it.state != SessionState.RECORDING }
        val sessions = all.take(MaxSessions) + all.drop(MaxSessions).filter { it.id in include }
        cache.keys.retainAll(sessions.map { it.id }.toSet())
        sessions.forEachIndexed { i, s ->
            val known = cache[s.id]
            if (known == null || known.stale(saved, Stamps.of(s.dir))) cache[s.id] = read(s, saved)
            // Once laps are shown, a reload keeps them on screen: back on Loading, the review would
            // leave and forget the laps picked (after a rotation, or on coming back to it).
            if ((state as? ReviewLoad.Ready)?.tracks.isNullOrEmpty()) post(ReviewLoad.Loading(i + 1, sessions.size))
        }
        post(ReviewLoad.Ready(group(sessions, saved)))
        readOlder(all.filter { s -> s.id !in cache }, saved)
        val rows = cache.mapValues { it.value.facts } + older.mapValues { it.value.facts }
        main.post { facts = rows }
    }

    /**
     * Sessions shows every session, so the ones past [MaxSessions] are read for their row's facts
     * too, after the review has its laps; only the facts are kept, not the laps' paths.
     */
    private fun readOlder(rest: List<SessionSummary>, saved: List<Track>) {
        older.keys.retainAll(rest.map { it.id }.toSet())
        for (s in rest) {
            val known = older[s.id]
            if (known == null || known.stale(saved, Stamps.of(s.dir))) older[s.id] = read(s, saved).withoutLaps()
        }
    }

    private fun post(load: ReviewLoad) = main.post { state = load }

    private fun group(sessions: List<SessionSummary>, saved: List<Track>): List<ReviewTrack> {
        val byTrack = LinkedHashMap<String, MutableList<ReviewSession>>()
        for (s in sessions) {
            val c = cache[s.id] ?: continue
            if (c.trackId != null && c.session != null) byTrack.getOrPut(c.trackId) { mutableListOf() } += c.session
        }
        return byTrack.mapNotNull { (id, list) ->
            saved.firstOrNull { it.id == id }?.let { ReviewTrack(id, it.displayName, list) }
        }
    }

    // A damaged session is left out; the others still load.
    @Suppress("TooGenericExceptionCaught")
    private fun read(s: SessionSummary, saved: List<Track>): Cached = try {
        val data = store.read(s.id)
        // The track the session recorded at, else the nearest one (as the lap analysis picks it).
        val track = trackFor(data, saved)
        val facts = recordedFacts(data)
        if (track == null) {
            Cached(null, null, revisions(saved), Stamps.of(s.dir), facts)
        } else {
            val laps = replayLaps(s.id, data.sampleSets, track)
            val video = sessionVideo(s.dir, VideoTimeline.from(data.events, indexedClips(s.dir)))
            val title = sessionTitle(s)
            val session = ReviewSession(
                id = s.id,
                title = title,
                detail = sessionDetail(s),
                laps = laps.laps.map { ReviewLap(it, title, video) },
                bestNumber = laps.best?.lap?.number,
            )
            Cached(
                track.id,
                session.takeIf { it.laps.isNotEmpty() },
                revisions(saved),
                Stamps.of(s.dir),
                facts.withLaps(laps),
            )
        }
    } catch (e: Exception) {
        DebugLog.w(Tag, "reading session ${s.id} failed", e)
        Cached(null, null, revisions(saved), Stamps.of(s.dir), SessionFacts())
    }

    /**
     * What a session's laps were worked out against: each track's revision, the session's own files
     * and its video files. Any change (lines moved, a track added or removed, the session replaced,
     * GoPro files added) reads the session again.
     */
    private class Cached(
        val trackId: String?,
        val session: ReviewSession?,
        val tracks: Map<String, Int>,
        val files: Stamps,
        val facts: SessionFacts,
    ) {
        fun stale(saved: List<Track>, filesNow: Stamps) = tracks != revisions(saved) || files != filesNow

        fun withoutLaps() = Cached(trackId, null, tracks, files, facts)
    }

    companion object {
        private const val Tag = "ReviewLaps"

        /** Newest sessions read for review; bounds memory (each keeps its laps' paths). */
        private const val MaxSessions = 30

        @Volatile
        private var instance: ReviewLaps? = null

        fun get(context: Context): ReviewLaps = instance ?: synchronized(this) {
            instance ?: ReviewLaps(context.applicationContext).also { instance = it }
        }
    }
}

private fun revisions(saved: List<Track>) = saved.associate { it.id to it.revision }

/** What a session's laps were read from: its own files (metadata, chunks, end marker) and its video files. */
internal data class Stamps(val session: Long, val video: Long) {
    companion object {
        fun of(dir: File) = Stamps(sessionStamp(dir), videoStamp(dir))
    }
}

/**
 * Changes when one of the files the session's laps are read from is added, removed or rewritten:
 * its metadata, end or recovery marker, and chunks. A session replaced by an import of the same id
 * (audit A10) comes with new files. Files written later beside them (the quality report) do not
 * count.
 */
internal fun sessionStamp(dir: File): Long {
    val markers = listOf(SessionFiles.META, SessionFiles.FINISHED, SessionFiles.RECOVERY).map { File(dir, it) }
    return filesStamp(markers.filter { it.isFile } + SessionFiles.chunks(dir))
}

/** Changes when a file in the session's video directory (or the clip index) is added, removed or rewritten. */
private fun videoStamp(dir: File): Long = filesStamp(File(dir, VideoFiles.DIR).listFiles().orEmpty().asList())

private fun filesStamp(files: List<File>): Long =
    files.sumOf { f -> (f.name.hashCode().toLong() * StampPrime + f.length()) * StampPrime + f.lastModified() }

private const val StampPrime = 31L

/**
 * The session's video for review and for the video export (FBBD-152): the phone camera when it
 * recorded, else the first camera with files, as the VBO export picks it. Clips whose file is
 * missing, or cut off by a crash (no index, so it would not play), are left out.
 */
internal fun sessionVideo(dir: File, timeline: VideoTimeline): SessionVideo? {
    val videoDir = File(dir, VideoFiles.DIR)
    val present = timeline.clips.filter { File(videoDir, it.file).let { f -> f.isFile && Mp4Reader.isComplete(f) } }
    val camera = present.firstOrNull { it.camera == CameraIds.PHONE }?.camera ?: present.firstOrNull()?.camera
    return camera?.let { SessionVideo(it, present, videoDir) }
}

/** The session's clip index on the recorder's terms; clips still on a camera are left out (as Sessions). */
internal fun indexedClips(dir: File): List<VideoClip> = try {
    VideoIndex(dir).clips()
        .filter { it.status == ClipStatus.LOCAL }
        .map(::recorderClip)
} catch (e: IOException) {
    DebugLog.w("ReviewLaps", "unreadable clip index in $dir", e)
    emptyList()
}
