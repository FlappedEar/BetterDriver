package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.video.VideoEventText

// The thermal part of the session validation report (FBBD-183), against FBBD-46: the phone's
// thermal status (from the camera while it records, and from the recorder's health reports every
// 30 s), the camera's thermal stops and the battery temperature.
//
// From 0.3.29 video keeps its mode and stops only at a 60 °C battery or Android's emergency (Arek's
// heat stress test of 2026-10-17: status critical at a 45 °C battery, 4K · 60 fine for 55 min). So
// Android's severe or critical label no longer fails a session; a 60 °C battery with video still
// running does.

/** The battery temperature that must stop phone video (`:app`'s StopBatteryC). */
private const val StopBatteryC = 60.0

/** The camera checks every 5 s; its stop may land a little after the health report that saw 60 °C. */
private const val StopSlackMicros = 10_000_000L

/** Android's PowerManager.THERMAL_STATUS_SHUTDOWN: fails whatever the camera did before. */
private const val Shutdown = 6

private val StatusNames = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

/** Video stops of the thermal policy (`:app`'s PhoneCamera): video off, or a step down before 0.3.29. */
private val ThermalStops = setOf("thermal_step", "thermal")

private const val ThermalCriterion =
    "video stopped by the time the battery reaches 60 °C; no thermal shutdown (FBBD-46)"

private const val IgnoredCriterion = "no thermal shutdown; mitigation off for a heat stress test (FBBD-231)"

internal fun thermalSection(events: List<RecorderEvent>, endMicros: Long): QualitySection {
    val fromCamera = events.ofKind(EventKinds.THERMAL_STATUS).mapNotNull { e ->
        fields(e.text)["status"]?.toIntOrNull()?.let { e.tMicros to it }
    }
    val fromRecorder = events.ofKind(EventKinds.DEVICE).mapNotNull { e ->
        fields(e.text)["thermal"]?.toIntOrNull()?.let { e.tMicros to it }
    }
    val statuses = (fromCamera + fromRecorder).sortedBy { it.first }
    val steps = events.ofKind(EventKinds.VIDEO_STOP).filter { reason(it) in ThermalStops }
    val stepCount = info(
        "thermal.step_downs", "Camera thermal stops", steps.size.toDouble(), "", Basis.MEASURED,
        "video files ended for heat",
    )
    val notMeasuredYet = battery(events, endMicros)
    if (statuses.isEmpty()) {
        val why = "thermal status is logged while the phone camera records, from this version on"
        val status = notMeasured("thermal.status", "Thermal mitigation", ThermalCriterion, why)
        return QualitySection("Thermal", listOf(status, stepCount) + notMeasuredYet)
    }
    val max = statuses.maxOf { it.second }
    val firstHot = events.ofKind(EventKinds.DEVICE).firstOrNull { e ->
        (fields(e.text)["battery_c"]?.toDoubleOrNull() ?: 0.0) >= StopBatteryC
    }?.tMicros
    val firstStep = steps.minOfOrNull { it.tMicros }
    // Heat stress test (FBBD-231): the camera was told to ignore the temperature, so no step-down is
    // expected and only a thermal shutdown fails.
    val ignoredAt = events.ofKind(EventKinds.THERMAL_STATUS).firstOrNull { fields(it.text)["heat"] == "ignored" }
    val stoppedInTime = firstHot == null || (firstStep != null && firstStep <= firstHot + StopSlackMicros)
    val mitigated = ignoredAt != null || stoppedInTime
    val ok = max < Shutdown && mitigated
    val status = if (ignoredAt != null) {
        judged(
            "thermal.status", "Thermal mitigation", max.toDouble(), "", ok, Basis.MEASURED, IgnoredCriterion,
            "phone temperature ignored for a heat stress test; highest status ${name(max)}, a shutdown fails",
        )
    } else {
        judged(
            "thermal.status", "Thermal mitigation", max.toDouble(), "", ok, Basis.MEASURED, ThermalCriterion,
            "highest status ${name(max)}; video must stop by the first battery reading of 60 °C, " +
                "and a shutdown fails",
        )
    }
    val metrics = listOf(
        info(
            "thermal.max", "Highest thermal status", max.toDouble(), "", Basis.MEASURED,
            "Android thermal status ${name(max)}",
        ),
        status,
        stepCount,
    ) + notMeasuredYet
    val changes = statuses.filterIndexed { i, (_, s) -> i == 0 || statuses[i - 1].second != s }
    val notes = changes.map { (t, s) -> QualityNote(t, "thermal status ${name(s)}") } +
        steps.map { QualityNote(it.tMicros, "camera ${reason(it)?.replace('_', ' ')}") } +
        listOfNotNull(ignoredAt?.let { QualityNote(it.tMicros, "phone temperature ignored (heat stress test)") })
    return QualitySection("Thermal", metrics, notes.sortedBy { it.tMicros })
}

private const val BatteryCriterion = "battery temperature logged at least every 30 s, peak reported (FBBD-46)"

/** A health report this much later than 30 s still counts as on time: the timer is not exact. */
private const val BatterySlackMicros = 5_000_000L
private const val BatteryEveryMicros = 30_000_000L

/**
 * The battery temperature from the recorder's health reports: logged often enough, and its peak. The
 * gaps counted include the session's start to the first report and the last report to its end
 * ([endMicros]), so a timer that stalls after its first report fails.
 */
private fun battery(events: List<RecorderEvent>, endMicros: Long): List<QualityMetric> {
    val temps = events.ofKind(EventKinds.DEVICE).mapNotNull { e ->
        fields(e.text)["battery_c"]?.toDoubleOrNull()?.let { e.tMicros to it }
    }
    if (temps.isEmpty()) {
        val why = "the recorder logs the battery temperature every 30 s from this version on"
        return listOf(notMeasured("thermal.battery", "Battery temperature logged", BatteryCriterion, why))
    }
    val times = listOf(0L) + temps.map { it.first }.sorted() + maxOf(endMicros, temps.maxOf { it.first })
    val longest = times.zipWithNext { a, b -> b - a }.max()
    val peak = temps.maxBy { it.second }
    return listOf(
        judged(
            "thermal.battery", "Battery temperature logged", seconds(longest), "s",
            longest <= BatteryEveryMicros + BatterySlackMicros, Basis.MEASURED, BatteryCriterion,
            "longest time between the recorder's ${temps.size} health reports, the session's start and its end",
        ),
        info(
            "thermal.battery_peak", "Battery temperature peak", peak.second, "C", Basis.MEASURED,
            "highest battery temperature reported, at ${seconds(peak.first).toLong()} s",
        ),
    )
}

private fun reason(e: RecorderEvent) = VideoEventText.parse(e.text)[VideoEventText.REASON]

private fun name(status: Int) = StatusNames.getOrNull(status) ?: "level $status"
