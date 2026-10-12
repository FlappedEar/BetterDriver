package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import kotlin.math.roundToLong

// The coach part of the session validation report (FBBD-183), against FBBD-27 and FBBD-49: each
// cue's delay from trigger to sound, the margin it was heard with before the braking zone and
// where it went.

/** `:coach`'s CoachEvents.CUE and CoachEvents.PROBLEM; `:analysis` does not depend on the coach. */
private const val CueKind = "coach_cue"
private const val ProblemKind = "coach_problem"

private const val MaxLiveDelayMicros = 400_000L
private const val MaxClipDelayMicros = 250_000L
private const val P95 = 0.95
private const val MinOnTimePercent = 95.0

/** 50 m before the braking zone at 150 km/h (FBBD-27): 1.2 s at any speed. */
private const val MarginMicros = 1_200_000L
private const val KmhPerMps = 3.6

/** Below this speed a margin in time means nothing. */
private const val MinMarginKmh = 5.0

/** A cue as the coach logged it: when it was triggered, its number, and metres to the braking zone. */
private class LoggedCue(val tMicros: Long, val seq: Int, val corner: String?, val zoneMeters: Double?)

/** When a cue's sound started, how it was said and where it went. */
private class Voiced(val tMicros: Long, val seq: Int, val path: String, val route: String)

internal fun coachSection(events: List<RecorderEvent>, samples: GnssSamples): QualitySection {
    val cues = events.ofKind(CueKind).mapNotNull(::loggedCue)
    val unsaid = events.ofKind(ProblemKind).size
    if (cues.isEmpty() && unsaid == 0) {
        return QualitySection("Coach", listOf(notMeasured("coach.cues", "Cues", null, "no cue in this session")))
    }
    val voiced = events.ofKind(EventKinds.COACH_VOICE).mapNotNull(::voiced).associateBy { it.seq }
    val played = cues.mapNotNull { c -> voiced[c.seq]?.let { c to it } }
    val margins = played.mapNotNull { (c, v) -> margin(c, v, samples)?.let { Triple(c, v, it) } }
    val metrics = buildList {
        add(
            info(
                "coach.cues", "Cues said", cues.size.toDouble(), "", Basis.MEASURED,
                "$unsaid more not said (too late, or no voice)",
            ),
        )
        addAll(delays(played))
        addAll(marginMetrics(margins.map { it.third }))
        add(headset(played.map { it.second }))
        add(
            notMeasured(
                "coach.overlap", "Overlapping cues", "0 overlapping cues (FBBD-27)",
                "the end of each cue's sound is not logged",
            ),
        )
    }
    val notes = played.map { (c, v) ->
        val m = margins.firstOrNull { it.first === c }?.third
        val zone = m?.let { ", ${it.meters.roundToLong()} m to the braking zone" }.orEmpty()
        val where = c.corner?.let { " (T$it)" }.orEmpty()
        val delay = ms(v.tMicros - c.tMicros)
        QualityNote(c.tMicros, "cue ${c.seq}$where: $delay ms to sound, ${v.path}, ${v.route}$zone")
    }
    return QualitySection("Coach", metrics, notes)
}

private fun loggedCue(e: RecorderEvent): LoggedCue? {
    val f = fields(e.text.substringBefore(" text="))
    val seq = f["seq"]?.toIntOrNull() ?: return null
    return LoggedCue(e.tMicros, seq, f["corner"], f["zone_m"]?.toDoubleOrNull())
}

private fun voiced(e: RecorderEvent): Voiced? {
    val f = fields(e.text)
    val seq = f["seq"]?.toIntOrNull() ?: return null
    return Voiced(e.tMicros, seq, f["path"].orEmpty(), f["route"].orEmpty())
}

