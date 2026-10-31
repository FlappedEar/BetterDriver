package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryAlgorithm
import com.flappedear.betterdriver.analysis.coaching.CoachingHistoryStore
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.coaching.keepCoachingHistory
import com.flappedear.betterdriver.analysis.coaching.originOf
import com.flappedear.betterdriver.analysis.identity.analyzeKeepingIdentity
import com.flappedear.betterdriver.analysis.progression.CornerMapping
import com.flappedear.betterdriver.analysis.progression.ProgressionDay
import com.flappedear.betterdriver.analysis.progression.ProgressionGrouping
import com.flappedear.betterdriver.analysis.progression.systemDayZone
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.review.savedTrackId
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.feature.analysis.ProgressionChoice
import com.flappedear.betterdriver.feature.analysis.ProgressionPeriod
import com.flappedear.betterdriver.feature.analysis.ProgressionUi
import com.flappedear.betterdriver.feature.analysis.progressionScreenUi
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.timeIntervalSince1970

/** At most this many of the track's sessions are analysed for the progression each time it opens, newest first. */
internal const val MaxProgressionAdds = 20

/** Of at most this many of the track's sessions, newest first; sessions at other tracks are not counted. */
internal const val MaxProgressionScan = 120

private const val MillisPerSecond = 1000.0

/** The saved track a session's progression is at (FBBD-181): its id and the name it is shown with. */
data class ProgressionTrack(val id: String, val name: String)

/**
 * The progression at a track (FBBD-181) on the iPhone and iPad, as Android's TrackProgressionRoute
 * makes it: each session at the track is analysed with the track's corner identity (kept with the
 * track, as Android's analyzeAtTrack keeps it) into its coaching history record (FBBD-180), from
 * which the shared core works out and words the screen. The records are kept in the coaching
 * [history] Android keeps (FBBD-364), which the summary's coaching card reads too: a track opened
 * again shows at once, only sessions without a current record are read, and a record outlives its
 * session, as on Android. Thread-safe: the app reads sessions off the main thread while the screen
 * is redrawn.
 */
@Suppress("TooGenericExceptionCaught") // A damaged session never stops the others.
class TrackProgressions internal constructor(
    private val store: SessionStore,
    private val tracks: TrackLibrary,
    private val history: CoachingHistoryStore,
) {
    private val lock = NSRecursiveLock()

    /** Sessions already tried for a track ("track/session"), so none is analysed twice in vain. */
    private val tried = mutableSetOf<String>()

    /** The saved track session [id] was driven at, as its review finds it; null when none (no progression). */
    fun trackOf(id: String): ProgressionTrack? = try {
        trackFor(store.read(id), tracks.list())?.let { ProgressionTrack(it.id, it.displayName) }
    } catch (_: Exception) {
        null
    }

    /**
     * Analyses the sessions at track [trackId] with no current record, newest first, as Android's
     * addNewest: at most [MaxProgressionAdds] tried, of the [MaxProgressionScan] newest of the track's
     * sessions. [left] hears, before each, how many more may be tried at most, and 0 at the end;
     * [added] hears after each session added, so the screen shows it. Returns how many were tried.
     * Never throws: a session that cannot be read is left out.
     */
    fun addNewest(trackId: String, left: (Int) -> Unit, added: () -> Unit): Int {
        val sessions = try {
            store.list().filter { it.state != SessionState.RECORDING }.map { it.id }
        } catch (_: Exception) {
            emptyList()
        }
        val saved = tracks.list()
        val kept = records(trackId).filter { it.algorithm == CoachingHistoryAlgorithm }
            .map { it.sessionId }.toMutableSet()
        fun triedHere(id: String) = locked { "$trackId/$id" in tried }
        val pending = sessions.count { it !in kept && !triedHere(it) }
        var passed = 0
        var seen = 0
        var tries = 0
        for (id in sessions) {
            if (seen >= MaxProgressionScan || tries >= MaxProgressionAdds) break
            if (id in kept) {
                seen++
            } else if (!triedHere(id)) {
                left(minOf(pending - passed, MaxProgressionAdds - tries))
                passed++
                val result = add(trackId, id, saved)
                if (result != Added.Elsewhere) {
                    seen++
                    tries++
                }
                if (result == Added.Kept) {
                    kept += id
                    added()
                }
            }
        }
        left(0)
        return tries
    }

    /**
     * The progression screen of track [trackId] named [title], as Android's progressionScreenUi: the
     * sessions grouped by [grouping] over [period] counted back from today, or [day] alone, the
     * sessions in [excluded] left out; [adding] sessions are still being read. Days are the phone's.
     */
    @Suppress("LongParameterList") // Every choice the screen has, as Android's.
    fun screen(
        trackId: String,
        title: String,
        grouping: ProgressionGrouping,
        period: ProgressionPeriod,
        day: ProgressionDay?,
        excluded: Set<String>,
        adding: Int,
    ): ProgressionUi {
        val kept = records(trackId)
        val zone = systemDayZone()
        val today = ProgressionDay.at((NSDate().timeIntervalSince1970 * MillisPerSecond).toLong(), zone)
        val mapping = CornerMapping.of(tracks.get(trackId)?.identity)
        val choice = ProgressionChoice(grouping, period, day, excluded)
        return progressionScreenUi(title, kept, choice, today, zone, mapping).copy(adding = adding)
    }

    /** Analyses session [id] for the progression at [trackId], as Android's addToHistory; [saved] the saved tracks. */
    @Suppress("ReturnCount") // Another track's session, or none of a track at all: nothing to analyse.
    private fun add(trackId: String, id: String, saved: List<Track>): Added {
        locked { tried += "$trackId/$id" }
        return try {
            val named = savedTrackId(store.read(id, samples = false).events)
            if (named != null && named != trackId) return Added.Elsewhere
            val data = store.read(id)
            val track = trackFor(data, saved)?.takeIf { it.id == trackId } ?: return Added.Elsewhere
            val start = data.info.startUtcMillis
            // With the identity stored now: an earlier session of the loop may have kept a new one.
            val identity = tracks.get(trackId)?.identity ?: track.identity
            val analysis = analyzeKeepingIdentity(telemetrySessionOf(data, track), identity, start) { expected, made ->
                tracks.updateIdentity(trackId, expected, made)?.identity
            }
            val coached = CoachEvents.coachedSession(data.events)
            keepCoachingHistory(history, trackId, data.id, start, analysis, coached, originOf(data.info)) {
                tracks.get(trackId) != null
            }
            if (history.get(trackId, data.id) == null) Added.Analysed else Added.Kept
        } catch (_: Exception) {
            Added.Failed
        }
    }

    /** The records at [trackId], as Android's readHistory; none when they cannot be read. */
    private fun records(trackId: String): List<SessionCoachingRecord> = try {
        history.history(trackId)
    } catch (_: Exception) {
        emptyList()
    }

    private inline fun <T> locked(body: () -> T): T {
        lock.lock()
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }

    /** What came of a session tried for the progression, as Android's AddResult. */
    private enum class Added {
        /** Analysed at the track, its record kept. */
        Kept,

        /** Analysed at the track, but its corners are not the track's: no record. */
        Analysed,

        /** Not read or not analysed: it may be the track's, so it counts as tried. */
        Failed,

        /** Another track's session, or one at no track: it leaves the limits as they are. */
        Elsewhere,
    }
}
