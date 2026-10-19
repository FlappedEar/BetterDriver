package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.RecorderEvent

// The coach's sound in the session validation report (FBBD-183), against FBBD-27, FBBD-47, FBBD-48
// and FBBD-49: text-to-speech time, the total delay to the driver's ear with the headset's own delay
// (measured by the app at the session's start, or set by hand), cues that overlap, cues per straight,
// audio focus and the share of pre-rendered clips.

private const val P95 = 0.95
private const val MaxTtsMicros = 150_000L
private const val MaxLiveTotalMicros = 400_000L
private const val MaxClipTotalMicros = 250_000L

/** Two cues are on one straight when the car did not slow by this much between them. */
private const val BrakingDropKmh = 15.0

internal fun audioMetrics(
    played: List<Pair<LoggedCue, Voiced>>,
    ends: List<RecorderEvent>,
    samples: GnssSamples,
): List<QualityMetric> = listOf(
    tts(played.map { it.second }),
    total(played, "live", MaxLiveTotalMicros),
    total(played, "clip", MaxClipTotalMicros),
    overlap(played.map { it.second }, ends),
    perStraight(played, samples),
    focus(played.map { it.second }),
    clipShare(played.map { it.second }),
)

private const val TtsCriterion = "text-to-speech p95 <= 150 ms (FBBD-47)"

/** Live phrases: from asking the voice to its first audio being ready. */
private fun tts(voiced: List<Voiced>): QualityMetric {
    val label = "Text-to-speech p95"
    val times = voiced.filter { it.path == "live" }.mapNotNull { v ->
        val asked = v.askedMicros
        val ready = v.readyMicros
        if (asked != null && ready != null) (ready - asked).toDouble() else null
    }
    val p95 = nearestRank(times, P95)
        ?: return notMeasured("coach.tts", label, TtsCriterion, "no live phrase with its audio-ready time logged")
    return judged(
        "coach.tts", label, p95 / MicrosPerMilli, "ms", p95 <= MaxTtsMicros, Basis.MEASURED, TtsCriterion,
        "from asking the voice to the engine's first audio, ${times.size} live phrase(s)",
    )
}

/** Trigger to the driver's ear: the cue's delay to sound plus the headset's own delay. */
private fun total(played: List<Pair<LoggedCue, Voiced>>, path: String, limit: Long): QualityMetric {
    val name = if (path == "live") "live voice" else "pre-rendered"
    val id = "coach.total_$path"
    val label = "Total delay p95, $name"
    val criterion = "<= ${limit / MicrosPerMilli} ms to the ear (FBBD-49, FBBD-48, FBBD-47)"
    val own = played.filter { it.second.path == path }
    val delays = own.map { (c, v) -> (v.heardMicros - c.tMicros).toDouble() }
    val p95 = nearestRank(delays, P95)
    val headset = own.firstOrNull { it.second.outDelayMillis != null }?.second
    val source = when (headset?.outDelaySource) {
        "measured" -> "measured by the app at the session's start"
        "set" -> "set by hand, as Android gave no delay"
        else -> "from the tap test"
    }
    return when {
        p95 == null -> notMeasured(id, label, criterion, "no cue said this way")
        own.any { it.second.route == "headset" && it.second.outDelayMillis == null } -> notMeasured(
            id, label, criterion,
            "the headset's own delay is not known: Android gave none at the session's start and none is set",
        )
        else -> judged(
            id, label, p95 / MicrosPerMilli, "ms", p95 <= limit, Basis.DERIVED, criterion,
            "trigger to sound leaving the phone, plus the headset's delay" +
                (headset?.let { " (${it.outDelayMillis} ms, $source)" }.orEmpty()) + "; ${delays.size} cue(s)",
        )
    }
}

/** Overlapping cues named in the check. */
private const val MaxOverlapsShown = 3

private const val OverlapCriterion = "0 overlapping cues (FBBD-27, FBBD-47, FBBD-48)"

