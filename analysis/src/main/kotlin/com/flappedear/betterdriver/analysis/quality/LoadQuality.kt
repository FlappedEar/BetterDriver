package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.LoadLine
import com.flappedear.betterdriver.recorder.log.LogLine
import java.util.Locale

// How hard each of the app's processes worked while the session recorded (FBBD-281), from the
// once-a-minute load lines every process logs. Shown for information on the phone; the CI replay drive
// holds each process to a budget (ReplayDriveTest).

/** The id of [process]'s CPU metric: "load.recorder.cpu". */
fun loadCpuId(process: String) = "load.$process.cpu"

/** One metric per process that logged load lines: its mean CPU over the session's minutes. Null without any. */
internal fun loadSection(lines: List<LogLine>): QualitySection? {
    val byProcess = lines.filter { it.tag == LoadLine.TAG }
        .mapNotNull { line -> LoadLine.parse(line.message)?.let { line.process to it } }
        .groupBy({ it.first }, { it.second })
    if (byProcess.isEmpty()) return null
    val metrics = byProcess.toSortedMap().map { (process, minutes) -> cpu(process, minutes) }
    return QualitySection("App load", metrics)
}

private fun cpu(process: String, minutes: List<LoadLine>): QualityMetric {
    val wall = minutes.sumOf { it.wallMillis }
    val mean = minutes.sumOf { it.cpuMillis } * Percent / wall
    val peak = minutes.maxOf { it.cpuPercent }
    val gc = minutes.sumOf { it.gcCount }
    val gcMs = minutes.sumOf { it.gcMillis }
    val alloc = minutes.sumOf { it.allocatedMb } * MillisPerMinute / wall
    val how = String.format(
        Locale.ROOT,
        "mean of %d logged minutes, %% of one core; busiest minute %.1f %%; %d GC (%d ms); " +
            "%.1f MB allocated a minute; up to %d MB heap, %d threads",
        minutes.size, peak, gc, gcMs, alloc, minutes.maxOf { it.heapUsedMb }, minutes.maxOf { it.threads },
    )
    return info(loadCpuId(process), "CPU, $process process", mean, "%", Basis.MEASURED, how)
}

private const val MillisPerMinute = 60_000.0
