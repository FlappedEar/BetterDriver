package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.heartrate.heartRateStats
import com.flappedear.betterdriver.analysis.heartrate.lapHeartRates
import kotlin.math.roundToInt

/**
 * [ui] with the heart rate [session] recorded (FBBD-225): the whole session's and each lap's.
 * The minutes are how long the strap was sending, gaps left out. [device] is the strap's name.
 * A session or lap without heart rate shows none (UI-109).
 */
fun withHeartRate(ui: AnalysisUi, analysis: SessionAnalysis, session: TelemetrySession, device: String?): AnalysisUi {
    val whole = heartRateStats(session) ?: return ui
    val laps = lapHeartRates(session, analysis.laps.timedLaps)
    val minutes = (whole.coveredSeconds / SecondsPerMinute).roundToInt().coerceAtLeast(1)
    return ui.copy(
        heartRate = HeartRateUi(device, whole.averageBpm, whole.maxBpm, whole.minBpm, minutes),
        laps = ui.laps.map { row ->
            row.copy(heartRate = laps.getOrNull(row.index)?.let { LapHeartRate(it.averageBpm, it.maxBpm) })
        },
    )
}

private const val SecondsPerMinute = 60.0
