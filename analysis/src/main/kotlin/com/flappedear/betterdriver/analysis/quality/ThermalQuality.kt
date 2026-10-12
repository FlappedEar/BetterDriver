package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.video.VideoEventText

// The thermal part of the session validation report (FBBD-183), against FBBD-46: the phone's
// thermal status (from the camera while it records, and from the recorder's health reports every
// 30 s), the camera's thermal step-downs and the battery temperature.

/** Android's PowerManager.THERMAL_STATUS_SEVERE. */
private const val Severe = 3

private val StatusNames = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

/** Video stops of the thermal policy (`:app`'s PhoneCamera): a step down, or video off. */
private val ThermalStops = setOf("thermal_step", "thermal")

private const val ThermalCriterion = "never SEVERE before the app's mitigation engaged; no thermal shutdown (FBBD-46)"

internal fun thermalSection(events: List<RecorderEvent>): QualitySection {
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
    val notMeasuredYet = battery(events)
    if (statuses.isEmpty()) {
        val why = "thermal status is logged while the phone camera records, from this version on"
        val status = notMeasured("thermal.status", "Thermal mitigation", ThermalCriterion, why)
        return QualitySection("Thermal", listOf(status, stepCount) + notMeasuredYet)
    }
    val max = statuses.maxOf { it.second }
    val firstSevere = statuses.firstOrNull { it.second >= Severe }?.first
    val firstStep = steps.minOfOrNull { it.tMicros }
    val ok = firstSevere == null || (firstStep != null && firstStep <= firstSevere)
    val metrics = listOf(
        info(
            "thermal.max", "Highest thermal status", max.toDouble(), "", Basis.MEASURED,
            "Android thermal status ${name(max)}",
        ),
        judged(
            "thermal.status", "Thermal mitigation", max.toDouble(), "", ok, Basis.MEASURED, ThermalCriterion,
            "highest status ${name(max)}; a step-down or stop must come before the first severe reading",
        ),
        stepCount,
    ) + notMeasuredYet
    val changes = statuses.filterIndexed { i, (_, s) -> i == 0 || statuses[i - 1].second != s }
    val notes = changes.map { (t, s) -> QualityNote(t, "thermal status ${name(s)}") } +
        steps.map { QualityNote(it.tMicros, "camera ${reason(it)?.replace('_', ' ')}") }
    return QualitySection("Thermal", metrics, notes.sortedBy { it.tMicros })
}

private const val BatteryCriterion = "battery temperature logged at least every 30 s, peak reported (FBBD-46)"

/** A health report this much later than 30 s still counts as on time: the timer is not exact. */
private const val BatterySlackMicros = 5_000_000L
private const val BatteryEveryMicros = 30_000_000L

/** The battery temperature from the recorder's health reports: logged often enough, and its peak. */
private fun battery(events: List<RecorderEvent>): List<QualityMetric> {
    val temps = events.ofKind(EventKinds.DEVICE).mapNotNull { e ->
        fields(e.text)["battery_c"]?.toDoubleOrNull()?.let { e.tMicros to it }
    }
    if (temps.isEmpty()) {
        val why = "the recorder logs the battery temperature every 30 s from this version on"
        return listOf(notMeasured("thermal.battery", "Battery temperature logged", BatteryCriterion, why))
    }
    val longest = temps.map { it.first }.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L
    val peak = temps.maxBy { it.second }
    return listOf(
        judged(
            "thermal.battery", "Battery temperature logged", seconds(longest), "s",
            longest <= BatteryEveryMicros + BatterySlackMicros, Basis.MEASURED, BatteryCriterion,
            "longest time between the recorder's ${temps.size} health reports",
        ),
        info(
            "thermal.battery_peak", "Battery temperature peak", peak.second, "C", Basis.MEASURED,
            "highest battery temperature reported, at ${seconds(peak.first).toLong()} s",
        ),
    )
}

private fun reason(e: RecorderEvent) = VideoEventText.parse(e.text)[VideoEventText.REASON]

private fun name(status: Int) = StatusNames.getOrNull(status) ?: "level $status"
