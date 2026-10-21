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
 * when none came). Gaps over 200 ms are listed; only those longer than the banner's own threshold
 * (1 s at 25 Hz) are outages the banner must show for.
 */
internal class Outage(val lastFix: Long, val nextFix: Long?) {
    fun micros(endMicros: Long) = (nextFix ?: endMicros) - lastFix

    companion object {
        /**
         * A banner at [t] that logged a fix age ([age]) over its threshold with a gap among [gaps]
         * around it: the fixes reached the phone late, so it was right (FBBD-282).
         */
        fun lateFixes(t: Long, age: Long?, gaps: List<Outage>, during: (Outage, Long) -> Boolean) =
            age != null && age > BannerThresholdMicros && gaps.any { during(it, t) }
    }
}

/** The banner shows after this long without a fix (`:recorder`'s gnssLostAfterMicros at 25 Hz). */
internal const val BannerThresholdMicros = 1_000_000L

/**
 * The gaps of [samples]: waits between fixes over 200 ms, and the time after the last fix when it is
 * longer than the banner's threshold.
 */
internal fun outages(samples: GnssSamples, endMicros: Long): List<Outage> {
    val fixes = (0 until samples.size).filter { samples.hasFix(it) }.map { samples.t[it] }
    val out = fixes.zipWithNext().filter { (a, b) -> b - a > MaxGapMicros }.map { (a, b) -> Outage(a, b) }
    val last = fixes.lastOrNull()
    return if (last != null && endMicros - last > BannerThresholdMicros) out + Outage(last, null) else out
}

