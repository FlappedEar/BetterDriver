package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.FrameBuckets
import com.flappedear.betterdriver.recorder.RecorderEvent
import kotlin.math.ceil
import kotlin.math.roundToLong

// The screen part of the session validation report (FBBD-183): the track screen's frames (FBBD-41,
// FBBD-62), the screen going off (FBBD-63) and the GNSS-lost banner against real outages (FBBD-64).

private const val MaxJankPercent = 5.0
private const val MaxP95Millis = 16
private const val P95 = 0.95

/** A frame over this is frozen (FBBD-41). */
private const val FrozenMillis = 700

/** The banner must appear within this of the last fix, and clear within this of the first fix after (FBBD-64). */
private const val MaxBannerMicros = 2_000_000L

/** A banner shown no later than this after an outage ended still belongs to it: the screen is a tick behind. */
private const val BannerSlackMicros = 1_000_000L

/**
 * A time without a GNSS fix: from the last fix before ([lastFix]) to the first after ([nextFix], null
 * when none came). Only outages longer than the banner's own threshold (1 s at 25 Hz) are listed.
 */
internal class Outage(val lastFix: Long, val nextFix: Long?)

/** The banner shows after this long without a fix (`:recorder`'s gnssLostAfterMicros at 25 Hz). */
internal const val BannerThresholdMicros = 1_000_000L

/** The outages of [samples]: waits between fixes longer than the banner's threshold, and none after the last fix. */
internal fun outages(samples: GnssSamples, endMicros: Long): List<Outage> {
    val fixes = (0 until samples.size).filter { samples.hasFix(it) }.map { samples.t[it] }
    val out = fixes.zipWithNext().filter { (a, b) -> b - a > BannerThresholdMicros }.map { (a, b) -> Outage(a, b) }
    val last = fixes.lastOrNull()
    return if (last != null && endMicros - last > BannerThresholdMicros) out + Outage(last, null) else out
}

internal fun screenSection(events: List<RecorderEvent>, outages: List<Outage>, endMicros: Long): QualitySection {
    val frames = events.ofKind(EventKinds.UI_FRAMES).mapNotNull(::frameStretch)
    val metrics = frameMetrics(frames) + screenOff(events) + banners(events, outages, endMicros)
    val notes = events.ofKind(EventKinds.SCREEN).map { QualityNote(it.tMicros, "screen ${fields(it.text)["state"]}") } +
        events.ofKind(EventKinds.GNSS_BANNER).map { QualityNote(it.tMicros, "GNSS banner ${fields(it.text)["state"]}") }
    return QualitySection("Screen", metrics, notes.sortedBy { it.tMicros })
}

/** One [EventKinds.UI_FRAMES] report: frame counts by [FrameBuckets], janky and frozen ones. */
private class FrameStretch(val frames: Long, val janky: Long, val frozen: Long, val histogram: List<Long>)

private fun frameStretch(e: RecorderEvent): FrameStretch? {
    val f = fields(e.text)
    val frames = f["frames"]?.toLongOrNull() ?: return null
    val hist = f["hist"]?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
    return FrameStretch(frames, f["janky"]?.toLongOrNull() ?: 0, f["frozen"]?.toLongOrNull() ?: 0, hist)
}

private const val FrameCriterion = "frame time p95 <= 16 ms (FBBD-62, FBBD-41)"
private const val JankCriterion = "jank < 5 % of frames (FBBD-62, FBBD-41, FBBD-46)"
private const val FrozenCriterion = "0 frozen frames over 700 ms (FBBD-41)"

private fun frameMetrics(stretches: List<FrameStretch>): List<QualityMetric> {
    val total = stretches.sumOf { it.frames }
    if (total == 0L) {
        val why = "the track screen's frames are logged while recording from this version on"
        return listOf(
            notMeasured("ui.frame_p95", "Frame time p95", FrameCriterion, why),
            notMeasured("ui.jank", "UI jank", JankCriterion, why),
            notMeasured("ui.frozen", "Frozen frames", FrozenCriterion, why),
        )
    }
    val buckets = LongArray(FrameBuckets.size + 1)
    stretches.filter { it.histogram.size == buckets.size }.forEach { s ->
        s.histogram.forEachIndexed { i, n -> buckets[i] += n }
    }
    val p95 = percentileBucket(buckets, P95)?.let { FrameBuckets.getOrElse(it) { FrozenMillis } }
    val janky = stretches.sumOf { it.janky }
    val frozen = stretches.sumOf { it.frozen }
    val jankPercent = janky * Percent / total
    val how = "JankStats over $total frames drawn while recording"
    return listOf(
        if (p95 == null) {
            notMeasured("ui.frame_p95", "Frame time p95", FrameCriterion, "no frame-time histogram was logged")
        } else {
            judged(
                "ui.frame_p95", "Frame time p95", p95.toDouble(), "ms", p95 <= MaxP95Millis, Basis.MEASURED,
                FrameCriterion,
                "$how; the upper edge of the frame-time bucket that holds the 95th percentile",
            )
        },
        judged(
            "ui.jank", "UI jank", jankPercent, "%", jankPercent < MaxJankPercent, Basis.MEASURED, JankCriterion, how,
        ),
        judged("ui.frozen", "Frozen frames", frozen.toDouble(), "", frozen == 0L, Basis.MEASURED, FrozenCriterion, how),
    )
}

