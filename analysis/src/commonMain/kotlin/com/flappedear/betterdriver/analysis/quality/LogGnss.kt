package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.log.GnssSecond
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

// The GNSS part of the drive checks from the continuous log (FBBD-183), against FBBD-8 and FBBD-46:
// the same values as the session's own report, worked out from the recorder's line per second of
// GNSS ([GnssSecond]) instead of every sample.

// Arek (2026-10-13): above 24 Hz is good, below 20 Hz would worry him. Missing messages are
// judged on their own by the dropped-samples check.
private const val DegreesPerHalfTurn = 180.0
private const val MinRateHz = 24.0
private const val MaxDroppedPercent = 0.5
private const val MaxReconnectMicros = 5_000_000L
private const val MaxStationaryStdMeters = 0.5
private const val StationaryKmh = 1.0
private const val StationarySeconds = 60
private const val P1 = 0.01
private const val MetersPerDegree = 111_195.0

private const val RateCriterion =
    ">= 24 Hz over the session, the RaceBox's whole-second skip left out (FBBD-8, FBBD-46, FBBD-170)"
private const val DroppedCriterion =
    "<= 0.5 % of expected, link drops and the RaceBox's whole-second skip left out (FBBD-8, FBBD-46, FBBD-170)"
private const val GapCriterion = "<= 200 ms, link drops left out (FBBD-8, FBBD-46)"
private const val ReconnectCriterion = "data back within 5 s, same session (FBBD-8, FBBD-43)"
private const val StationaryCriterion = "2D standard deviation <= 0.5 m over 60 s parked (FBBD-8)"

internal fun logGnssSection(
    seconds: List<GnssSecond>,
    events: List<RecorderEvent>,
    endMicros: Long,
    raceBox: Boolean = false,
): QualitySection {
    if (seconds.size < 2) {
        val why = "fewer than two seconds of GNSS in the log for this session"
        return QualitySection("GNSS", listOf(notMeasured("gnss.rate", "Average sample rate", RateCriterion, why)))
    }
    val byIndex = seconds.associateBy { it.second }
    val firstIndex = seconds.first().second
    val lastIndex = seconds.last().second
    // Whole seconds between the first and the last, without a link drop: a second with no line had no sample.
    // A link drop runs from the last second before it to the first second after it.
    val drops = seconds.zipWithNext()
        .filter { (_, b) -> b.afterDrop }
        .flatMap { (a, b) -> (a.second..b.second).toList() }
        .toSet()
    val whole = ((firstIndex + 1) until lastIndex).filter { k -> k !in drops }
    val counts = whole.map { (byIndex[it]?.samples ?: 0).toDouble() }
    // As for a session: the RaceBox's whole-second skip with a fix (FBBD-170) counts as received.
    val skips = if (raceBox) whole.sumOf { (byIndex[it]?.missingOnSecond ?: 0).toLong() } else 0L
    val metrics = buildList {
        add(rate(counts, skips))
        add(
            info(
                "gnss.rate_p1", "Sample rate, 1st percentile", nearestRank(counts, P1), "Hz", Basis.MEASURED,
                "samples in each whole second, 1st percentile, seconds with a link drop left out",
            ),
        )
        addAll(dropped(seconds, raceBox))
        addAll(logMaxGap(seconds))
        add(
            info(
                "gnss.long_gaps", "Gaps over 200 ms", logLongGaps(seconds).toDouble(), "", Basis.MEASURED,
                "receiver steps over 200 ms, link drops and those before the first fix (FBBD-273) left out",
            ),
        )
        addAll(reconnects(seconds, events, endMicros))
        add(stationary(seconds))
    }
    // Logs before 0.3.31 took the lag over samples without a fix too, when the receiver's time can be a
    // guess: only seconds whose every sample had a fix count, so a second a fix began in is left out.
    val lags = seconds.filter { it.fixes > 0 && it.fixes == it.samples }
        .mapNotNull { g -> g.lagMicros?.let { g.tMicros to it } }
    val notes = logGapNotes(seconds)
    return QualitySection("GNSS", metrics + clockDrift(lags, endMicros), notes)
}

