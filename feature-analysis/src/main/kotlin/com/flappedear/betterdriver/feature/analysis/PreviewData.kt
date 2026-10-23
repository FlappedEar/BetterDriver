@file:Suppress("MagicNumber") // Made-up sample values.

package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.data.SamplePoint
import com.flappedear.betterdriver.analysis.driving.DrivingState
import com.flappedear.betterdriver.analysis.driving.DrivingStateRun
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.unprojectCoordinate
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.PlanAdvice
import com.flappedear.betterdriver.analysis.line.PlanCorner
import com.flappedear.betterdriver.analysis.line.PlanPass
import com.flappedear.betterdriver.analysis.line.PlanPoint
import com.flappedear.betterdriver.analysis.map.GgPoint
import com.flappedear.betterdriver.analysis.map.GgSource
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.analysis.map.LapMap
import com.flappedear.betterdriver.analysis.map.MapCorner
import com.flappedear.betterdriver.analysis.map.MapPoint
import com.flappedear.betterdriver.analysis.optimal.PedalState
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.summary.SegmentChange
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.analysis.vehicle.PedalShares
import com.flappedear.betterdriver.analysis.vehicle.TemperatureRange
import com.flappedear.betterdriver.analysis.vehicle.VehicleChannel
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.Provenance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val PreviewLength = 1767.0

private fun line(f: (Double) -> Double) = listOf((0..176).map { ChartPoint(it * 10.0, f(it * 10.0)) })

private fun previewTemps(oil: Pair<Double, Double>, coolant: Pair<Double, Double>, gearbox: Pair<Double, Double>?) =
    listOfNotNull(
        TemperatureRange(VehicleChannel.OilTemp, oil.first, oil.second, "°C"),
        TemperatureRange(VehicleChannel.CoolantTemp, coolant.first, coolant.second, "°C"),
        gearbox?.let { TemperatureRange(VehicleChannel.GearboxTemp, it.first, it.second, "°C") },
        TemperatureRange(VehicleChannel.IntakeTemp, 30.0, 36.0, "°C"),
    )

private val PreviewWheel = listOf(
    0, 1, 4, 11, 19, 39, 14, -35, -52, -66, -62, -29, -13, -8, 4, 8, 8, 6, -4, -9, -46, -73, -47, -34, -12, -3,
    4, 12, 13, 10, -28, 14, 47, -47, -89, -86, -97, -105, -78, -31, -18, -15, 6, 6, 10, 32, 66, 140, 161, 64, 8,
    5, 0, -5, -2, -1, -2, -29, -139, -167, -121, -49, -20, -18, 0, 13, 28, 93, 125, 115, 85, 49, -5, -33, -53, -101,
)

/** The Car tab of [PreviewUi]: all four temperatures, the third lap's gearbox not recorded, no pedals on lap 4. */
internal val PreviewCar = CarUi(
    rows = listOf(
        CarRow(
            1, 82_310, false,
            PedalShares(0.33, 0.30, 0.10, 0.27, 80.0),
            previewTemps(105.0 to 115.0, 94.0 to 100.0, 93.0 to 99.0),
        ),
        CarRow(
            2, 81_240, true,
            PedalShares(0.36, 0.30, 0.07, 0.27, 79.0),
            previewTemps(115.0 to 123.0, 98.0 to 100.0, 99.0 to 104.0),
        ),
        CarRow(
            3, 81_870, false,
            PedalShares(0.28, 0.43, 0.08, 0.21, 80.0),
            previewTemps(122.0 to 128.0, 99.0 to 103.0, null),
        ),
        CarRow(
            4, 95_020, false,
            null,
            previewTemps(120.0 to 127.0, 98.0 to 101.0, 104.0 to 110.0),
        ),
    ),
    heartRate = HeartRateChartUi(
        HeartRateUi("Polar H10 1234", 128, 161, 96, 22),
        segments = listOf(
            (0..30).map { SamplePoint(it * 10.0, 110.0 + it) },
            (40..80).map { SamplePoint(it * 10.0, 140.0 + (it % 7) * 3) },
        ),
        laps = listOf(300.0 to 382.0, 382.0 to 463.0, 463.0 to 545.0, 545.0 to 640.0),
        fromSeconds = 0.0,
        toSeconds = 800.0,
    ),
    // The FR-S's wheel and brake pressure (UI-250 A, UI-251 A): the trace at 1 s steps is a Jastrząb
    // lap's wheel worked out from its path, as on the UI review page.
    steering = SteeringUi(
        mostLeft = 183,
        mostRight = 181,
        largeLockSeconds = 11.0,
        perLap = true,
        trace = listOf(PreviewWheel.mapIndexed { i, v -> SamplePoint(i.toDouble(), v.toDouble()) }),
        traceLapMillis = 81_240,
    ),
    brakePressure = BrakePressureUi(hardest = 51, typicalStop = 38, hardSeconds = 9.0, perLap = true, unit = "bar"),
)


