package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.AppLifeLog
import com.flappedear.betterdriver.recorder.log.LogLine
import com.flappedear.betterdriver.recorder.log.ProcessExit

// The app's own start and its ANRs (FBBD-41), from the continuous log: cold starts are timed in the
// UI process, and Android's record of how the app's processes ended is read at each start and when a
// recording ends. Neither belongs to a recording, so each drive gets those around it.

private const val MaxColdStartMillis = 1_500L
private const val ColdStartCriterion = "cold start <= 1.5 s (FBBD-41)"
private const val AnrCriterion = "0 ANRs over the session (FBBD-41)"

/** A cold start logged at [utcMillis] that took [millis] to its first frame. */
internal class ColdStart(val utcMillis: Long, val millis: Long)

/** A read of Android's exit record at [utcMillis]; [read] false when this Android keeps none (before 11). */
internal class ExitCheck(val utcMillis: Long, val read: Boolean)

/** What the whole log says about the app's starts and process ends, whichever recording was running. */
internal class AppLife(val coldStarts: List<ColdStart>, val checks: List<ExitCheck>, val exits: List<ProcessExit>) {
    companion object {
        fun of(lines: List<LogLine>): AppLife {
            val cold = lines.filter { it.tag == AppLifeLog.STARTUP }
                .mapNotNull { l -> AppLifeLog.parseColdStart(l.message)?.let { ColdStart(l.utcMillis, it) } }
            val exitLines = lines.filter { it.tag == AppLifeLog.EXIT }
            val checks = exitLines.mapNotNull { l ->
                AppLifeLog.parseCheck(l.message)?.let { ExitCheck(l.utcMillis, it) }
            }
            // Each end is logged once per process that read it; the same end read twice is one end.
            val exits = exitLines.mapNotNull { AppLifeLog.parseExit(it.message) }.distinctBy { it.utcMillis to it.pid }
            return AppLife(cold.sortedBy { it.utcMillis }, checks.sortedBy { it.utcMillis }, exits)
        }
    }
}

/**
 * The responsiveness section of a recording from [startUtc] to [endUtc] (phone wall clock): the cold
 * starts logged after [sinceUtc] (the previous recording's end, null for the log's start) up to its
 * end, which are the starts that led to this drive or happened during it, and the ANRs Android
 * recorded while it ran.
 */
internal fun startupSection(life: AppLife, sinceUtc: Long?, startUtc: Long, endUtc: Long): QualitySection {
    val metrics = listOf(coldStart(life, sinceUtc, endUtc), anrs(life, startUtc, endUtc))
    return QualitySection("Responsiveness", metrics)
}

private fun coldStart(life: AppLife, sinceUtc: Long?, endUtc: Long): QualityMetric {
    val starts = life.coldStarts.filter { (sinceUtc == null || it.utcMillis > sinceUtc) && it.utcMillis <= endUtc }
    val worst = starts.maxOfOrNull { it.millis } ?: return notMeasured(
        "app.cold_start", "Cold start", ColdStartCriterion,
        "no cold start of the app was logged before or during this recording (logged from this version on; " +
            "a warm start or a screen Android brought back is not timed)",
    )
    return judged(
        "app.cold_start", "Cold start", worst.toDouble(), "ms", worst <= MaxColdStartMillis, Basis.MEASURED,
        ColdStartCriterion,
        "from the app's process start to its screen's first frame; worst of ${starts.size} cold start(s) " +
            "logged before or during this recording",
    )
}

private fun anrs(life: AppLife, startUtc: Long, endUtc: Long): QualityMetric {
    val during = life.exits.filter { it.reason == ProcessExit.ANR && it.utcMillis in startUtc..endUtc }
    // Android tells how a process ended only when asked later; a read after the end covers the whole drive.
    val covered = life.checks.any { it.read && it.utcMillis >= endUtc }
    if (during.isEmpty() && !covered) {
        val why = if (life.checks.isNotEmpty() && life.checks.none { it.read }) {
            "Android before 11 keeps no record of how the app's processes ended"
        } else {
            "how the app's processes ended is read when a recording ends and when the app starts, " +
                "from this version on; no read after this recording was logged"
        }
        return notMeasured("app.anr", "ANRs", AnrCriterion, why)
    }
    val processes = during.map { it.process }.distinct().joinToString(", ").ifEmpty { "none" }
    return judged(
        "app.anr", "ANRs", during.size.toDouble(), "", during.isEmpty(), Basis.MEASURED, AnrCriterion,
        "app processes Android ended as not responding while the session recorded (ApplicationExitInfo); " +
            "processes: $processes",
    )
}
