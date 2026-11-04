package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.review.SessionFacts
import com.flappedear.betterdriver.analysis.review.atTrack
import com.flappedear.betterdriver.analysis.review.offersVideo
import com.flappedear.betterdriver.analysis.review.recordedFacts
import com.flappedear.betterdriver.analysis.review.sessionRowDetail
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.analysis.review.withLaps
import com.flappedear.betterdriver.format.formatLapTime
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.StoredSession
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import platform.Foundation.NSLock

/**
 * A session's card in Sessions, as Android's (UI-139 C, UI-308 A): "Corolla · 22 min · 14 laps · OBD",
 * its best lap, and the best lap's time for the day's Track day card ([bestMillis]). [video]: the card
 * offers Watch video (FBBD-426), only with a whole video file to play. [facts]: what was read, for the
 * shared list's days (the saved track its laps are timed at, FBBD-453).
 */
class SessionRowFacts(
    val detail: String,
    val best: String?,
    val bestMillis: Long? = null,
    val video: Boolean = false,
    val facts: SessionFacts? = null,
)

/**
 * Reads each listed session's [SessionFacts] with the shared core, as Android's ReviewLaps: its laps
 * at the saved track it was recorded at, and whether car data and heart rate were recorded. Kept until
 * the session's length or the saved tracks change. Off the main thread.
 */
internal class SessionRows(
    private val store: SessionStore,
    private val tracks: () -> List<Track>,
    private val hasVideo: (String) -> Boolean,
) {
    private val lock = NSLock()
    private val cache = HashMap<String, Pair<Stamp, SessionFacts>>()

    /** What a session's facts were read against: its length and every saved track's revision. */
    private data class Stamp(val durationMicros: Long?, val tracks: Map<String, Int>)

    fun read(listed: List<StoredSession>): Map<String, SessionRowFacts> = facts(listed).let { all ->
        listed.associate { s ->
            val facts = all.getValue(s.id)
            val video = hasVideo(s.id)
            val detail = sessionRowDetail(s.info.car, s.durationMicros, facts, video)
            // A listed session is not recording: the recorder adds it once it ends.
            val offers = offersVideo(video, recording = false)
            s.id to SessionRowFacts(detail, facts.bestMillis?.let(::formatLapTime), facts.bestMillis, offers, facts)
        }
    }

    /** Each of [listed]'s [SessionFacts], from the cache when its length and the tracks are unchanged. */
    fun facts(listed: List<StoredSession>): Map<String, SessionFacts> {
        lock.lock()
        try {
            val saved = tracks().filter { it.timeable }
            val revisions = saved.associate { it.id to it.revision }
            cache.keys.retainAll(listed.map { it.id }.toSet())
            return listed.associate { s ->
                val stamp = Stamp(s.durationMicros, revisions)
                val facts = cache[s.id]?.takeIf { it.first == stamp }?.second ?: facts(s, saved)
                cache[s.id] = stamp to facts
                s.id to facts
            }
        } finally {
            lock.unlock()
        }
    }

    // A damaged session shows no facts; the others still do.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun facts(s: StoredSession, saved: List<Track>): SessionFacts = try {
        val data = store.read(s.id)
        val recorded = recordedFacts(data)
        trackFor(data, saved)?.let { recorded.withLaps(replayLaps(s.id, data.sampleSets, it)).atTrack(it) } ?: recorded
    } catch (e: Exception) {
        SessionFacts()
    }
}
