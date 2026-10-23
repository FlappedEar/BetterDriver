package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.data.SamplePoint
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.heartrate.heartRateSeries
import com.flappedear.betterdriver.analysis.heartrate.heartRateStats
import com.flappedear.betterdriver.analysis.vehicle.PedalShares
import com.flappedear.betterdriver.analysis.vehicle.TemperatureRange
import com.flappedear.betterdriver.analysis.vehicle.brakePressureStats
import com.flappedear.betterdriver.analysis.vehicle.VehicleChannel
import com.flappedear.betterdriver.analysis.vehicle.lapVehicleStretches
import com.flappedear.betterdriver.analysis.vehicle.sessionVehicleStretch
import com.flappedear.betterdriver.analysis.vehicle.steeringStats
import com.flappedear.betterdriver.analysis.vehicle.steeringTrace
import kotlin.math.roundToInt

/**
 * The Car tab (FBBD-176, UI review round 40): the pedals by lap (UI-158 C), the temperatures by lap
 * (UI-159 B) and the heart rate over the session (UI-160 A). A road drive without laps has one row
 * for the whole drive (UI-162 A). Only what was recorded: a part the session has nothing for says it
 * was not recorded, never shows zero. On the FR-S also the steering wheel (UI-250 A) and the brake
 * pressure (UI-251 A, FBBD-322), each only when the car sent it.
 */
data class CarUi(
    val rows: List<CarRow>,
    val heartRate: HeartRateChartUi?,
    val steering: SteeringUi? = null,
    val brakePressure: BrakePressureUi? = null,
) {
    /** Whether any row has pedal shares. */
    val hasPedals: Boolean get() = rows.any { it.pedals != null }

    /** The temperatures any row has, in [VehicleChannel.Temperatures] order. */
    val temperatureKinds: List<VehicleChannel>
        get() = VehicleChannel.Temperatures.filter { k -> rows.any { r -> r.temperatures.any { it.kind == k } } }

    /** Whether the session has none of it. */
    val empty: Boolean
        get() = !hasPedals && temperatureKinds.isEmpty() && heartRate == null && steering == null &&
            brakePressure == null
}

/**
 * The steering wheel (UI-250 A): the most lock each way in whole degrees, the seconds over 90° a lap
 * ([perLap]) or over the whole drive, and the best lap's wheel against seconds into it ([trace],
 * positive left; empty for a road drive), with that lap's time.
 */
data class SteeringUi(
    val mostLeft: Int,
    val mostRight: Int,
    val largeLockSeconds: Double,
    val perLap: Boolean,
    val trace: List<List<SamplePoint>>,
    val traceLapMillis: Long?,
)

/**
 * The brake pressure (UI-251 A), in [unit]: the hardest press, the typical stop (null without a
 * stop), and the seconds at half braking or more a lap ([perLap]) or over the whole drive.
 */
data class BrakePressureUi(
    val hardest: Int,
    val typicalStop: Int?,
    val hardSeconds: Double,
    val perLap: Boolean,
    val unit: String,
)

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
    val best = ui.laps.firstOrNull { it.best }?.let { row -> laps.getOrNull(row.index)?.let { it to row.millis } }
    val steering = steeringUi(session, spans, perLap = true)?.let { s ->
        val (lap, millis) = best ?: return@let s
        s.copy(trace = steeringTrace(session, lap.startTelemetryTime, lap.endTelemetryTime), traceLapMillis = millis)
    }
    val brake = brakePressureUi(session, spans, perLap = true)
    return ui.copy(car = CarUi(rows, heartRateChart(session, device, spans), steering, brake))
}

private fun steeringUi(session: TelemetrySession, spans: List<Pair<Double, Double>>, perLap: Boolean): SteeringUi? =
    steeringStats(session, spans)?.let { s ->
        SteeringUi(
            mostLeft = s.mostLeftDegrees.roundToInt(),
            mostRight = s.mostRightDegrees.roundToInt(),
            largeLockSeconds = if (perLap) s.largeLockSeconds / s.stretches else s.largeLockSeconds,
            perLap = perLap,
            trace = emptyList(),
            traceLapMillis = null,
        )
    }

private fun brakePressureUi(session: TelemetrySession, spans: List<Pair<Double, Double>>, perLap: Boolean) =
    brakePressureStats(session, spans)?.let { b ->
        BrakePressureUi(
            hardest = b.hardest.roundToInt(),
            typicalStop = b.typicalStop?.roundToInt(),
            hardSeconds = if (perLap) b.hardSeconds / b.stretches else b.hardSeconds,
            perLap = perLap,
            unit = b.unit,
        )
    }

/** The Car cards of a session without laps (a road drive, UI-162 A); null when it recorded none of it. */
fun wholeDriveCar(session: TelemetrySession, device: String?): CarUi? {
    val whole = sessionVehicleStretch(session)
    val rows = listOfNotNull(whole?.let { CarRow(null, null, false, it.pedals, it.temperatures) })
    val span = session.channels.values.filter { it.sampleCount > 0 }.takeIf { it.isNotEmpty() }
        ?.let { c -> listOf(c.minOf { it.timestamps.first() } to c.maxOf { it.timestamps.last() }) }
        .orEmpty()
    return CarUi(
        rows,
        heartRateChart(session, device, emptyList()),
        steeringUi(session, span, perLap = false),
        brakePressureUi(session, span, perLap = false),
    ).takeUnless { it.empty }
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
