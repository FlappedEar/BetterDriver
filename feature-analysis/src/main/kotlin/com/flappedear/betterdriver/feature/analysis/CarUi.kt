package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.data.SamplePoint
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.heartrate.heartRateSeries
import com.flappedear.betterdriver.analysis.heartrate.heartRateStats
import com.flappedear.betterdriver.analysis.vehicle.PedalShares
import com.flappedear.betterdriver.analysis.vehicle.TemperatureRange
import com.flappedear.betterdriver.analysis.vehicle.VehicleChannel
import com.flappedear.betterdriver.analysis.vehicle.lapVehicleStretches
import com.flappedear.betterdriver.analysis.vehicle.sessionVehicleStretch
import kotlin.math.roundToInt

/**
 * The Car tab (FBBD-176, UI review round 40): the pedals by lap (UI-158 C), the temperatures by lap
 * (UI-159 B) and the heart rate over the session (UI-160 A). A road drive without laps has one row
 * for the whole drive (UI-162 A). Only what was recorded: a part the session has nothing for says it
 * was not recorded, never shows zero.
 */
data class CarUi(
    val rows: List<CarRow>,
    val heartRate: HeartRateChartUi?,
) {
    /** Whether any row has pedal shares. */
    val hasPedals: Boolean get() = rows.any { it.pedals != null }

    /** The temperatures any row has, in [VehicleChannel.Temperatures] order. */
    val temperatureKinds: List<VehicleChannel>
        get() = VehicleChannel.Temperatures.filter { k -> rows.any { r -> r.temperatures.any { it.kind == k } } }

    /** Whether the session has none of it. */
    val empty: Boolean get() = !hasPedals && temperatureKinds.isEmpty() && heartRate == null
}

/** One lap, or the whole drive ([lapNumber] null), of the Car tab. */
data class CarRow(
    val lapNumber: Int?,
    val millis: Long?,
    val best: Boolean,
    val pedals: PedalShares?,
    val temperatures: List<TemperatureRange>,
)

/**
 * The heart rate over the session (UI-160 A): the figures of the Summary card, the chart's
 * [segments] (bpm against session seconds, a gap between two segments left blank), each lap's
 * start and end for its shading, and the chart's time span.
 */
data class HeartRateChartUi(
    val stats: HeartRateUi,
    val segments: List<List<SamplePoint>>,
    val laps: List<Pair<Double, Double>>,
    val fromSeconds: Double,
    val toSeconds: Double,
)

/** [ui] with its Car tab, from [session] as [analysis] timed it; [device] is the heart rate strap's name. */
fun withCar(ui: AnalysisUi, analysis: SessionAnalysis, session: TelemetrySession, device: String?): AnalysisUi {
    val laps = analysis.laps.timedLaps
    val stretches = lapVehicleStretches(session, laps)
    val rows = ui.laps.mapNotNull { row ->
        stretches.getOrNull(row.index)?.let { CarRow(row.number, row.millis, row.best, it.pedals, it.temperatures) }
    }
    val spans = laps.map { it.startTelemetryTime to it.endTelemetryTime }
    return ui.copy(car = CarUi(rows, heartRateChart(session, device, spans)))
}

/** The Car cards of a session without laps (a road drive, UI-162 A); null when it recorded none of it. */
fun wholeDriveCar(session: TelemetrySession, device: String?): CarUi? {
    val whole = sessionVehicleStretch(session)
    val rows = listOfNotNull(whole?.let { CarRow(null, null, false, it.pedals, it.temperatures) })
    return CarUi(rows, heartRateChart(session, device, emptyList())).takeUnless { it.empty }
}

@Suppress("ReturnCount") // No heart rate, or nothing to draw.
private fun heartRateChart(
    session: TelemetrySession,
    device: String?,
    laps: List<Pair<Double, Double>>,
): HeartRateChartUi? {
    val whole = heartRateStats(session) ?: return null
    val segments = heartRateSeries(session)
    if (segments.isEmpty()) return null
    val minutes = (whole.coveredSeconds / SecondsPerMinute).roundToInt().coerceAtLeast(1)
    val times = segments.flatten().map { it.time } + laps.flatMap { listOf(it.first, it.second) }
    return HeartRateChartUi(
        HeartRateUi(device, whole.averageBpm, whole.maxBpm, whole.minBpm, minutes),
        segments,
        laps,
        times.min(),
        times.max(),
    )
}

private const val SecondsPerMinute = 60.0
