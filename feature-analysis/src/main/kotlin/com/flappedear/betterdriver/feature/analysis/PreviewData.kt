@file:Suppress("MagicNumber") // Made-up sample values.

package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.map.GgPoint
import com.flappedear.betterdriver.analysis.map.GgSource
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.analysis.map.LapMap
import com.flappedear.betterdriver.analysis.map.MapCorner
import com.flappedear.betterdriver.analysis.map.MapPoint
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.summary.SegmentChange
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.Provenance
import kotlin.math.PI
import kotlin.math.cos
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
    gains = listOf(
        GainRow("Corners 2–3", 0.21, 1, "s2"),
        GainRow("Corner 4", 0.12, 3, "s5", ConfidenceUi(Confidence.MEDIUM, "within the usual spread of 0.14 s")),
    ),
    withheld = listOf(WithheldRow("Corner 1", "seen on one lap only")),
    losses = listOf(
        LossRow(0, 1, "Corner 4", 0.44, "s5"),
        LossRow(2, 3, "Corners 2–3", 0.28, "s2"),
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
                CornerMetricRow("Throttle", CornerMeasure.Meters, 31.0, 38.5, 5.2, Provenance.INFERRED),
            ),
            speedUnit = "km/h",
            lineWithinGps = false,
            lineSpreadMeters = 1.4,
        ),
    ),
    progress = SessionProgress(
        earlierSessions = 3,
        eligibleLapCount = 6,
        bestLapSeconds = 81.21,
        earlierBestSeconds = 81.62,
        earlierBestLabel = "11 Oct 2026, 10:05",
        lapSpreadSeconds = 0.42,
        previousLabel = "11 Oct 2026, 11:40",
        previousBestSeconds = 81.62,
        previousSpreadSeconds = 0.71,
        changes = listOf(
            SegmentChange("s2", "Corners 2–3", TrackSegmentType.Corner, 3.61, 3.83, 0.06, 0.11),
            SegmentChange("s5", "Corner 4", TrackSegmentType.Corner, 5.27, 5.18, 0.09, 0.05),
        ),
    ),
    focus = listOf(
        FocusRow(
            "Best lap gap",
            "Corners 2–3",
            "Your best lap (lap 2) was 0.210 s slower through Corners 2–3 than lap 1, the fastest recorded there.",
            "Comparing the two laps through Corners 2–3 may show where the time went: where braking starts, " +
                "the lowest speed, and when the throttle comes back.",
            0,
            1,
            "s2",
        ),
        FocusRow(
            "Repeated loss",
            "Corner 4",
            "In 3 of 4 compared laps you lost time through Corner 4 against lap 2 (median 0.190 s).",
            "Because it repeats, comparing a typical lap with lap 2 through Corner 4 may show a pattern " +
                "rather than a one-off.",
            2,
            3,
            "s5",
        ),
    ),
    coachPickable = setOf("s2", "s5"),
    coachPicked = setOf("s5"),
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

/** A made-up lap on an oval for the Map tab's previews: 400 by 250 m, anticlockwise. */
private fun previewOval(steps: Int = 360): List<MapPoint> = (0..steps).map {
    val a = 2 * PI * it / steps
    val d = PreviewLength * it / steps
    MapPoint(
        eastMeters = 200 * cos(a),
        northMeters = 125 * sin(a),
        distanceMeters = d,
        speed = 120 + 45 * cos(2 * a),
        deltaSeconds = d / PreviewLength * 0.63,
        deltaRate = 0.04 + 0.12 * sin(3 * a),
    )
}

private fun previewGg(scale: Double) = GgTrace(
    GgSource.Gps,
    (0..600).map {
        val a = 2 * PI * it / 600
        GgPoint(scale * sin(a) * 1.0, scale * cos(3 * a) * 0.8, PreviewLength * it / 600)
    },
)

/** The Map tab of the made-up session. */
internal val PreviewMap = previewOval().let { line ->
    MapUi(
        lapNumber = 3,
        referenceNumber = 2,
        map = LapMap(
            lapIndex = 2,
            referenceIndex = 1,
            lengthMeters = PreviewLength,
            runs = listOf(line),
            outline = line.map { MetricPoint(it.eastMeters, it.northMeters) },
            corners = listOf(
                MapCorner("Corner 1", 440.0, MetricPoint(0.0, 125.0), MetricPoint(0.0, 1.0)),
                MapCorner("Corners 2–3", 880.0, MetricPoint(-200.0, 0.0), MetricPoint(-1.0, 0.0)),
                MapCorner("Corner 4", 1320.0, MetricPoint(0.0, -125.0), MetricPoint(0.0, -1.0)),
            ),
        ),
        gg = previewGg(0.95),
        referenceGg = previewGg(1.05),
    )
}
