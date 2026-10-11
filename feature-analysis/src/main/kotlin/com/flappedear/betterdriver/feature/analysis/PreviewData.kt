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
            lapSeconds = 3.52, lossSeconds = 0.13,
        ),
        SectorRow(
            "Straight 1", corner = false, bestSeconds = 1.33, bestLapNumber = 3,
            lapSeconds = 1.34, lossSeconds = 0.01,
        ),
        SectorRow(
            "Corners 2–3", corner = true, bestSeconds = 3.53, bestLapNumber = 1,
            lapSeconds = 3.81, lossSeconds = 0.28,
        ),
        SectorRow(
            "Straight 2", corner = false, bestSeconds = 3.51, bestLapNumber = 2,
            lapSeconds = 3.52, lossSeconds = 0.01,
        ),
    ),
    sectorsNote = null,
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