/** A cue whose sound started before the one before it ended, or that cut the one before it off. */
private fun overlap(voiced: List<Voiced>, ends: List<RecorderEvent>): QualityMetric {
    val label = "Overlapping cues"
    if (ends.isEmpty()) {
        val why = "the end of each cue's sound is logged from this version on"
        return notMeasured("coach.overlap", label, OverlapCriterion, why)
    }
    val endOf = ends.associate { e -> fields(e.text)["seq"]?.toIntOrNull() to (e.tMicros to fields(e.text)["end"]) }
    val sorted = voiced.sortedBy { it.tMicros }
    val overlaps = sorted.zipWithNext().filter { (a, b) ->
        val (endAt, how) = endOf[a.seq] ?: return@filter false
        b.tMicros < endAt || how == "stopped"
    }
    // Which cues, so a FAIL can be told apart: one started early, or one cut off.
    val shown = overlaps.take(MaxOverlapsShown).joinToString { (a, b) ->
        val (endAt, how) = endOf.getValue(a.seq)
        "cue ${a.seq} $how at ${"%.1f".format(endAt / MicrosPerSecond.toDouble())} s, " +
            "cue ${b.seq} at ${"%.1f".format(b.tMicros / MicrosPerSecond.toDouble())} s"
    }
    return judged(
        "coach.overlap", label, overlaps.size.toDouble(), "", overlaps.isEmpty(), Basis.MEASURED, OverlapCriterion,
        "cues whose sound started before the one before it ended, or cut it off; ${sorted.size} cue(s)" +
            (if (shown.isEmpty()) "" else ": $shown"),
    )
}

private const val StraightCriterion = "at most 1 cue per straight (FBBD-27)"

/**
 * Cues said on a straight that already had one: two cues are on one straight when the GNSS speed did
 * not fall by 15 km/h from its highest between their sounds (no braking zone between them). Praise
 * followed by the straight's corner cue is one pair the coach means to say (UI-94 A), so it is not counted.
 */
private fun perStraight(played: List<Pair<LoggedCue, Voiced>>, s: GnssSamples): QualityMetric {
    val label = "Extra cues on a straight"
    val sorted = played.sortedBy { it.second.tMicros }
    if (sorted.size < 2 || s.size < 2) {
        return notMeasured("coach.per_straight", label, StraightCriterion, "fewer than two cues")
    }
    val extra = sorted.zipWithNext().count { (a, b) ->
        val praiseThenCue = a.first.kind == PraiseKind && b.first.kind == CornerKind
        !praiseThenCue && !brakedBetween(s, a.second.tMicros, b.second.tMicros)
    }
    return judged(
        "coach.per_straight", label, extra.toDouble(), "", extra == 0, Basis.DERIVED, StraightCriterion,
        "cue pairs with no slowing of 15 km/h or more between their sounds, praise before a cue aside; " +
            "${sorted.size} cue(s)",
    )
}

private const val PraiseKind = "Praise"
private const val CornerKind = "Corner"

private fun brakedBetween(s: GnssSamples, from: Long, to: Long): Boolean {
    var top = Double.NEGATIVE_INFINITY
    var i = maxOf(0, s.lastAtOrBefore(from))
    while (i < s.size && s.t[i] <= to) {
        val v = s.speed[i]
        if (v.isFinite()) {
            top = maxOf(top, v)
            if (top - v >= BrakingDropKmh) return true
        }
        i++
    }
    return false
}

private const val FocusCriterion = "other audio ducks under every cue: focus granted (FBBD-47)"

private fun focus(voiced: List<Voiced>): QualityMetric {
    val label = "Audio focus granted"
    val known = voiced.mapNotNull { it.focus }
    if (known.isEmpty()) {
        return notMeasured("coach.focus", label, FocusCriterion, "audio focus is logged per cue from this version on")
    }
    val percent = known.count { it == "granted" } * Percent / known.size
    return judged(
        "coach.focus", label, percent, "%", percent >= Percent, Basis.MEASURED, FocusCriterion,
        "cues for which Android granted ducking focus; music then ducks and comes back on its own; " +
            "${known.size} cue(s)",
    )
}

private const val ClipCriterion = "pre-rendered clips cover 100 % of the phrases, fallbacks logged (FBBD-48)"

private fun clipShare(voiced: List<Voiced>): QualityMetric {
    val label = "Phrases from clips"
    val clips = voiced.count { it.path == "clip" }
    if (clips == 0) {
        return notMeasured("coach.clips", label, ClipCriterion, "no pre-rendered clip played (voice set to live)")
    }
    val percent = clips * Percent / voiced.size
    val devices = voiced.groupingBy { it.device ?: it.route }.eachCount().entries
        .joinToString { "${it.key} ${it.value}" }
    return judged(
        "coach.clips", label, percent, "%", percent >= Percent, Basis.MEASURED, ClipCriterion,
        "${voiced.size - clips} of ${voiced.size} phrase(s) fell back to live text-to-speech; outputs: $devices",
    )
}