/** A made-up session for previews and the UI review page. */
internal val PreviewUi = AnalysisUi(
    title = "Silesia Ring · 11 Oct",
    heartRate = HeartRateUi("Polar H10 1234", 128, 161, 96, 22),
    car = PreviewCar,
    laps = listOf(
        LapRow(0, 1, 82_310, 1.07, best = false, note = "", heartRate = LapHeartRate(134, 152)),
        LapRow(1, 2, 81_240, 0.0, best = true, note = "", heartRate = LapHeartRate(138, 158)),
        LapRow(2, 3, 81_870, 0.63, best = false, note = "", heartRate = LapHeartRate(140, 161)),
        // No heart rate recorded during this lap: its cell stays empty (UI-109).
        LapRow(3, 4, 95_020, null, best = false, note = "GPS gap"),
    ),
    bestLapNumber = 2,
    bestLapMillis = 81_240,
    theoreticalMillis = 80_610,
    gainSeconds = 0.63,
    realisticMillis = 80_900,
    realisticGainSeconds = 0.34,
    realisticLaps = listOf(2, 3),
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
                CornerMetricRow(CoastingLabel, CornerMeasure.Seconds, 0.12, 0.41, 0.18, Provenance.INFERRED),
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
        lapMedianSeconds = 81.74,
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
            primary = true,
            timeSeconds = 0.21,
            what = "Your 2 fastest passes braked 8 m later and carried 4 km/h more minimum speed than your other 3.",
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
            timeSeconds = 0.19,
            what = "Your 2 fastest passes left the corner 3 km/h faster than your other 3.",
        ),
    ),
    coaching = listOf(
        CoachingRow(
            "Corner 4",
            CoachingStatus.Faster,
            "0.20 s back · 0.05 s still to your target · the coach spoke here 5 times",
            doubtful = false,
            cornerId = "s5",
            measured = true,
        ),
        CoachingRow(
            "Corners 2–3",
            CoachingStatus.FasterNoCue,
            "0.07 s back without a cue from the coach",
            doubtful = false,
            cornerId = "s3",
            measured = true,
        ),
        CoachingRow(
            "Corner 1",
            CoachingStatus.Learned,
            "Corner 1, worked on 4 Oct: the gain held. The median corner time is 0.12 s better than before " +
                "the focus and the minimum speed 3 km/h higher.",
            doubtful = false,
            cornerId = "s1",
            attempt = false,
        ),
    ),
    lastFocusWonBackSeconds = 0.27,
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
        referenceStates = previewStates(0.0),
        otherStates = previewStates(18.0),
        referenceCoastingSeconds = 1.32,
        otherCoastingSeconds = 2.05,
        statesProvenance = Provenance.INFERRED,
    ),
)

/** What the car does along the made-up lap: each corner braked into, coasted, cornered and driven out of. */
private fun previewStates(late: Double): List<DrivingStateRun> = buildList {
    var at = 0.0
    fun add(state: DrivingState, to: Double) {
        add(DrivingStateRun(state, at, to))
        at = to
    }
    for ((start, end) in listOf(224.0 to 285.0, 311.0 to 370.0, 900.0 to 1010.0)) {
        if (start - 70.0 > at) add(DrivingState.FullThrottle, start - 70.0)
        add(DrivingState.Braking, start + 5.0 + late / 3)
        add(DrivingState.Coasting, start + 15.0 + late)
        add(DrivingState.Cornering, end - 15.0)
        add(DrivingState.Accelerating, end + 10.0)
    }
    add(DrivingState.FullThrottle, PreviewLength)
}

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

/** A point of the made-up oval at angle [a] (radians), [scale] times its size. */
private fun ovalDot(a: Double, scale: Double, pedal: PedalState? = null) =
    LineDot(200.0 * scale * cos(a), 125.0 * scale * sin(a), pedal)