private fun rate(counts: List<Double>, skips: Long): QualityMetric {
    if (counts.isEmpty()) {
        return notMeasured("gnss.rate", "Average sample rate", RateCriterion, "no whole second of GNSS")
    }
    val received = counts.sum() / counts.size
    val hz = (counts.sum() + skips) / counts.size
    val counted = if (skips > 0) {
        ", with the $skips whole-second messages the RaceBox does not send with a fix counted " +
            "(received: ${fixed(received, 2)} Hz)"
    } else {
        ""
    }
    return judged(
        "gnss.rate", "Average sample rate", hz, "Hz", hz >= MinRateHz, Basis.MEASURED, RateCriterion,
        "samples received per whole second on the phone clock over ${counts.size} s, seconds with a link drop " +
            "left out$counted",
    )
}

/**
 * As for a session: a RaceBox's single whole-second skip with a fix (FBBD-170) is counted apart, and the
 * verdict counts from the receiver's first fix (FBBD-259). A line counts losses ending in its second, and
 * the step that ends on the first fix ends in the second the fix came in, so that second goes with the
 * time before it, even when every sample in it had a fix.
 */
private fun dropped(seconds: List<GnssSecond>, raceBox: Boolean): List<QualityMetric> {
    if (seconds.none { it.stepMillis != null }) {
        val why = "the receiver gave no fix times"
        return listOf(notMeasured("gnss.dropped", "Dropped samples", DroppedCriterion, why))
    }
    val fixAt = seconds.indexOfFirst { it.fixes > 0 }
    val from = if (fixAt < 0) seconds.size else fixAt + 1
    val before = seconds.take(from)
    val after = seconds.drop(from)
    fun skips(s: List<GnssSecond>) = if (raceBox) s.sumOf { it.missingOnSecond.toLong() } else 0L
    val beforeFix = before.sumOf { it.missing.toLong() } - skips(before)
    val firstFix = seconds.getOrNull(fixAt)?.let { it.firstFixMicros ?: it.tMicros }
    val info = beforeFixInfo(beforeFix, firstFix)
    val metric = if (after.isEmpty()) {
        val why = if (fixAt < 0) NoFixText else "no whole second of GNSS was logged after the first fix"
        notMeasured("gnss.dropped", "Dropped samples", DroppedCriterion, why)
    } else {
        val all = after.sumOf { it.missing.toLong() }
        val wholeSecond = skips(after)
        val missing = all - wholeSecond
        val expected = after.sumOf { it.samples.toLong() } + all
        val percent = missing * Percent / expected
        judged(
            "gnss.dropped", "Dropped samples", percent, "%", percent <= MaxDroppedPercent, Basis.MEASURED,
            DroppedCriterion,
            "$missing of $expected expected samples missing from the receiver's fix times since its first fix, " +
                "besides $wholeSecond single whole-second messages the RaceBox does not send with a fix; " +
                "$beforeFix missing before the first fix, counted apart",
        )
    }
    return listOf(metric, info)
}

/** Each logged link drop, timed by the wait between fixes that ended in the first second after it. */
private fun reconnects(secs: List<GnssSecond>, events: List<RecorderEvent>, endMicros: Long): List<QualityMetric> {
    val all = events.filter { it.kind in GnssDisconnectKinds }
    val count = linkDrops(all.size)
    // Or after the drive, which had ended under cover (underCoverFrom).
    val underCover = logUnderCoverFrom(secs)
    val covered = all.filter { afterDrive(it.tMicros, underCover) }
    val drops = all.filterNot { d -> d in covered || logEndOfDrive(secs, d, endMicros) }
    if (drops.isEmpty()) {
        val why = when {
            all.isEmpty() -> "no link drop in this session"
            covered.isNotEmpty() -> UnderCoverText
            else -> EndOfDriveText
        }
        return listOf(count, notMeasured("gnss.reconnect", "Longest reconnect", ReconnectCriterion, why))
    }
    val waits = drops.map { d ->
        val back = secs.firstOrNull { it.afterDrop && it.tMicros >= d.tMicros }
        back?.maxGapMicros ?: (endMicros - (secs.lastOrNull { it.tMicros <= d.tMicros }?.tMicros ?: d.tMicros))
    }
    val back = drops.all { d -> secs.any { it.afterDrop && it.tMicros >= d.tMicros } }
    val longest = waits.max()
    return listOf(
        count,
        judged(
            "gnss.reconnect", "Longest reconnect", seconds(longest), "s", back && longest <= MaxReconnectMicros,
            Basis.MEASURED,
            ReconnectCriterion, "time without a fix around each drop, from the log's second-by-second GNSS",
        ),
    )
}

