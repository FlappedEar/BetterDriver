@file:Suppress("MagicNumber") // Made-up sample values.

package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.compare.ChartPoint
import kotlin.math.PI
import kotlin.math.sin

private const val PreviewLength = 1767.0

private fun line(f: (Double) -> Double) = listOf((0..176).map { ChartPoint(it * 10.0, f(it * 10.0)) })

/** A made-up session for previews and the UI review page. */
internal val PreviewUi = AnalysisUi(
    title = "Silesia Ring · 11 Oct",
    laps = listOf(
        LapRow(0, 1, 82_310, 1.07, best = false, note = ""),
        LapRow(1, 2, 81_240, 0.0, best = true, note = ""),
        LapRow(2, 3, 81_870, 0.63, best = false, note = ""),
        LapRow(3, 4, 95_020, null, best = false, note = "GPS gap"),
    ),
    bestLapNumber = 2,
    bestLapMillis = 81_240,
    theoreticalMillis = 80_610,
    gainSeconds = 0.63,
    sectors = listOf(
        SectorRow(
            "Corner 1", corner = true, bestSeconds = 3.39, bestLapNumber = 2,
            lapSeconds = 3.52, lossSeconds = 0.13, spreadSeconds = 0.09,
        ),
        SectorRow(
            "Straight 1", corner = false, bestSeconds = 1.33, bestLapNumber = 3,
            lapSeconds = 1.34, lossSeconds = 0.01, spreadSeconds = 0.02,
        ),
        SectorRow(
            "Corners 2–3", corner = true, bestSeconds = 3.53, bestLapNumber = 1,
            lapSeconds = 3.81, lossSeconds = 0.28, spreadSeconds = 0.17,
        ),
        SectorRow(
            "Straight 2", corner = false, bestSeconds = 3.51, bestLapNumber = 2,
            lapSeconds = 3.52, lossSeconds = 0.01, spreadSeconds = 0.04,
        ),
    ),
    sectorsNote = null,
    gains = listOf(GainRow("Corners 2–3", 0.21, 1), GainRow("Corner 4", 0.12, 3)),
    losses = listOf(
        LossRow(0, 1, "Corner 4", 0.44),
        LossRow(2, 3, "Corners 2–3", 0.28),
        LossRow(0, 1, "Straight 3", 0.19),
    ),
    corners = listOf(
        CornerDetail(
            "Corner 1",
            listOf(
                CornerMetricRow("Brakes", CornerMeasure.Meters, 64.8, 58.2, 6.1),
                CornerMetricRow("Peak braking", CornerMeasure.G, 0.83, 0.79, null),
                CornerMetricRow("Min speed", CornerMeasure.Speed, 63.9, 62.4, 1.8),
                CornerMetricRow("Exit speed", CornerMeasure.Speed, 67.0, 66.1, 1.5),
                CornerMetricRow("Throttle", CornerMeasure.Meters, 31.0, 38.5, 5.2, inferred = true),
            ),
            speedUnit = "km/h",
            lineWithinGps = false,
            lineSpreadMeters = 1.4,
        ),
    ),
    compare = CompareUi(
        referenceNumber = 2,
        otherNumber = 3,
        lengthMeters = PreviewLength,
        delta = line { it / PreviewLength * 0.63 + 0.12 * sin(it / 180.0) },
        referenceSpeed = line { 120 + 50 * sin(it / PreviewLength * 6 * PI) },
        otherSpeed = line { 117 + 50 * sin(it / PreviewLength * 6 * PI - 0.1) },
        corners = listOf(
            CornerBand("Corner 1", 224.0, 285.0),
            CornerBand("Corners 2–3", 311.0, 370.0),
            CornerBand("Corner 4", 900.0, 1010.0),
        ),
    ),
)