/** One quarter of the oval as a corner window: braking, coasting, then throttle along it. */
private fun previewLineCorner(k: Int, tag: String, gain: Double, todo: List<String>): LineCornerUi {
    val angles = (0..45).map { PI / 2 * (k + it / 45.0) }
    fun pedal(i: Int) = when {
        i < 12 -> PedalState.Braking
        i < 24 -> PedalState.Coasting
        else -> PedalState.Accelerating
    }
    val reference = angles.mapIndexed { i, a -> ovalDot(a, 1.0, pedal(i)) }
    return LineCornerUi(
        tag = tag,
        gainSeconds = gain,
        todo = todo,
        targetFrom = "this session, lap 4 (1:52.61)",
        startMeters = PreviewLength / 4 * k,
        endMeters = PreviewLength / 4 * (k + 1),
        reference = reference,
        target = angles.map { ovalDot(it, 0.985) },
        roadLeft = angles.map { ovalDot(it, 0.96) },
        roadRight = angles.map { ovalDot(it, 1.03) },
        anchor = reference[reference.size / 2],
    )
}

/** Where to find time in the made-up session (FBBD-306). */
internal val PreviewLine = LineUi(
    bestLapNumber = 2,
    bestLapSeconds = 112.41,
    totalGainSeconds = 0.42,
    laps = 14,
    sessions = 3,
    corners = listOf(
        previewLineCorner(
            0, "T1", 0.11,
            listOf("Brake 5 m earlier", "Apex 2 m tighter", "Carry 2 km/h more through the slowest point"),
        ),
        previewLineCorner(1, "T2–3", 0.0, listOf("Your best lap is as quick as your target here.")),
        previewLineCorner(2, "T4", 0.31, listOf("Apex 8 m later", "Back on the throttle 5 m earlier")),
        previewLineCorner(3, "T5", 0.0, listOf("Your best lap is as quick as your target here.")),
    ),
)

/**
 * A made-up line plan for the perfect lap in 3D (FBBD-308): four corners round an oval at
 * 0° N 0° E (no real place), the target a little inside the best lap and quicker through each.
 */
internal fun previewPerfectLapPlan(): LinePlan {
    val origin = GeoCoordinate(0.0, 0.0)
    val steps = 60
    fun at(a: Double, scale: Double): PlanPoint {
        val g = unprojectCoordinate(PreviewOvalEast * scale * cos(a), PreviewOvalNorth * scale * sin(a), origin)
        return PlanPoint(0.0, g.latitudeDegrees, g.longitudeDegrees)
    }
    fun pedal(i: Int) = when {
        i < steps / 4 -> PedalState.Braking
        i < steps / 2 -> PedalState.Coasting
        else -> PedalState.Accelerating
    }
    val quarter = PreviewOvalLength / 4
    val corners = (0 until 4).map { k ->
        fun line(scale: Double, kmh: Double) = (0..steps).map { i ->
            val a = PI / 2 * (k + i.toDouble() / steps)
            val progress = quarter * k + quarter * i / steps
            at(a, scale).copy(progressMeters = progress, speedKmh = kmh + 20 * sin(PI * i / steps), pedal = pedal(i))
        }
        val gain = listOf(0.11, 0.0, 0.31, 0.0)[k]
        fun pass(scale: Double, kmh: Double, seconds: Double) =
            PlanPass("s1", "", 2, 112.41, seconds, line = line(scale, kmh))
        PlanCorner(
            cornerId = null, name = "Corner ${k + 1}", turnsLeft = true,
            startMeters = quarter * k, endMeters = quarter * (k + 1), gainSeconds = gain,
            reference = pass(1.0, 80.0, 112.41 / 4), target = pass(0.985, 82.0, 112.41 / 4 - gain),
            advice = PlanAdvice(), roadLeft = line(0.96, 0.0), roadRight = line(1.03, 0.0),
        )
    }
    return LinePlan(
        trackId = "preview", sessionId = "s1", sessionStartUtcMillis = 0L, referenceLapNumber = 2,
        referenceLapSeconds = 112.41, laps = 14, sessions = 3, corners = corners,
    )
}

private const val PreviewOvalEast = 320.0
private const val PreviewOvalNorth = 180.0
private const val PreviewOvalLength = 1600.0
