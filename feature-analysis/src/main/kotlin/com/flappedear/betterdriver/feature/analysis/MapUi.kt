@file:Suppress("TooManyFunctions") // The Map tab's pure parts, together so they are tested together.

package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.compare.LapComparison
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.map.GgPoint
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.analysis.map.LapMap
import com.flappedear.betterdriver.analysis.map.lapGg
import com.flappedear.betterdriver.analysis.map.lapMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Pure Kotlin (no Android): the Map tab's model, colour scales and geometry (FBBD-149), tested on the JVM.

/** The Map tab: one lap on the map and its G-G diagram, with the best lap's for comparison. */
data class MapUi(
    val lapNumber: Int,
    /** The lap the time deltas are against (the best lap), or null when the map shows the best lap. */
    val referenceNumber: Int?,
    val map: LapMap,
    val gg: GgTrace?,
    /** The best lap's G-G trace, drawn under [gg]; null when the map shows the best lap. */
    val referenceGg: GgTrace?,
)

/**
 * The Map tab of [analysis]: the compared lap of [comparison] (else the fastest lap) on the map,
 * coloured against the fastest lap, and both laps' G-G traces. Null without an axis or positions.
 * Reads every sample of two laps, so it runs off the main thread.
 */
@Suppress("ReturnCount")
fun mapUi(analysis: SessionAnalysis, session: TelemetrySession, comparison: LapComparison?): MapUi? {
    val fastest = analysis.laps.fastestLapIndex ?: return null
    val shown = comparison?.otherIndex ?: fastest
    val map = lapMap(analysis, session, shown, comparison) ?: return null
    val laps = analysis.laps.timedLaps
    val against = map.referenceIndex
    return MapUi(
        lapNumber = laps[shown].number,
        referenceNumber = against?.let { laps[it].number },
        map = map,
        gg = lapGg(analysis, session, shown),
        referenceGg = if (shown != fastest) lapGg(analysis, session, fastest) else null,
    )
}

/** How the lap line is coloured. */
enum class MapColoring(val title: String) {
    Speed("Speed"),
    Delta("Time vs best"),
}

/**
 * Sequential speed scale, slow to fast: viridis from its blue end (its darkest purples vanish on the
 * dark track theme). Perceptually even and readable with every common colour-vision deficiency.
 */
val SpeedScaleColors = listOf(
    0xFF3B528B.toInt(),
    0xFF2C728E.toInt(),
    0xFF21908C.toInt(),
    0xFF27AD81.toInt(),
    0xFF5DC863.toInt(),
    0xFFAADC32.toInt(),
    0xFFFDE725.toInt(),
)

private const val ChannelMask = 0xFF
private const val AlphaShift = 24
private const val RedShift = 16
private const val GreenShift = 8

/** [a] to [b] by [t] (0 to 1), channel by channel, alpha included. */
fun lerpColor(a: Int, b: Int, t: Double): Int {
    val f = t.coerceIn(0.0, 1.0)
    fun channel(shift: Int): Int {
        val x = (a shr shift) and ChannelMask
        val y = (b shr shift) and ChannelMask
        return (x + (y - x) * f).roundToInt().coerceIn(0, ChannelMask) shl shift
    }
    return channel(AlphaShift) or channel(RedShift) or channel(GreenShift) or channel(0)
}

/** The colour of [fraction] (0 slow to 1 fast) on [stops], interpolated between them. */
fun scaleColor(fraction: Double, stops: List<Int> = SpeedScaleColors): Int {
    if (stops.size == 1 || fraction.isNaN()) return stops.first()
    val position = fraction.coerceIn(0.0, 1.0) * (stops.size - 1)
    val i = min(floor(position).toInt(), stops.size - 2)
    return lerpColor(stops[i], stops[i + 1], position - i)
}

/**
 * Time gained or lost per distance as a colour: [gain] at -[limit] and beyond, [neutral] at 0,
 * [loss] at +[limit] and beyond. The theme's gain and loss colours, so the colour-blind theme's
 * blue and orange apply here too.
 */
fun gainLossColor(rate: Double, limit: Double, gain: Int, neutral: Int, loss: Int): Int {
    if (!(limit > 0.0) || rate.isNaN()) return neutral
    val f = (rate / limit).coerceIn(-1.0, 1.0)
    return if (f < 0) lerpColor(neutral, gain, -f) else lerpColor(neutral, loss, f)
}

/** Where a value sits on a scale with no span. */
private const val Middle = 0.5

/** A colour scale's span, as its legend labels it. */
data class ScaleRange(val low: Double, val high: Double) {
    /** Where [value] sits on the scale, 0 to 1. */
    fun fraction(value: Double): Double = if (high > low) ((value - low) / (high - low)).coerceIn(0.0, 1.0) else Middle
}

/** Speeds are labelled in steps of this. */
private const val SpeedStep = 10.0

/** The speed scale of [speeds], widened to whole tens; null without any speed. */
fun speedRange(speeds: List<Double>): ScaleRange? {
    val finite = speeds.filter { it.isFinite() }
    if (finite.isEmpty()) return null
    val low = floor(finite.min() / SpeedStep) * SpeedStep
    val high = max(low + SpeedStep, ceil(finite.max() / SpeedStep) * SpeedStep)
    return ScaleRange(low, high)
}

