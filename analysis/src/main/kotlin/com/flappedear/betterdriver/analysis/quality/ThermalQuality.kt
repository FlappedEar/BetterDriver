package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.video.VideoEventText

// The thermal part of the session validation report (FBBD-183), against FBBD-46: the phone's
// thermal status while the camera recorded and the camera's thermal step-downs.

/** Android's PowerManager.THERMAL_STATUS_SEVERE. */
private const val Severe = 3

private val StatusNames = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

/** Video stops of the thermal policy (`:app`'s PhoneCamera): a step down, or video off. */
private val ThermalStops = setOf("thermal_step", "thermal")

private const val ThermalCriterion = "never SEVERE before the app's mitigation engaged; no thermal shutdown (FBBD-46)"

internal fun thermalSection(events: List<RecorderEvent>): QualitySection {
    val statuses = events.ofKind(EventKinds.THERMAL_STATUS).mapNotNull { e ->
        fields(e.text)["status"]?.toIntOrNull()?.let { e.tMicros to it }
    }
    val steps = events.ofKind(EventKinds.VIDEO_STOP).filter { reason(it) in ThermalStops }
    val stepCount = info(
        "thermal.step_downs", "Camera thermal step-downs", steps.size.toDouble(), "", Basis.MEASURED,
        "video files ended for a thermal step-down or stop",
    )
    val notMeasuredYet = listOf(
        notMeasured(
            "thermal.battery", "Battery temperature", "logged every 30 s, peak reported (FBBD-46)",
            "not logged in the session",
        ),
        notMeasured("thermal.jank", "UI jank", "< 5 % of frames (FBBD-46)", "not logged in the session"),
    )
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
    val notes = statuses.map { (t, s) -> QualityNote(t, "thermal status ${name(s)}") } +
        steps.map { QualityNote(it.tMicros, "camera ${reason(it)?.replace('_', ' ')}") }
    return QualitySection("Thermal", metrics, notes.sortedBy { it.tMicros })
}

private fun reason(e: RecorderEvent) = VideoEventText.parse(e.text)[VideoEventText.REASON]

private fun name(status: Int) = StatusNames.getOrNull(status) ?: "level $status"
