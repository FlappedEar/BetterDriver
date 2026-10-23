package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.focus.median
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import kotlin.math.abs

// The track day (FBBD-315): every session of one day at one track, read on the axis and corners of
// the day's fastest lap, as the line analysis reads a session (FBBD-306). Two kinds of time are in a
// corner. On the best lap: what the day's fastest lap loses to the day's target there (a time driven
// twice, FBBD-177), the line plan's question. On every lap: what a typical lap at the day's pace
// loses there, which is where his laps go once he is up to speed and what the next day can win
// back most surely. The day's focus is the corners with most of the second and something to do
// there; the next day at the track proves it ([dayProof], against the day's [DayPlan]). How each
// session went (its own push laps) is the day's trend: what he learned over the day and where he
// stopped getting faster.

/**
 * 107 %, the limit racing uses for a lap that counts. A lap more than this share slower than its
 * session's fastest is not a push lap (a cool-down, traffic): a session's typical lap is its push
 * laps' median. A lap more than this share slower than the day's fastest is not at the day's pace:
 * a morning still learning the track says where he was then, not where the time is now.
 */
const val PushLapShare = 1.07

/** One session of the day: its laps, its fastest and its typical push lap. */
data class DaySession(
    val label: String,
    val id: String,
    /** Eligible laps; of those the push laps ([PushLapShare] of its fastest) and those at the day's pace. */
    val laps: Int,
    val pushLaps: Int,
    val paceLaps: Int,
    val bestLapSeconds: Double?,
    /** The median push lap; null without one. */
    val typicalLapSeconds: Double?,
)

/** One session through one corner: its fastest push pass and its median one; null without a pass. */
data class DayCornerSession(val label: String, val id: String, val bestSeconds: Double?, val typicalSeconds: Double?)

/** What kind of time a corner of the day has. */
enum class DayLoss {
    /** Even the day's fastest lap loses time to the target here: something to learn. */
    BestLap,

    /** The fastest lap matches the target, the typical lap does not: something to repeat. */
    Consistency,

    /** Neither beyond timing noise. */
    None,
}

/**
 * One corner of the day. [target] is the day's second fastest pass through the window over every
 * lap of every session (FBBD-177); [bestLap] the day's fastest lap through it; [typical] the pass
 * closest to the day's median there of the sessions' median passes. Gains are against the target,
 * 0 within [LineThresholds.gainSeconds].
 */
data class DayCorner(
    val segmentId: String,
    val name: String,
    val turnsLeft: Boolean,
    val startMeters: Double,
    val endMeters: Double,
    val target: LinePass,
    val bestLap: LinePass,
    val typical: LinePass?,
    /** The median pass through the window of the laps at the day's pace. */
    val typicalSeconds: Double,
    /** Passes through the window of the laps at the day's pace. */
    val passes: Int,
    /** The day's fastest lap against the target: what the line plan finds here. */
    val bestLapGainSeconds: Double,
    /** The typical pass against the target: what a lap at the day's pace loses here on average. */
    val perLapGainSeconds: Double,
    /** What the target does differently from the fastest lap, and from the typical pass. */
    val bestLapAdvice: CornerAdvice,
    val typicalAdvice: CornerAdvice,
    /** In the order the sessions were driven. */
    val sessions: List<DayCornerSession>,
) {
    val loss: DayLoss
        get() = when {
            bestLapGainSeconds > 0.0 -> DayLoss.BestLap
            perLapGainSeconds > 0.0 -> DayLoss.Consistency
            else -> DayLoss.None
        }

    /**
     * How much faster the typical pass was in the day's last session with one than in its first:
     * positive got faster over the day. Null with fewer than two sessions through the corner.
     */
    val daySeconds: Double?
        get() {
            val typical = sessions.mapNotNull { it.typicalSeconds }
            return if (typical.size < 2) null else typical.first() - typical.last()
        }

    /** What to do here: the typical pass's advice for time on every lap, the best lap's otherwise. */
    val advice: CornerAdvice get() = if (typicalAdvice.any) typicalAdvice else bestLapAdvice
}

/**
 * One day at one track: the sessions in the order they were driven, and every corner of the day's
 * fastest lap (session [referenceSessionId], lap [referenceLapNumber]).
 */
data class DayAnalysis(
    val sessions: List<DaySession>,
    val referenceSessionId: String,
    val referenceSessionLabel: String,
    val referenceLapNumber: Int,
    val referenceLapSeconds: Double,
    /** The median lap at the day's pace. */
    val typicalLapSeconds: Double,
    /** In track order. */
    val corners: List<DayCorner>,
    /** The track's corner identity revision the corner ids are in (FBBD-172); null without one. */
    val identityRevision: Int? = null,
    /** The fastest lap's axis origin: where the corners' line points are measured from. */
    val origin: GeoCoordinate = GeoCoordinate(0.0, 0.0),
) {
    /** Most time per lap first. */
    val ranked: List<DayCorner>
        get() = corners.sortedWith(
            compareByDescending<DayCorner> { it.perLapGainSeconds }.thenByDescending { it.bestLapGainSeconds },
        )

    /** What the day's fastest lap leaves: the perfect lap of the day is the fastest less this. */
    val bestLapGainSeconds: Double get() = corners.sumOf { it.bestLapGainSeconds }

    /** What a typical lap at pace loses to the targets, corner by corner; the windows tile the lap. */
    val perLapGainSeconds: Double get() = corners.sumOf { it.perLapGainSeconds }

    /** The targets one after the other. */
    val perfectLapSeconds: Double get() = referenceLapSeconds - bestLapGainSeconds

    /**
     * What to work on next time (at most [count]): the corners where a typical lap loses most and
     * the target does something different there, most time first.
     */
    fun focus(count: Int = LinePlan.FocusCorners): List<DayCorner> =
        ranked.filter { it.perLapGainSeconds > 0.0 && it.advice.any }.take(count)

    /** How much faster the day's last session's typical lap was than its first's; null with one. */
    val daySeconds: Double?
        get() {
            val typical = sessions.mapNotNull { it.typicalLapSeconds }
            return if (typical.size < 2) null else typical.first() - typical.last()
        }
}