/** The bucket that holds the [p] quantile of the counts in [buckets], or null when they hold none. */
private fun percentileBucket(buckets: LongArray, p: Double): Int? {
    val rank = ceil(p * buckets.sum()).toLong()
    if (rank <= 0) return null
    var seen = 0L
    return buckets.indices.first { i ->
        seen += buckets[i]
        seen >= rank
    }
}

private const val ScreenCriterion = "0 screen-off events while recording (FBBD-63)"

private fun screenOff(events: List<RecorderEvent>): List<QualityMetric> {
    val device = events.ofKind(EventKinds.DEVICE)
    if (device.isEmpty() && events.ofKind(EventKinds.SCREEN).isEmpty()) {
        val why = "the screen's state is logged while recording from this version on"
        return listOf(notMeasured("ui.screen_off", "Screen off while recording", ScreenCriterion, why))
    }
    val off = events.ofKind(EventKinds.SCREEN).count { fields(it.text)["state"] == "off" }
    return listOf(
        judged(
            "ui.screen_off", "Screen off while recording", off.toDouble(), "", off == 0, Basis.MEASURED,
            ScreenCriterion, "times Android reported the screen going off while the session recorded",
        ),
    )
}

private const val FalseBannerCriterion = "0 GNSS-lost banners without a real outage (FBBD-64)"
private const val ShowCriterion = "banner within 2 s of the GNSS drop (FBBD-64)"
private const val ClearCriterion = "banner cleared within 2 s of the reconnect (FBBD-64)"

/**
 * The banner against the outages the session's fixes show: a banner shown with no outage around it
 * is false; for each outage, how long after its last fix the banner showed and after its first fix
 * again it cleared.
 */
private fun banners(events: List<RecorderEvent>, outages: List<Outage>, endMicros: Long): List<QualityMetric> {
    val banners = events.ofKind(EventKinds.GNSS_BANNER).map { it.tMicros to fields(it.text)["state"] }
    if (banners.isEmpty() && events.ofKind(EventKinds.UI_FRAMES).isEmpty()) {
        val why = "the banner's changes are logged while the track screen is open, from this version on"
        return listOf(
            notMeasured("ui.false_banners", "False GNSS banners", FalseBannerCriterion, why),
            notMeasured("ui.banner_show", "GNSS banner delay", ShowCriterion, why),
            notMeasured("ui.banner_clear", "GNSS banner clear delay", ClearCriterion, why),
        )
    }
    val shown = banners.filter { it.second == "shown" }.map { it.first }
    val cleared = banners.filter { it.second == "cleared" }.map { it.first }
    fun during(o: Outage, t: Long) = o.lastFix < t && (o.nextFix ?: endMicros) + BannerSlackMicros >= t
    val wrong = shown.count { b -> outages.none { o -> during(o, b) } }
    val showDelays = outages.mapNotNull { o ->
        shown.firstOrNull { during(o, it) }?.let { it - o.lastFix }
    }
    val clearDelays = outages.mapNotNull { o ->
        val back = o.nextFix ?: return@mapNotNull null
        cleared.firstOrNull { it >= back - BannerSlackMicros }?.let { maxOf(0L, it - back) }
    }
    val how = "banner changes on the track screen against outages of the session's fixes over 1 s"
    return buildList {
        add(
            judged(
                "ui.false_banners", "False GNSS banners", wrong.toDouble(), "", wrong == 0, Basis.MEASURED,
                FalseBannerCriterion, "$how; ${shown.size} banner(s), ${outages.size} outage(s)",
            ),
        )
        add(delayMetric("ui.banner_show", "GNSS banner delay", showDelays, ShowCriterion, "$how; from the last fix"))
        val clearHow = "$how; from the first fix back"
        add(delayMetric("ui.banner_clear", "GNSS banner clear delay", clearDelays, ClearCriterion, clearHow))
    }
}

/** The share of [delays] within the limit, in whole percent. */
private fun inTime(delays: List<Long>) = (delays.count { it <= MaxBannerMicros } * Percent / delays.size).roundToLong()

private fun delayMetric(id: String, label: String, delays: List<Long>, criterion: String, how: String): QualityMetric {
    val worst = delays.maxOrNull() ?: return notMeasured(id, label, criterion, "no GNSS outage the screen showed")
    return judged(
        id, label, seconds(worst), "s", worst <= MaxBannerMicros, Basis.MEASURED, criterion,
        "$how; worst of ${delays.size}, ${inTime(delays)} % in time",
    )
}
