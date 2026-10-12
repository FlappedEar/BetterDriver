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
 * session is read again only when its track's lines change. Disk work runs on one background
 * thread; [state] is Compose state.
 */
class ReviewLaps private constructor(context: Context) {
    private val store = SessionStore(sessionsDir(context))
    private val tracks = TrackStore(tracksDir(context))
    private val io = Executors.newSingleThreadExecutor { Thread(it, "review-io") }
    private val main = Handler(Looper.getMainLooper())

    /** By session id. io thread only. */
    private val cache = HashMap<String, Cached>()

    var state by mutableStateOf<ReviewLoad>(ReviewLoad.Loading(0, 0))
        private set

    /** Reads new and changed sessions, then publishes every track with laps. */
    fun load() = io.execute {
        val saved = tracks.all().filter { it.timeable }
        val sessions = store.list().filter { it.state != SessionState.RECORDING }.take(MaxSessions)
        cache.keys.retainAll(sessions.map { it.id }.toSet())
        sessions.forEachIndexed { i, s ->
            val known = cache[s.id]
            if (known == null || known.stale(saved, videoStamp(s.dir))) cache[s.id] = read(s, saved)
            post(ReviewLoad.Loading(i + 1, sessions.size))
        }
        post(ReviewLoad.Ready(group(sessions, saved)))
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
        if (track == null) {
            Cached(null, null, revisions(saved), videoStamp(s.dir))
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
            Cached(track.id, session.takeIf { it.laps.isNotEmpty() }, revisions(saved), videoStamp(s.dir))
        }
    } catch (e: Exception) {
        android.util.Log.w(Tag, "reading session ${s.id} failed", e)
        Cached(null, null, revisions(saved), videoStamp(s.dir))
    }

    /**
     * What a session's laps were worked out against: each track's revision, and its video files. Any
     * change (lines moved, a track added or removed, GoPro files added) reads the session again.
     */
    private class Cached(
        val trackId: String?,
        val session: ReviewSession?,
        val tracks: Map<String, Int>,
        val video: Long,
    ) {
        fun stale(saved: List<Track>, videoNow: Long) = tracks != revisions(saved) || video != videoNow
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

/** Changes when a file in the session's video directory (or the clip index) is added, removed or rewritten. */
private fun videoStamp(dir: File): Long = File(dir, VideoFiles.DIR).listFiles().orEmpty()
    .sumOf { f -> (f.name.hashCode().toLong() * StampPrime + f.length()) * StampPrime + f.lastModified() }

private const val StampPrime = 31L

/**
 * The session's video for review: the phone camera when it recorded, else the first camera with
 * files, as the VBO export picks it. Clips whose file is missing, or cut off by a crash (no index, so
 * it would not play), are left out.
 */
private fun sessionVideo(dir: File, timeline: VideoTimeline): SessionVideo? {
    val videoDir = File(dir, VideoFiles.DIR)
    val present = timeline.clips.filter { File(videoDir, it.file).let { f -> f.isFile && Mp4Reader.isComplete(f) } }
    val camera = present.firstOrNull { it.camera == CameraIds.PHONE }?.camera ?: present.firstOrNull()?.camera
    return camera?.let { SessionVideo(it, present, videoDir) }
}

/** The session's clip index on the recorder's terms; clips still on a camera are left out (as Sessions). */
private fun indexedClips(dir: File): List<VideoClip> = try {
    VideoIndex(dir).clips()
        .filter { it.status == ClipStatus.LOCAL }
        .map { c ->
            VideoClip(c.kind.name.lowercase(), c.fileName, c.sessionOffsetUs, c.durationUs?.let(c::sessionTimeUs))
        }
} catch (e: IOException) {
    android.util.Log.w("ReviewLaps", "unreadable clip index in $dir", e)
    emptyList()
}