/**
 * The day of [sessions] (one track, one calendar day, in the order they were driven), on the axis
 * and corners of the day's fastest lap; [analyze] analyses that lap's session as the app does (with
 * the track's corner identity, so the corners are CornerIds). Null with fewer than two sessions with
 * a lap, or when the fastest lap's session has no corners.
 */
@Suppress("ReturnCount")
fun analyzeDay(
    sessions: List<LineSession>,
    analyze: (LineSession) -> SessionAnalysis = { analyzeSession(it.session, it.laps) },
    thresholds: LineThresholds = LineThresholds(),
): DayAnalysis? {
    val driven = sessions.filter { s -> s.laps.timedLaps.any { it.referenceEligible } }
    if (driven.size < 2) return null
    val fastest = driven.minBy { s -> s.laps.timedLaps.filter { it.referenceEligible }.minOf { it.durationSeconds } }
    val analysis = analyze(fastest)
    val axis = analysis.axis ?: return null
    val segments = analysis.segments ?: return null
    val referenceNumber = analysis.axisLapNumber ?: return null
    val windows = cornerWindows(segments.segments, axis)
    if (windows.isEmpty()) return null
    val reader = LineReader(analysis, axis, segments.revision, windows)
    val best = analysis.laps.timedLaps.firstOrNull { it.number == referenceNumber } ?: return null
    val pace = best.durationSeconds * PushLapShare
    val own = reader.read(fastest.copy(laps = analysis.laps), referenceNumber, pace) ?: return null
    val reference = own.reference ?: return null
    val inOrder = driven.mapNotNull { s -> (if (s === fastest) own else reader.read(s, null, pace))?.let { s to it } }
    val line = lineOf(reader, listOf(own) + inOrder.filter { it.first !== fastest }.map { it.second }, thresholds)
        ?: return null
    val laps = inOrder.map { (s, lines) -> dayLaps(s, lines.pace!!, pace) }
    val corners = line.corners.map { c ->
        dayCorner(c, windows.indexOfFirst { it.corner.id == c.segmentId }, laps, axis.lengthMeters, thresholds)
    }
    val atPace = laps.flatMap { l -> l.atPace.map { l.pace.lapSeconds[it] } }
    return DayAnalysis(
        sessions = laps.map { l ->
            val push = l.push.map { l.pace.lapSeconds[it] }
            DaySession(
                l.session.label, l.session.id, l.pace.lapSeconds.size, push.size, l.atPace.size, push.minOrNull(),
                median(push),
            )
        },
        referenceSessionId = fastest.id,
        referenceSessionLabel = fastest.label,
        referenceLapNumber = reference.number,
        referenceLapSeconds = reference.lapSeconds,
        typicalLapSeconds = median(atPace),
        corners = corners,
        identityRevision = analysis.identity?.number?.takeIf { analysis.identified },
        origin = axis.origin,
    )
}

/** One session's laps as the day reads them: indices into [pace] of its push laps and its laps at the day's pace. */
private class DayLaps(val session: LineSession, val pace: PaceLaps, val push: List<Int>, val atPace: List<Int>)

/** [session]'s push laps (within [PushLapShare] of its fastest) and its laps at the day's pace, up to [dayPace] s. */
private fun dayLaps(session: LineSession, pace: PaceLaps, dayPace: Double): DayLaps {
    val times = pace.lapSeconds
    val limit = times.minOf { it } * PushLapShare
    val push = times.indices.filter { times[it] <= limit }
    return DayLaps(session, pace, push, times.indices.filter { times[it] <= dayPace })
}

/** Corner [c] of the day's line analysis, window [k], over every session's [laps]. */
private fun dayCorner(
    c: CornerLine,
    k: Int,
    laps: List<DayLaps>,
    axisLengthMeters: Double,
    thresholds: LineThresholds,
): DayCorner {
    val atPace = laps.flatMap { l -> l.atPace.mapNotNull { l.pace.seconds[k][it] } }
    val typicalSeconds = if (atPace.isEmpty()) c.reference.seconds else median(atPace)
    // Each session's median pass at pace is kept, not every pass: the one closest to the day's median.
    val typical = laps.mapNotNull { l -> l.pace.median[k]?.copy(source = l.session.label, sourceId = l.session.id) }
        .minByOrNull { abs(it.seconds - typicalSeconds) }
    val perLap = (typicalSeconds - c.target.seconds).takeIf { it >= thresholds.gainSeconds } ?: 0.0
    return DayCorner(
        c.segmentId, c.name, c.turnsLeft, c.startMeters, c.endMeters, c.target, c.reference, typical,
        typicalSeconds, atPace.size, c.gainSeconds, perLap, c.advice,
        if (perLap > 0.0 && typical != null) {
            advise(typical, c.target, c.turnsLeft, axisLengthMeters, thresholds)
        } else {
            CornerAdvice()
        },
        laps.map { l ->
            val mine = l.push.mapNotNull { l.pace.seconds[k][it] }
            val typical = if (mine.isEmpty()) null else median(mine)
            DayCornerSession(l.session.label, l.session.id, mine.minOrNull(), typical)
        },
    )
}