/** The worst 60 s stop: seconds in a row under 1 km/h, their spread combined about their joint mean. */
private fun stationary(seconds: List<GnssSecond>): QualityMetric {
    val label = "Position noise parked"
    val runs = mutableListOf<List<GnssSecond>>()
    var run = mutableListOf<GnssSecond>()
    for (g in seconds) {
        val parked = g.fixes > 0 && (g.kmhMax ?: Double.MAX_VALUE) < StationaryKmh && g.lat != null && g.lon != null
        if (parked && (run.isEmpty() || g.second == run.last().second + 1)) {
            run += g
        } else {
            if (run.size >= StationarySeconds) runs += run.take(StationarySeconds)
            run = if (parked) mutableListOf(g) else mutableListOf()
        }
    }
    if (run.size >= StationarySeconds) runs += run.take(StationarySeconds)
    if (runs.isEmpty()) return notMeasured("gnss.stationary", label, StationaryCriterion, "no stop of 60 s or more")
    val worst = runs.maxOf(::spread)
    return judged(
        "gnss.stationary", label, worst, "m", worst <= MaxStationaryStdMeters, Basis.MEASURED, StationaryCriterion,
        "worst of ${runs.size} stop(s) of 60 s or more; each second's spread and mean combined",
    )
}

private fun spread(run: List<GnssSecond>): Double {
    val n = run.sumOf { it.fixes }.toDouble()
    val lat = run.sumOf { it.lat!! * it.fixes } / n
    val lon = run.sumOf { it.lon!! * it.fixes } / n
    val kx = MetersPerDegree * cos(lat * PI / DegreesPerHalfTurn)
    val total = run.sumOf { g ->
        val dx = (g.lon!! - lon) * kx
        val dy = (g.lat!! - lat) * MetersPerDegree
        g.fixes * ((g.varianceM2 ?: 0.0) + dx * dx + dy * dy)
    }
    return sqrt(total / n)
}

/**
 * The GNSS values an older build's log holds: the RaceBox's own count of messages received and
 * missing, every 1500 messages. Rate and dropped samples only; gaps were not logged.
 */
internal fun legacyGnssSection(s: LoggedSession, endMicros: Long): QualitySection {
    val stats = s.raceBox
    if (stats.isEmpty()) {
        val why = "this build's log has no GNSS statistics for the session"
        return QualitySection("GNSS", listOf(notMeasured("gnss.rate", "Average sample rate", RateCriterion, why)))
    }
    val received = stats.sumOf { it.second }
    val missing = stats.sumOf { it.third }
    val percent = missing * Percent / (received + missing)
    val spans = stats.zipWithNext { a, b -> b.second to (b.first - a.first) }
    val rate = if (spans.isEmpty()) null else spans.sumOf { it.first } * MillisPerSecond / spans.sumOf { it.second }
    val why = "an older build logged no fix times or gaps"
    return QualitySection(
        "GNSS",
        listOfNotNull(
            rate?.let {
                judged(
                    "gnss.rate", "Average sample rate", it, "Hz", it >= MinRateHz, Basis.DERIVED, RateCriterion,
                    "RaceBox messages per second between its statistics lines (every 1500 messages, timed by the log)",
                )
            },
            judged(
                "gnss.dropped", "Dropped samples", percent, "%", percent <= MaxDroppedPercent, Basis.MEASURED,
                DroppedCriterion,
                "$missing of ${received + missing} RaceBox messages missing, as the RaceBox driver counted them",
            ),
            notMeasured("gnss.max_gap", "Longest gap", GapCriterion, why),
            notMeasured("gnss.stationary", "Position noise parked", StationaryCriterion, why),
        ) + reconnectsLegacy(s.events, endMicros),
    )
}

private fun linkDrops(n: Int) =
    info("gnss.link_drops", "Link drops", n.toDouble(), "", Basis.MEASURED, "link-drop events the GNSS source logged")

private fun reconnectsLegacy(events: List<RecorderEvent>, endMicros: Long): List<QualityMetric> {
    val drops = events.filter { it.kind in GnssDisconnectKinds }
    val count = linkDrops(drops.size)
    val why = if (drops.isEmpty()) {
        "no link drop in this session"
    } else {
        "an older build logged no fix times (session ended at ${seconds(endMicros)} s)"
    }
    return listOf(count, notMeasured("gnss.reconnect", "Longest reconnect", ReconnectCriterion, why))
}

private const val MillisPerSecond = 1_000.0
