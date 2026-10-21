package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.LogLine
import com.flappedear.betterdriver.recorder.log.ReachLog
import java.util.Locale

// Whether Settings › Equipment's "In range" and "Not seen" (UI-138 B) were right (FBBD-280), from the
// continuous log alone: the state the page last showed for a Bluetooth item before a recording
// started, against whether that recording then connected to the item. The page does not look while a
// recording runs, so only what it showed before one can be checked.

private const val InRange = "InRange"
private const val NotSeen = "NotSeen"
private const val Label = "Equipment In range / Not seen"
private const val Criterion =
    "Settings › Equipment's In range / Not seen agrees with the recording's connection to the item (FBBD-280)"

/** "In range" shown up to this long before Start is judged: the item should still be there... */
private const val InRangeMaxAgeMillis = 5 * 60_000L

/** ...and the recording should connect to it within this long of Start (a RaceBox took 45 s, 2026-10-21). */
private const val ConnectWithinMillis = 60_000L

/**
 * "Not seen" only when shown up to this long before Start: a device switched on after the page
 * looked is no fault of the page.
 */
private const val NotSeenMaxAgeMillis = 60_000L

/** ...and then it was wrong if the recording connected to the item within this long of Start. */
private const val NotSeenConnectMillis = 20_000L

/** A connection the recorder logged: GattLink's "<address> connected in", ObdConnector's "connected to <address>". */
private val Connected = Regex("""\bconnected (in|to) |\bStreaming\b""")

/** Tags of the recorder's connection lines. */
private val LinkTags = setOf("Link", "Obd")

/** Item [item] ([model], [address]) was shown as [reach] from [fromUtc] until [untilUtc] (phone wall clock). */
internal class ReachSpan(
    val fromUtc: Long,
    val untilUtc: Long,
    val item: String,
    val model: String,
    val address: String?,
    val reach: String,
)

/**
 * What Settings › Equipment showed, item by item, from its [ReachLog] lines: a state lasts until the
 * item's next state or until the page stopped looking. One still shown when the log ends lasts on.
 */
internal fun reachSpans(lines: List<LogLine>): List<ReachSpan> {
    val out = mutableListOf<ReachSpan>()
    val open = LinkedHashMap<String, Pair<Long, ReachLog.Entry.Item>>()
    fun close(id: String, at: Long) {
        open.remove(id)?.let { (from, e) -> out += ReachSpan(from, at, e.id, e.model, e.address, e.reach) }
    }
    for (line in lines.filter { it.tag == ReachLog.TAG }.sortedBy { it.utcMillis }) {
        when (val e = ReachLog.parse(line.message)) {
            is ReachLog.Entry.Item -> {
                close(e.id, line.utcMillis)
                open[e.id] = line.utcMillis to e
            }
            // A page that starts looking again without a "look off" was ended with its process.
            is ReachLog.Entry.Look -> open.keys.toList().forEach { close(it, line.utcMillis) }
            null -> Unit
        }
    }
    open.keys.toList().forEach { close(it, Long.MAX_VALUE) }
    return out.sortedBy { it.fromUtc }
}

/**
 * The Equipment section of recording [s]: for each Bluetooth item the page last showed as In range or
 * Not seen in [spans] before [s] started (and after [sinceUtc], the recording before it), whether the
 * recording's connection agreed. Null when the page showed nothing between the two recordings.
 */
internal fun reachSection(spans: List<ReachSpan>, s: LoggedSession, sinceUtc: Long?): QualitySection? {
    val start = s.startUtcMillis ?: return null
    val before = spans.filter { it.fromUtc < start && (sinceUtc == null || it.untilUtc > sinceUtc) }
    if (before.isEmpty()) return null
    val last = before.groupBy { it.item }.values.map { it.maxBy { span -> span.fromUtc } }
    val checked = last.filter { it.address != null && (it.reach == InRange || it.reach == NotSeen) }
        .mapNotNull { judge(it, s, start) }
    if (checked.isEmpty()) {
        val why = "Settings › Equipment was open before this recording, but it showed no Bluetooth item " +
            "In range up to 5 min before Start, or Not seen up to 1 min before, that the recording then used"
        return QualitySection("Equipment", listOf(notMeasured("equipment.reach", Label, Criterion, why)))
    }
    val wrong = checked.count { !it.first }
    val metric = judged(
        "equipment.reach", Label, wrong.toDouble(), "", wrong == 0, Basis.MEASURED, Criterion,
        "items shown wrongly, of ${checked.size}: " + checked.joinToString("; ") { it.second } +
            " (In range: connected within 60 s of Start; Not seen: not connected within 20 s)",
    )
    return QualitySection("Equipment", listOf(metric))
}

/** Whether [span]'s state agreed with recording [s], and how; null when it is not judged. */
private fun judge(span: ReachSpan, s: LoggedSession, start: Long): Pair<Boolean, String>? {
    val address = span.address ?: return null
    val age = start - minOf(span.untilUtc, start)
    val maxAge = if (span.reach == InRange) InRangeMaxAgeMillis else NotSeenMaxAgeMillis
    val links = s.lines.filter { it.tag in LinkTags && it.message.contains(address, ignoreCase = true) }
    if (age > maxAge || links.isEmpty()) return null
    val connected = links.filter { Connected.containsMatchIn(it.message) }.minOfOrNull { it.utcMillis }
    val after = connected?.let { it - start }
    val ok = if (span.reach == InRange) {
        after != null && after <= ConnectWithinMillis
    } else {
        after == null || after > NotSeenConnectMillis
    }
    val shown = if (span.reach == InRange) "In range" else "Not seen"
    val link = after?.let { "connected ${secondsText(it)} s after it" } ?: "never connected"
    val verdict = if (ok) "agrees" else "WRONG"
    return ok to "${span.model} $address shown $shown ${secondsText(age)} s before Start, $link: $verdict"
}

private fun secondsText(millis: Long): String = String.format(Locale.ROOT, "%.1f", millis / MsPerSecond)

private const val MsPerSecond = 1_000.0
