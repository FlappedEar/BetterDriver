package com.flappedear.betterdriver.analysis.summary

import com.flappedear.betterdriver.analysis.laps.LapSession
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import kotlin.math.roundToLong

// The lap-level part of FlappedEar Telemetry day/session_summary.dart (Apache 2.0): a session's
// best lap against the best of every earlier session at the same track, and how repeatable its
// laps were against the session before. Nothing is recalculated: every number comes from the
// sessions' own lap detection.

private const val MillisPerSecond = 1000.0

/** An earlier session at the same track: its name on screen and its laps. */
data class EarlierSession(val label: String, val laps: LapSession)

/** One session against the earlier sessions at its track. */
data class SessionProgress(
    /** Earlier sessions at the track, with or without a counted lap. */
    val earlierSessions: Int = 0,
    /** True when only the newest [earlierSessions] were compared: older ones here may exist. */
    val earlierLimited: Boolean = false,
    /** Laps of this session that count. */
    val eligibleLapCount: Int = 0,
    /** This session's best lap; null without a counted lap. */
    val bestLapSeconds: Double? = null,
    /** The quickest best lap of the earlier sessions, and the session that set it. */
    val earlierBestSeconds: Double? = null,
    val earlierBestLabel: String? = null,
    /** The interquartile range of this session's counted lap times; null below three laps. */
    val lapSpreadSeconds: Double? = null,
    /**
     * The median of this session's counted lap times, its typical lap (FBBD-175): with
     * [lapSpreadSeconds], how consistent the session was, never swayed by one slow lap; null below three.
     */
    val lapMedianSeconds: Double? = null,
    /** The newest earlier session with a counted lap: its name, best lap and lap spread. */
    val previousLabel: String? = null,
    val previousBestSeconds: Double? = null,
    val previousSpreadSeconds: Double? = null,
    /** Each segment's typical time against the session before, in track order. */
    val changes: List<SegmentChange> = emptyList(),
) {
    /** Best lap minus the earlier best, rounded to the millisecond as times are shown; negative is quicker. */
    val bestDeltaSeconds: Double?
        get() {
            val best = bestLapSeconds ?: return null
            val earlier = earlierBestSeconds ?: return null
            return ((best - earlier) * MillisPerSecond).roundToLong() / MillisPerSecond
        }

    /** Quicker than every earlier session, by at least a millisecond as times are shown. */
    val newBest: Boolean get() = (bestDeltaSeconds ?: 0.0) < 0

    /** Lap spread minus the previous session's: negative is steadier. */
    val spreadDeltaSeconds: Double?
        get() {
            val now = lapSpreadSeconds ?: return null
            val before = previousSpreadSeconds ?: return null
            return now - before
        }
}

private fun LapSession.best(): Double? = fastestLapIndex?.let { timedLaps[it].durationSeconds }

private fun LapSession.consistency() =
    summarizeConsistency(timedLaps.filter { it.referenceEligible }.map { it.durationSeconds }).takeIf { it.available }

private fun LapSession.spread(): Double? = consistency()?.interquartileRange

/** [current] against [earlier], newest first. */
fun summarizeProgress(current: LapSession, earlier: List<EarlierSession>): SessionProgress {
    val timed = earlier.filter { it.laps.best() != null }
    val previous = timed.firstOrNull()
    val quickest = timed.minByOrNull { it.laps.best()!! } // the newest on a tie
    return SessionProgress(
        earlierSessions = earlier.size,
        eligibleLapCount = current.timedLaps.count { it.referenceEligible },
        bestLapSeconds = current.best(),
        earlierBestSeconds = quickest?.laps?.best(),
        earlierBestLabel = quickest?.label,
        lapSpreadSeconds = current.spread(),
        lapMedianSeconds = current.consistency()?.median,
        previousLabel = previous?.label,
        previousBestSeconds = previous?.laps?.best(),
        previousSpreadSeconds = previous?.laps?.spread(),
    )
}

/**
 * The best lap against the earlier sessions here, in words, the same on Android and iOS. With
 * [SessionProgress.earlierLimited] only the newest sessions were read, so the best is theirs rather
 * than of every session here.
 */
fun progressBestText(progress: SessionProgress): String {
    val limited = progress.earlierLimited
    val sessions = "${if (limited) "last" else "earlier"} ${progress.earlierSessions} sessions"
    return when {
        progress.earlierSessions == 0 && limited -> "No recent earlier session at this track."
        progress.earlierSessions == 0 -> "First session at this track."
        progress.bestDeltaSeconds == null -> "No counted lap in the $sessions here."
        limited && progress.newBest -> "New best of your $sessions here"
        limited -> "Best lap against your $sessions here"
        progress.newBest -> "New best lap here"
        else -> "Best lap against your best here"
    }
}
