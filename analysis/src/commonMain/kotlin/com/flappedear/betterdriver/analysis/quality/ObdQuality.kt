package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.RecorderEvent

// The OBD-II part of the session validation report (FBBD-271, FBBD-173): how often car data dropped
// mid-drive, how long it was gone, and whether it came back in the same session. Made from the
// reader's session events (ObdSessionEvents in :obd), the same on Android and iOS.

/** ObdSessionEvents.CONNECTED: the car answered on a connection. */
internal const val ObdConnectedKind = "obd_connected"

/** ObdSessionEvents.DISCONNECTED, text "reason=<why>". */
internal const val ObdDisconnectedKind = "obd_disconnected"

private const val ObdBackCriterion = "car data back after every mid-drive link drop, same session (FBBD-271)"

/** A drop this close to the end of the session, with no data after it, may be the drive ending. */
private const val ObdEndOfDriveMicros = 30_000_000L

/** A drop of the link mid-drive at [tMicros], and car data back [backAfterMicros] later (null: never). */
private class ObdDrop(val tMicros: Long, val reason: String, val backAfterMicros: Long?, val endOfDrive: Boolean)

/**
 * Whether a connection ended by the link rather than the car, as ObdConnector.isLinkLoss: the link
 * dropped or the adapter went silent. "car stopped answering" is the engine switched off at a stop,
 * which is expected; our own close or interrupt is stopping.
 */
internal fun obdLinkLoss(reason: String): Boolean =
    reason.startsWith("connection lost") && !reason.endsWith(": closed") && !reason.endsWith(": interrupted") ||
        reason == "adapter stopped answering"

/**
 * The OBD-II section, or null when no OBD adapter connected in the session. [endMicros] is its end
 * on the session clock.
 */
internal fun obdSection(events: List<RecorderEvent>, endMicros: Long): QualitySection? {
    val connects = events.ofKind(ObdConnectedKind).map { it.tMicros }.sorted()
    val first = connects.firstOrNull() ?: return null
    val ends = events.ofKind(ObdDisconnectedKind).sortedBy { it.tMicros }
    val drops = liveEnds(events).mapNotNull { e ->
        val reason = e.text.substringAfter("reason=")
        if (!obdLinkLoss(reason)) return@mapNotNull null
        val back = connects.firstOrNull { it > e.tMicros }
        ObdDrop(e.tMicros, reason, back?.minus(e.tMicros), back == null && endMicros - e.tMicros <= ObdEndOfDriveMicros)
    }
    val counted = drops.filterNot { it.endOfDrive }
    val metrics = listOf(
        info(
            "obd.drops", "OBD link drops", counted.size.toDouble(), "", Basis.MEASURED,
            "connections the link or the adapter ended mid-drive; the car switched off at a stop is not counted",
        ),
        longestGap(counted, endMicros),
        info(
            "obd.time_without", "Time without OBD", seconds(timeWithout(first, connects, ends, endMicros)), "s",
            Basis.MEASURED, "time between a connection ending and the next one, from the first connection " +
                "to the end of the session, for any reason (engine-off stops included)",
        ),
        notBack(counted),
    )
    val notes = drops.map { d ->
        val text = when {
            d.backAfterMicros != null ->
                "OBD link dropped (${d.reason}), car data back after ${format(d.backAfterMicros)} s"
            d.endOfDrive -> "OBD link dropped (${d.reason}) as the recording ended"
            else -> "OBD link dropped (${d.reason}), never back"
        }
        QualityNote(d.tMicros, text)
    }
    return QualitySection("OBD-II", metrics, notes)
}

/**
 * The ends of connections the car answered on: an attempt that opened the adapter but failed in
 * setup or discovery also logs an end, with no connect before it, and is no drop.
 */
private fun liveEnds(events: List<RecorderEvent>): List<RecorderEvent> {
    var live = false
    val out = mutableListOf<RecorderEvent>()
    for (e in events.filter { it.kind == ObdConnectedKind || it.kind == ObdDisconnectedKind }.sortedBy { it.tMicros }) {
        if (e.kind == ObdConnectedKind) {
            live = true
        } else if (live) {
            live = false
            out += e
        }
    }
    return out
}

private fun longestGap(drops: List<ObdDrop>, endMicros: Long): QualityMetric {
    val longest = drops.maxOfOrNull { it.backAfterMicros ?: (endMicros - it.tMicros) }
    return info(
        "obd.longest_gap", "Longest OBD gap", longest?.let(::seconds), "s", Basis.MEASURED,
        if (longest == null) {
            "no mid-drive link drop"
        } else {
            "longest time from a link drop to car data back (to the end of the session when it never came back)"
        },
    )
}

private fun notBack(drops: List<ObdDrop>): QualityMetric {
    if (drops.isEmpty()) {
        return notMeasured("obd.not_back", "OBD drops not recovered", ObdBackCriterion, "no mid-drive link drop")
    }
    val lost = drops.count { it.backAfterMicros == null }
    return judged(
        "obd.not_back", "OBD drops not recovered", lost.toDouble(), "", lost == 0, Basis.MEASURED, ObdBackCriterion,
        "${drops.size} mid-drive link drops; a drop within 30 s of the end with no data after it is left out",
    )
}

/** Time without a connection after [first]: from each end to the next connect, or to [endMicros]. */
private fun timeWithout(first: Long, connects: List<Long>, ends: List<RecorderEvent>, endMicros: Long): Long {
    var total = 0L
    var gapUntil = first
    for (e in ends.filter { it.tMicros >= first }) {
        // A later end before that connect is the same gap.
        if (e.tMicros < gapUntil) continue
        val next = connects.firstOrNull { it > e.tMicros } ?: endMicros
        total += (next - e.tMicros).coerceAtLeast(0L)
        gapUntil = next
    }
    return total
}

private fun format(micros: Long) = fixed(seconds(micros), 1)
