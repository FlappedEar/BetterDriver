package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.RecorderEvent
import java.util.Locale

// GNSS link drops and reconnects for the session validation report (FBBD-183), against FBBD-8 and
// FBBD-43: each logged drop, the data gap around it, and whether data came back.

private const val MaxReconnectMicros = 5_000_000L

/**
 * The link-drop events of the GNSS sources (core-ble's `*Events.DISCONNECTED`). Heart rate
 * (`hr_disconnected`) is no GNSS source and is left out.
 */
internal val GnssDisconnectKinds = setOf(
    "racebox_disconnected",
    "nmea_disconnected",
    "vbox_sport_disconnected",
    "dragy_disconnected",
    "diy_disconnected",
    "lns_disconnected",
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

internal fun reconnectMetrics(s: GnssSamples, drops: List<Reconnect>, endMicros: Long): List<QualityMetric> {
    val count = info(
        "gnss.link_drops", "Link drops", drops.size.toDouble(), "", Basis.MEASURED,
        "link-drop events the GNSS source logged",
    )
    if (drops.isEmpty()) {
        val none =
            notMeasured("gnss.reconnect", "Longest reconnect", ReconnectCriterion, "no link drop in this session")
        return listOf(count, none)
    }
    val longest = drops.maxOf { cost(s, it, endMicros) }
    val ok = drops.all { it.after != null } && longest <= MaxReconnectMicros
    val how = "time without data around each drop, last sample before to first after; it includes the time " +
        "the phone took to notice the drop, so it is an upper bound on the reconnect"
    val reconnect = judged(
        "gnss.reconnect", "Longest reconnect", seconds(longest), "s", ok, Basis.MEASURED, ReconnectCriterion, how,
    )
    return listOf(count, reconnect)
}

/** The data gap of a drop; when data never came back, it runs to the end of the session. */
private fun cost(s: GnssSamples, d: Reconnect, endMicros: Long) =
    (d.after?.let { s.t[it] } ?: endMicros) - s.t[d.before]

internal fun reconnectText(s: GnssSamples, d: Reconnect, endMicros: Long): String {
    val back = if (d.after == null) "data never came back" else "data back"
    val gap = String.format(Locale.ROOT, "%.1f", seconds(cost(s, d, endMicros)))
    return "link drop (${d.text}): $gap s without data, $back"
}
