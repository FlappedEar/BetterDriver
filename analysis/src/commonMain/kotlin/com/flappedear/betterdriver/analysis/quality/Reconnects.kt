package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.log.GnssSecond

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

/** The drive ended under cover when the session's last fix came at under this speed... */
private const val UnderCoverKmh = 10.0

/** ...and the receiver then went on sending samples without a fix for at least this long. */
internal const val UnderCoverMicros = 60_000_000L

/** A wait between samples longer than this breaks that data without a fix. */
private const val UnderCoverBreakMicros = 1_000_000L

internal const val UnderCoverText = "the drive had ended under cover: the last fix at under 10 km/h, " +
    "then over 60 s of data without one to the end of the session"

/**
 * When the drive ended under cover: the time of the session's last fix, when it came at under 10 km/h
 * and the receiver then sent samples without a fix, unbroken, for at least 60 s. Null when the drive did not end
 * so. The 2026-10-18 evening drive ended so: the fix went at 4 km/h, as into a garage, and never came
 * back; minutes later the screen went off twice and the link dropped twice as the driver left.
 */
internal fun underCoverFrom(s: GnssSamples): Long? {
    val last = (s.size - 1 downTo 0).firstOrNull { s.hasFix(it) } ?: return null
    val slow = s.speed[last].isFinite() && s.speed[last] < UnderCoverKmh
    // Data without a fix must run on unbroken from the last fix: a drop right after it is no garage.
    var end = last
    while (end + 1 < s.size && s.t[end + 1] - s.t[end] <= UnderCoverBreakMicros) end++
    return s.t[last].takeIf { slow && s.t[end] - it >= UnderCoverMicros }
}

/**
 * As [underCoverFrom], from the log's second-by-second GNSS: the start of the last second with a fix,
 * when its top speed was under 10 km/h and seconds without a fix then ran on for at least 60 s.
 */
internal fun logUnderCoverFrom(secs: List<GnssSecond>): Long? {
    val i = secs.indexOfLast { it.fixes > 0 }
    if (i < 0) return null
    val last = secs[i]
    val slow = last.kmhMax?.let { it < UnderCoverKmh } == true
    // As for a session: seconds in a row from the last fix, no link drop between them.
    var end = i
    while (end + 1 < secs.size && secs[end + 1].second == secs[end].second + 1 && !secs[end + 1].afterDrop) end++
    return last.tMicros.takeIf { slow && secs[end].tMicros - it >= UnderCoverMicros }
}

/** A drop or screen-off at [micros] came after the drive, which ended under cover from [underCover]. */
internal fun afterDrive(micros: Long, underCover: Long?) = underCover != null && micros >= underCover

private const val LogEndOfDriveMicros = 30_000_000L
private const val LogParkedKmh = 1.0

/** As for a session (endOfDrive): no data after it, the end within 30 s, parked or no fix before it. */
internal fun logEndOfDrive(secs: List<GnssSecond>, d: RecorderEvent, endMicros: Long): Boolean {
    val back = secs.any { it.afterDrop && it.tMicros >= d.tMicros }
    val before = secs.lastOrNull { it.tMicros <= d.tMicros }
    val kmh = before?.kmhMax
    val parked = before == null || before.fixes == 0 || kmh != null && kmh < LogParkedKmh
    return !back && endMicros - d.tMicros <= LogEndOfDriveMicros && parked
}

internal fun reconnectMetrics(
    s: GnssSamples,
    all: List<Reconnect>,
    endMicros: Long,
    underCover: Long? = null,
): List<QualityMetric> {
    val count = info(
        "gnss.link_drops", "Link drops", all.size.toDouble(), "", Basis.MEASURED,
        "link-drop events the GNSS source logged",
    )
    val covered = all.filter { afterDrive(it.dropMicros, underCover) }
    val drops = all.filterNot { endOfDrive(s, it, endMicros) || it in covered }
    if (drops.isEmpty()) {
        val why = when {
            all.isEmpty() -> "no link drop in this session"
            covered.isNotEmpty() -> UnderCoverText
            else -> EndOfDriveText
        }
        return listOf(count, notMeasured("gnss.reconnect", "Longest reconnect", ReconnectCriterion, why))
    }
    val longest = drops.maxOf { cost(s, it, endMicros) }
    val ok = drops.all { it.after != null } && longest <= MaxReconnectMicros
    val parked = all.size - drops.size
    val how = "time without data around each drop, last sample before to first after; it includes the time " +
        "the phone took to notice the drop, so it is an upper bound on the reconnect" +
        if (parked > 0) "; $parked drop(s) at the end of the drive, parked or under cover, left out" else ""
    val reconnect = judged(
        "gnss.reconnect", "Longest reconnect", seconds(longest), "s", ok, Basis.MEASURED, ReconnectCriterion, how,
    )
    return listOf(count, reconnect)
}

/** The data gap of a drop; when data never came back, it runs to the end of the session. */
private fun cost(s: GnssSamples, d: Reconnect, endMicros: Long) =
    (d.after?.let { s.t[it] } ?: endMicros) - s.t[d.before]

internal fun reconnectText(s: GnssSamples, d: Reconnect, endMicros: Long, underCover: Long? = null): String {
    val back = when {
        afterDrive(d.dropMicros, underCover) -> "after the drive, which had ended under cover"
        d.after != null -> "data back"
        endOfDrive(s, d, endMicros) -> "data never came back, parked at the end of the session"
        else -> "data never came back"
    }
    val gap = fixed(seconds(cost(s, d, endMicros)), 1)
    return "link drop (${d.text}): $gap s without data, $back"
}
