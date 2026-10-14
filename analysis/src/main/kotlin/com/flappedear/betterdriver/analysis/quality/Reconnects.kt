package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.RecorderEvent
import java.util.Locale

// GNSS link drops and reconnects for the session validation report (FBBD-183), against FBBD-8 and
// FBBD-43: each logged drop, the data gap around it, and whether data came back.

private const val MaxReconnectMicros = 5_000_000L

/**
 * The link-drop events of the GNSS sources (core-ble's `*Events.DISCONNECTED`). Heart rate
 * (`hr_disconnected`) is no GNSS source and is left out. Demo GNSS logs its drops too when an emulator
 * test switches it off (FakeGnssEvents, FBBD-43).
 */
internal val GnssDisconnectKinds = setOf(
    "racebox_disconnected",
    "nmea_disconnected",
    "vbox_sport_disconnected",
    "dragy_disconnected",
    "diy_disconnected",
    "lns_disconnected",
    "demo_gnss_disconnected",
)

/**
 * A logged link drop: the last sample before it ([before]) and the first after ([after], null when
 * data never came back). The data gap from one to the other is what the drop cost.
 */
internal class Reconnect(val dropMicros: Long, val text: String, val before: Int, val after: Int?)

/**
 * The GNSS link drops of [events], each with the samples around it. Drops between the same two
 * samples count once.
 */
internal fun reconnects(samples: GnssSamples, events: List<RecorderEvent>): List<Reconnect> {
    val out = mutableListOf<Reconnect>()
    for (e in events.filter { it.kind in GnssDisconnectKinds }.sortedBy { it.tMicros }) {
        val before = samples.lastAtOrBefore(e.tMicros)
        if (before >= 0 && out.lastOrNull()?.before != before) {
            val after = (before + 1).takeIf { it < samples.size }
            out += Reconnect(e.tMicros, "${e.kind} ${e.text}".trim(), before, after)
        }
    }
    return out
}

private const val ReconnectCriterion = "data back within 5 s, same session (FBBD-8, FBBD-43)"

/** A drop this close to the end of the session, with no data after it, may be the drive ending. */
private const val EndOfDriveMicros = 30_000_000L

internal const val EndOfDriveText = "the drop came with the car parked and the recording stopped within 30 s, " +
    "data not back: the drive ending, not a reconnect"

/** Below this speed the car is parked (as for the position noise check). */
private const val ParkedKmh = 1.0

/**
 * A drop that is the drive ending rather than a link fault: data never came back, the session ended
 * within 30 s, and the car was parked (or had no fix) at the last sample before it. The 2026-10-14
 * drive ended so: parked without a fix, the link dropped 7 s before the driver stopped recording.
 */
internal fun endOfDrive(s: GnssSamples, d: Reconnect, endMicros: Long): Boolean {
    if (d.after != null || endMicros - d.dropMicros > EndOfDriveMicros) return false
    // A fix without a speed (GGA-only NMEA) does not show the car parked.
    val speed = s.speed[d.before]
    return !s.hasFix(d.before) || speed.isFinite() && speed < ParkedKmh
}

internal fun reconnectMetrics(s: GnssSamples, all: List<Reconnect>, endMicros: Long): List<QualityMetric> {
    val count = info(
        "gnss.link_drops", "Link drops", all.size.toDouble(), "", Basis.MEASURED,
        "link-drop events the GNSS source logged",
    )
    val drops = all.filterNot { endOfDrive(s, it, endMicros) }
    if (drops.isEmpty()) {
        val why = if (all.isEmpty()) "no link drop in this session" else EndOfDriveText
        return listOf(count, notMeasured("gnss.reconnect", "Longest reconnect", ReconnectCriterion, why))
    }
    val longest = drops.maxOf { cost(s, it, endMicros) }
    val ok = drops.all { it.after != null } && longest <= MaxReconnectMicros
    val parked = all.size - drops.size
    val how = "time without data around each drop, last sample before to first after; it includes the time " +
        "the phone took to notice the drop, so it is an upper bound on the reconnect" +
        if (parked > 0) "; $parked drop(s) at the end of the drive, parked, left out" else ""
    val reconnect = judged(
        "gnss.reconnect", "Longest reconnect", seconds(longest), "s", ok, Basis.MEASURED, ReconnectCriterion, how,
    )
    return listOf(count, reconnect)
}

/** The data gap of a drop; when data never came back, it runs to the end of the session. */
private fun cost(s: GnssSamples, d: Reconnect, endMicros: Long) =
    (d.after?.let { s.t[it] } ?: endMicros) - s.t[d.before]

internal fun reconnectText(s: GnssSamples, d: Reconnect, endMicros: Long): String {
    val back = when {
        d.after != null -> "data back"
        endOfDrive(s, d, endMicros) -> "data never came back, parked at the end of the session"
        else -> "data never came back"
    }
    val gap = String.format(Locale.ROOT, "%.1f", seconds(cost(s, d, endMicros)))
    return "link drop (${d.text}): $gap s without data, $back"
}