/** The 95th percentile of trigger to sound, for each way of saying cues. */
private fun delays(played: List<Pair<LoggedCue, Voiced>>): List<QualityMetric> {
    if (played.isEmpty()) {
        return listOf(
            notMeasured(
                "coach.delay", "Cue delay p95", "<= 400 ms live, <= 250 ms pre-rendered (FBBD-27, FBBD-49)",
                "the start of each cue's sound is logged from this version on",
            ),
        )
    }
    val how = "from the fix that triggered the cue to its first audio leaving the phone, as the voice " +
        "reported it; a Bluetooth headset's own delay comes on top"
    return listOf("live" to MaxLiveDelayMicros, "clip" to MaxClipDelayMicros).mapNotNull { (path, limit) ->
        val ds = played.filter { it.second.path == path }.map { (c, v) -> (v.tMicros - c.tMicros).toDouble() }
        val p95 = nearestRank(ds, P95) ?: return@mapNotNull null
        val name = if (path == "live") "live voice" else "pre-rendered"
        judged(
            "coach.delay_$path", "Cue delay p95, $name", p95 / MicrosPerMilli, "ms", p95 <= limit, Basis.MEASURED,
            "<= ${limit / MicrosPerMilli} ms (FBBD-27, FBBD-49)", "$how; ${ds.size} cue(s)",
        )
    }
}

/** How far before the braking zone a cue's sound started, in metres and in time at the speed then. */
private class Margin(val meters: Double, val micros: Long)

/**
 * The margin of a corner cue: its distance to the braking zone when triggered, less the distance
 * the GNSS speed says the car drove until the sound started. Null for a lap time or a cue too slow
 * to tell.
 */
private fun margin(c: LoggedCue, v: Voiced, s: GnssSamples): Margin? {
    val zone = c.zoneMeters
    val kmh = s.speed.getOrNull(s.lastAtOrBefore(v.tMicros))?.takeIf { it.isFinite() && it >= MinMarginKmh }
    if (zone == null || kmh == null) return null
    val meters = zone - driven(s, c.tMicros, v.tMicros)
    return Margin(meters, (meters / (kmh / KmhPerMps) * MicrosPerSecond).roundToLong())
}

/** Metres driven from [from] to [to] by the GNSS speed, each sample's speed held until the next. */
private fun driven(s: GnssSamples, from: Long, to: Long): Double {
    var meters = 0.0
    var i = maxOf(0, s.lastAtOrBefore(from))
    while (i < s.size && s.t[i] < to) {
        val a = maxOf(s.t[i], from)
        val b = minOf(s.t.getOrElse(i + 1) { to }, to)
        if (b > a && s.speed[i].isFinite()) meters += s.speed[i] / KmhPerMps * seconds(b - a)
        i++
    }
    return meters
}

private fun marginMetrics(margins: List<Margin>): List<QualityMetric> {
    val criterion = ">= 50 m before the braking zone at 150 km/h (1.2 s) on >= 95 % of cues (FBBD-27)"
    if (margins.isEmpty()) {
        val why = "no corner cue with its distance to the braking zone and its sound start logged"
        return listOf(notMeasured("coach.margin", "Cues heard in time", criterion, why))
    }
    val onTime = margins.count { it.micros >= MarginMicros } * Percent / margins.size
    val inZone = margins.count { it.meters <= 0 }
    val how = "distance to the braking zone the coach worked out when it triggered the cue, less the distance " +
        "the GNSS speed says the car drove until the sound started; ${margins.size} corner cue(s)"
    return listOf(
        judged(
            "coach.margin", "Cues heard in time", onTime, "%", onTime >= MinOnTimePercent, Basis.DERIVED,
            criterion, how,
        ),
        judged(
            "coach.in_zone", "Cues starting in a braking zone", inZone.toDouble(), "", inZone == 0, Basis.DERIVED,
            "0 cues inside a braking zone (FBBD-27)", how,
        ),
    )
}

private fun headset(voiced: List<Voiced>): QualityMetric {
    val criterion = "audio to the Bluetooth headset (FBBD-27)"
    if (voiced.isEmpty()) {
        val why = "where each cue's sound went is logged from this version on"
        return notMeasured("coach.headset", "Cues to a headset", criterion, why)
    }
    val percent = voiced.count { it.route == "headset" } * Percent / voiced.size
    val how = "output connected when each cue's sound started (Bluetooth, wired or USB headset); " +
        "${voiced.size} cue(s)"
    val ok = percent >= Percent
    return judged("coach.headset", "Cues to a headset", percent, "%", ok, Basis.MEASURED, criterion, how)
}
