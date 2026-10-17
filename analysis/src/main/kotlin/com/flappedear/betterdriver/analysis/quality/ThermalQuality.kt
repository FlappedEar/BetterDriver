package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.video.VideoEventText

// The thermal part of the session validation report (FBBD-183), against FBBD-46: the phone's
// thermal status (from the camera while it records, and from the recorder's health reports every
// 30 s), the camera's thermal step-downs and the battery temperature.

/** Android's PowerManager.THERMAL_STATUS_SEVERE. */
private const val Severe = 3

/** Android's PowerManager.THERMAL_STATUS_SHUTDOWN: fails whatever the camera did before. */
private const val Shutdown = 6

private val StatusNames = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

/** Video stops of the thermal policy (`:app`'s PhoneCamera): a step down, or video off. */
private val ThermalStops = setOf("thermal_step", "thermal")

private const val ThermalCriterion = "never SEVERE before the app's mitigation engaged; no thermal shutdown (FBBD-46)"

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
        "thermal.step_downs", "Camera thermal step-downs", steps.size.toDouble(), "", Basis.MEASURED,
        "video files ended for a thermal step-down or stop",
    )
    val notMeasuredYet = battery(events, endMicros)
    if (statuses.isEmpty()) {
        val why = "thermal status is logged while the phone camera records, from this version on"
        val status = notMeasured("thermal.status", "Thermal mitigation", ThermalCriterion, why)
        return QualitySection("Thermal", listOf(status, stepCount) + notMeasuredYet)
    }
    val max = statuses.maxOf { it.second }
    val firstSevere = statuses.firstOrNull { it.second >= Severe }?.first
    val firstStep = steps.minOfOrNull { it.tMicros }
    // Heat stress test (FBBD-231): the camera was told to ignore the temperature, so no step-down is
    // expected and only a thermal shutdown fails.
    val ignoredAt = events.ofKind(EventKinds.THERMAL_STATUS).firstOrNull { fields(it.text)["heat"] == "ignored" }
    val mitigated = ignoredAt != null || firstSevere == null || (firstStep != null && firstStep <= firstSevere)
    val ok = max < Shutdown && mitigated
    val status = if (ignoredAt != null) {
        judged(
            "thermal.status", "Thermal mitigation", max.toDouble(), "", ok, Basis.MEASURED, IgnoredCriterion,
            "phone temperature ignored for a heat stress test; highest status ${name(max)}, a shutdown fails",
        )
    } else {
        judged(
            "thermal.status", "Thermal mitigation", max.toDouble(), "", ok, Basis.MEASURED, ThermalCriterion,
            "highest status ${name(max)}; a step-down or stop must come before the first severe reading, " +
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