/** [underCover]: when the drive ended under cover ([underCoverFrom]); the screen going off after it does not count. */
internal fun screenSection(
    events: List<RecorderEvent>,
    outages: List<Outage>,
    endMicros: Long,
    underCover: Long? = null,
): QualitySection {
    val frames = events.ofKind(EventKinds.UI_FRAMES).mapNotNull(::frameStretch)
    val metrics = frameMetrics(frames) + screenOff(events, underCover) + banners(events, outages, endMicros)
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

private fun screenOff(events: List<RecorderEvent>, underCover: Long?): List<QualityMetric> {
    val device = events.ofKind(EventKinds.DEVICE)
    if (device.isEmpty() && events.ofKind(EventKinds.SCREEN).isEmpty()) {
        val why = "the screen's state is logged while recording from this version on"
        return listOf(notMeasured("ui.screen_off", "Screen off while recording", ScreenCriterion, why))
    }
    val offs = events.ofKind(EventKinds.SCREEN).filter { fields(it.text)["state"] == "off" }
    val after = offs.count { afterDrive(it.tMicros, underCover) }
    val off = offs.size - after
    val left = if (after > 0) "; $after after the drive, which had ended under cover, left out" else ""
    return listOf(
        judged(
            "ui.screen_off", "Screen off while recording", off.toDouble(), "", off == 0, Basis.MEASURED,
            ScreenCriterion, "times Android reported the screen going off while the session recorded$left",
        ),
    )
}

private const val FalseBannerCriterion = "0 GNSS-lost banners without a real outage (FBBD-64)"
private const val ShowCriterion = "banner within 2 s of the GNSS drop (FBBD-64)"
private const val ClearCriterion = "banner cleared within 2 s of the reconnect (FBBD-64)"

/**
 * The banner against the gaps the session's fixes show: a banner shown with no outage around it is
 * false; for each outage, how long after its last fix the banner showed and after its first fix
 * again it cleared. The fixes' times are the receiver's, but the banner goes by when they reach the
 * phone (FBBD-282): on 21 Oct 2026 the RaceBox link stalled for ~1.3 s and lost a second of
 * messages, so the receiver's times showed gaps of 0.88 to 1.04 s while the phone waited over 1 s.
 * So a banner that logged a fix age over its own threshold, with a gap over 200 ms around it, is
 * right too.
 */
private fun banners(events: List<RecorderEvent>, gaps: List<Outage>, endMicros: Long): List<QualityMetric> {
    val banners = events.ofKind(EventKinds.GNSS_BANNER).map { it.tMicros to fields(it.text) }
    if (banners.isEmpty() && events.ofKind(EventKinds.UI_FRAMES).isEmpty()) {
        val why = "the banner's changes are logged while the track screen is open, from this version on"
        return listOf(
            notMeasured("ui.false_banners", "False GNSS banners", FalseBannerCriterion, why),
            notMeasured("ui.banner_show", "GNSS banner delay", ShowCriterion, why),
            notMeasured("ui.banner_clear", "GNSS banner clear delay", ClearCriterion, why),
        )
    }
    val outages = gaps.filter { it.micros(endMicros) > BannerThresholdMicros }
    val shownAt = banners.filter { it.second["state"] == "shown" }
    val shown = shownAt.map { it.first }
    val cleared = banners.filter { it.second["state"] == "cleared" }.map { it.first }
    fun during(o: Outage, t: Long) = o.lastFix < t && (o.nextFix ?: endMicros) + BannerSlackMicros >= t
    val unexplained = shownAt.filter { (b, _) -> outages.none { o -> during(o, b) } }
    val lateOnly = unexplained.count { (b, f) ->
        Outage.lateFixes(b, f["fix_age_us"]?.toLongOrNull(), gaps, ::during)
    }
    val wrong = unexplained.size - lateOnly
    // Null for an outage the banner should have reacted to and never did: one longer than the limit
    // that it never showed for, or one it showed for and never cleared after the fixes came back.
    val showDelays = outages.mapNotNull { o ->
        val delay = shown.firstOrNull { during(o, it) }?.let { it - o.lastFix }
        val long = (o.nextFix ?: endMicros) - o.lastFix > MaxBannerMicros
        if (delay != null || long) Delay(delay) else null
    }
    val clearDelays = outages.mapNotNull { o ->
        val back = o.nextFix
        if (back == null || shown.none { during(o, it) }) return@mapNotNull null
        Delay(cleared.firstOrNull { it >= back - BannerSlackMicros }?.let { maxOf(0L, it - back) })
    }
    val how = "banner changes on the track screen against outages of the session's fixes over 1 s"
    return buildList {
        add(
            judged(
                "ui.false_banners", "False GNSS banners", wrong.toDouble(), "", wrong == 0, Basis.MEASURED,
                FalseBannerCriterion,
                "$how; ${shown.size} banner(s), ${outages.size} outage(s), " +
                    "$lateOnly for a shorter gap with the fixes late on the phone",
            ),
        )
        add(delayMetric("ui.banner_show", "GNSS banner delay", showDelays, ShowCriterion, "$how; from the last fix"))
        add(clearMetric(outages, clearDelays, "$how; from the first fix back"))
    }
}

private const val NoOutageText = "no GNSS outage long enough for the banner"

/** The clear delays; when the session ended in its only outages, the fix never came back to clear it. */
private fun clearMetric(outages: List<Outage>, delays: List<Delay>, how: String): QualityMetric {
    val label = "GNSS banner clear delay"
    if (delays.isEmpty() && outages.isNotEmpty() && outages.all { it.nextFix == null }) {
        val why = "the fix did not come back before the session ended"
        return notMeasured("ui.banner_clear", label, ClearCriterion, why)
    }
    return delayMetric("ui.banner_clear", label, delays, ClearCriterion, how)
}

/** How long the banner took for one outage; [micros] null when it never reacted. */
private class Delay(val micros: Long?)

/** The share of [delays] within the limit, in whole percent; one never reacted to is late. */
private fun inTime(delays: List<Delay>) =
    (delays.count { it.micros != null && it.micros <= MaxBannerMicros } * Percent / delays.size).roundToLong()

/**
 * The worst of [delays]; FAIL when any is over the limit or the banner never reacted. The value is the
 * worst delay measured, or the limit when it never reacted at all.
 */
private fun delayMetric(id: String, label: String, delays: List<Delay>, criterion: String, how: String): QualityMetric {
    if (delays.isEmpty()) return notMeasured(id, label, criterion, NoOutageText)
    val missed = delays.count { it.micros == null }
    val worst = delays.mapNotNull { it.micros }.maxOrNull() ?: MaxBannerMicros
    val ok = missed == 0 && worst <= MaxBannerMicros
    return judged(
        id, label, seconds(worst), "s", ok, Basis.MEASURED, criterion,
        "$how; worst of ${delays.size}, ${inTime(delays)} % in time, $missed never reacted to",
    )
}
