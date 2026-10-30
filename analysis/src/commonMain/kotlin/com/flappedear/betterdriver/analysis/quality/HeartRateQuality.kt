package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.log.HeartRateLogIds
import com.flappedear.betterdriver.recorder.log.HeartRateWindow

// The heart-rate monitor part of the session validation report (FBBD-225): whether a listed strap
// sent its heart rate about once a second, without long gaps, and came back after each link drop.
// A listed strap that never sent anything is not measured, not failed (FBBD-279).
// Worked out from the 10 s windows the continuous log holds, so a stored session and a shared log
// give the same values.

private const val HrRateCriterion = ">= 0.9 Hz over the session (FBBD-225)"
private const val HrGapCriterion = "longest gap <= 5 s while connected and in skin contact (FBBD-225)"
private const val HrReconnectCriterion = "heart rate back after every link drop (FBBD-225)"
private const val MinHrRateHz = 0.9
private const val MaxHrGapMicros = 5_000_000L

/** A drop this close to the end of the session, with no data after it, may be the drive ending. */
private const val HrEndOfDriveMicros = 30_000_000L

/**
 * The heart rate section, or null when no heart-rate monitor was listed for the session (no
 * [HeartRateLogIds.DEVICE] event). [windows] are the session's heart rate 10 s at a time, [endMicros]
 * its end on the session clock.
 */
internal fun heartRateSection(
    windows: List<HeartRateWindow>,
    events: List<RecorderEvent>,
    endMicros: Long,
): QualitySection? {
    val device = events.ofKind(HeartRateLogIds.DEVICE).firstOrNull() ?: return null
    val name = device.text.substringAfter("name=", "").ifEmpty { "the monitor" }
    val n = windows.sumOf { it.samples }
    val (drops, notes) = drops(windows, events, endMicros)
    val metrics = if (n == 0) {
        // A strap only sends while worn, so one that never sent was not worn or not in range:
        // not a fault, and the strap needs no switching off for drives without it (Arek, 21 Oct 2026).
        // Without a plausible heart rate early on, the app switches it off for the session (FBBD-285).
        val off = events.ofKind(HeartRateLogIds.OFF).firstOrNull()
        val detail = if (off != null) {
            "$name was listed but ${off.text} (not worn)"
        } else {
            "$name was listed but never sent a heart rate (not worn or not in range)"
        }
        listOf(notMeasured("hr.rate", "Heart rate samples", HrRateCriterion, detail))
    } else {
        listOf(
            rate(n, maxOf(endMicros, windows.maxOf { it.lastMicros }), name),
            gap(windows),
            reconnect(drops),
            info(
                "hr.bpm", "Average heart rate", windows.sumOf { it.bpmSum }.toDouble() / n, "bpm", Basis.MEASURED,
                "min ${windows.minOf { it.bpmMin }}, max ${windows.maxOf { it.bpmMax }} bpm over $n samples",
            ),
        )
    }
    return QualitySection("Heart rate", metrics, notes)
}

/**
 * Heart rates per second of the whole session, from its start to [end]: a strap that came late,
 * stopped early or dropped for long sends too few for its session.
 */
private fun rate(n: Int, end: Long, name: String): QualityMetric {
    if (end <= 0L) {
        return notMeasured("hr.rate", "Heart rate samples", HrRateCriterion, "session length not known")
    }
    val hz = n / seconds(end)
    return judged(
        "hr.rate", "Heart rate samples", hz, "Hz", hz >= MinHrRateHz, Basis.MEASURED, HrRateCriterion,
        "$n heart rates from $name over the session's ${format(seconds(end))} s",
    )
}

private fun gap(windows: List<HeartRateWindow>): QualityMetric {
    val gap = windows.mapNotNull { it.maxGapMicros }.maxOrNull()
        ?: return notMeasured("hr.gap", "Longest heart rate gap", HrGapCriterion, "no two heart rates in a row")
    return judged(
        "hr.gap", "Longest heart rate gap", seconds(gap), "s", gap <= MaxHrGapMicros, Basis.MEASURED, HrGapCriterion,
        "longest wait between two heart rates; waits across a link drop or a lost skin contact are left out",
    )
}

/** A logged link drop and how long the heart rate took to come back; null when it never did. */
private class HrDrop(val tMicros: Long, val backAfterMicros: Long?, val endOfDrive: Boolean)

private fun drops(
    windows: List<HeartRateWindow>,
    events: List<RecorderEvent>,
    endMicros: Long,
): Pair<List<HrDrop>, List<QualityNote>> {
    val backs = windows.mapNotNull { it.backMicros }.sorted()
    val drops = events.ofKind(HeartRateLogIds.DISCONNECTED).sortedBy { it.tMicros }.map { e ->
        val back = backs.firstOrNull { it > e.tMicros }
        HrDrop(e.tMicros, back?.minus(e.tMicros), back == null && endMicros - e.tMicros <= HrEndOfDriveMicros)
    }
    // Several drops before the same return count once, at the first.
    val distinct = drops.filterIndexed { i, d ->
        val back = d.backAfterMicros?.plus(d.tMicros)
        back == null || i == 0 || drops[i - 1].backAfterMicros?.plus(drops[i - 1].tMicros) != back
    }
    val notes = distinct.map { d ->
        val text = when {
            d.backAfterMicros != null ->
                "heart-rate monitor link dropped, back after ${format(seconds(d.backAfterMicros))} s"
            d.endOfDrive -> "heart-rate monitor link dropped as the recording ended"
            else -> "heart-rate monitor link dropped, never back"
        }
        QualityNote(d.tMicros, text)
    }
    return distinct to notes
}

private fun reconnect(drops: List<HrDrop>): QualityMetric {
    val counted = drops.filterNot { it.endOfDrive && it.backAfterMicros == null }
    if (counted.isEmpty()) {
        return notMeasured("hr.reconnect", "Heart rate link drops not recovered", HrReconnectCriterion, "no link drop")
    }
    val lost = counted.count { it.backAfterMicros == null }
    val longest = counted.mapNotNull { it.backAfterMicros }.maxOrNull()
    return judged(
        "hr.reconnect", "Heart rate link drops not recovered", lost.toDouble(), "", lost == 0, Basis.MEASURED,
        HrReconnectCriterion,
        "${counted.size} link drops" +
            longest?.let { "; longest wait for heart rate back ${format(seconds(it))} s" }.orEmpty(),
    )
}

private fun format(s: Double) = fixed(s, 1)