/** Steps the gain/loss scale's end is rounded up to, seconds per 100 m. */
private val GainLossSteps = listOf(0.05, 0.1, 0.2, 0.3, 0.5, 1.0, 2.0)

/** Share of the points whose rate is within the gain/loss scale; the rest show at full colour. */
private const val GainLossCoverage = 0.9

/**
 * The end of the gain/loss scale for [rates] (seconds per 100 m): the size most of them stay
 * within, rounded up to a step, so a single spike does not wash the rest of the lap out.
 */
fun gainLossLimit(rates: List<Double>): Double {
    val sizes = rates.filter { it.isFinite() }.map(::abs).sorted()
    if (sizes.isEmpty()) return GainLossSteps.first()
    val typical = sizes[min(sizes.size - 1, (sizes.size * GainLossCoverage).toInt())]
    return GainLossSteps.firstOrNull { it >= typical } ?: GainLossSteps.last()
}

/** Pixels per metre at the least, so a map with no room still inverts. */
private const val MinimumScale = 1e-6

/**
 * Fits a map's [east] and [north] extent (metres) into [width] by [height] pixels, less [padding]
 * on every side, north up and one scale on both axes, centred.
 */
class MapFrame(
    east: ClosedFloatingPointRange<Double>,
    north: ClosedFloatingPointRange<Double>,
    width: Float,
    height: Float,
    padding: Float,
) {
    /** Pixels per metre. */
    val scale: Double
    private val offsetX: Double
    private val offsetY: Double

    init {
        val spanEast = max(east.endInclusive - east.start, 1.0)
        val spanNorth = max(north.endInclusive - north.start, 1.0)
        scale = max(min((width - 2 * padding) / spanEast, (height - 2 * padding) / spanNorth), MinimumScale)
        offsetX = (width - spanEast * scale) / 2 - east.start * scale
        offsetY = (height - spanNorth * scale) / 2 + north.endInclusive * scale
    }

    fun x(eastMeters: Double): Float = (eastMeters * scale + offsetX).toFloat()

    fun y(northMeters: Double): Float = (offsetY - northMeters * scale).toFloat()

    fun east(x: Float): Double = (x - offsetX) / scale

    fun north(y: Float): Double = (offsetY - y) / scale
}

/** The east and north extent of [map]'s outline and line, in metres. */
fun mapExtent(map: LapMap): Pair<ClosedFloatingPointRange<Double>, ClosedFloatingPointRange<Double>> {
    val east = map.outline.map { it.eastMeters } + map.runs.flatten().map { it.eastMeters }
    val north = map.outline.map { it.northMeters } + map.runs.flatten().map { it.northMeters }
    if (east.isEmpty()) return 0.0..1.0 to 0.0..1.0
    return east.min()..east.max() to north.min()..north.max()
}

/** The map is never taller than this share of its width, nor wider than [MaxMapAspect] times its height. */
private const val MinMapAspect = 0.8
private const val MaxMapAspect = 1.8

/** Width over height for drawing [map]: its own shape, kept between 0.8 and 1.8. */
fun mapAspect(map: LapMap): Float {
    val (east, north) = mapExtent(map)
    val span = max(north.endInclusive - north.start, 1.0)
    return ((east.endInclusive - east.start) / span).coerceIn(MinMapAspect, MaxMapAspect).toFloat()
}

/** Rings of the G-G diagram are this many g apart. */
const val GgRingStep = 0.5

/** The rings of a G-G diagram whose largest reading is [maxG]: every 0.5 g to just past it, at least 1 g. */
fun ggRings(maxG: Double): List<Double> {
    val outer = max(2, ceil(maxG.coerceIn(0.0, MaxGgRingG) / GgRingStep).toInt())
    return (1..outer).map { it * GgRingStep }
}

/** Readings beyond this are glitches and do not grow the diagram. */
private const val MaxGgRingG = 3.0

/** The largest combined acceleration of [traces], in g. */
fun maxG(vararg traces: GgTrace?): Double =
    traces.filterNotNull().flatMap { it.points }.maxOfOrNull { hypot(it.lateralG, it.longitudinalG) } ?: 0.0

/** The reading of [trace] nearest to [distanceMeters] along the lap, or null without one within [withinMeters]. */
fun ggPointAt(trace: GgTrace?, distanceMeters: Double, withinMeters: Double = GgCursorMeters): GgPoint? =
    trace?.points
        ?.filter { it.distanceMeters != null && abs(it.distanceMeters!! - distanceMeters) <= withinMeters }
        ?.minByOrNull { abs(it.distanceMeters!! - distanceMeters) }

/** The G-G cursor shows a reading only this close to the map cursor. */
const val GgCursorMeters = 10.0

/** The largest braking, cornering and accelerating readings of a lap, g. */
data class GgPeaks(val braking: Double, val cornering: Double, val accelerating: Double)

/** The peaks of [trace]: hardest braking, largest sideways and hardest speeding up, each at least 0. */
fun ggPeaks(trace: GgTrace): GgPeaks = GgPeaks(
    braking = trace.points.maxOfOrNull { -it.longitudinalG }?.coerceAtLeast(0.0) ?: 0.0,
    cornering = trace.points.maxOfOrNull { abs(it.lateralG) } ?: 0.0,
    accelerating = trace.points.maxOfOrNull { it.longitudinalG }?.coerceAtLeast(0.0) ?: 0.0,
)
