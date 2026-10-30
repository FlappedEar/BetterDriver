package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.map.LapMap
import com.flappedear.betterdriver.analysis.map.MapCorner
import com.flappedear.betterdriver.analysis.map.MapPoint
import com.flappedear.betterdriver.format.fixed
import kotlin.math.abs

// The Map tab's Laps view (FBBD-149) as both apps draw it: the line's colour scale, its coloured
// stretches and the words around it. Colours are ARGB ints; each app passes its theme's.

/** The colour scale the line is drawn with, for the legend and the strokes. */
class MapScale(val coloring: MapColoring, val speed: ScaleRange?, val gainLossLimit: Double)

/** The scale of [map] in [coloring]: speeds widened to tens, time vs best to most of its rates. */
fun mapScale(map: LapMap, coloring: MapColoring): MapScale {
    val points = map.runs.flatten()
    return MapScale(
        coloring,
        speedRange(points.mapNotNull { it.speed }),
        gainLossLimit(points.mapNotNull { it.deltaRate }),
    )
}

/** The colour of [p] on [scale]; [neutral] when the point has no value to colour. */
fun pointColor(p: MapPoint, scale: MapScale, gain: Int, neutral: Int, loss: Int): Int {
    val argb = when (scale.coloring) {
        MapColoring.Speed -> {
            val range = scale.speed
            val speed = p.speed
            if (range == null || speed == null) null else scaleColor(range.fraction(speed))
        }
        MapColoring.Delta -> p.deltaRate?.let { gainLossColor(it, scale.gainLossLimit, gain, neutral, loss) }
    }
    return argb ?: neutral
}

/** One coloured stretch of the lap line, in metres. */
class MapStroke(val from: MapPoint, val to: MapPoint, val argb: Int)

/** The lap line of [map] as coloured stretches on [scale], each the colour of its middle. */
fun mapStrokes(map: LapMap, scale: MapScale, gain: Int, neutral: Int, loss: Int): List<MapStroke> =
    map.runs.flatMap { run ->
        run.zipWithNext { a, b ->
            val middle = MapPoint(
                a.eastMeters, a.northMeters, a.distanceMeters,
                average(a.speed, b.speed), null, average(a.deltaRate, b.deltaRate),
            )
            MapStroke(a, b, pointColor(middle, scale, gain, neutral, loss))
        }
    }

private fun average(a: Double?, b: Double?) = if (a != null && b != null) (a + b) / 2 else a ?: b

/** A corner is named in the readout within this distance of its middle. */
private const val CornerReadoutMeters = 40.0

/** The corner of [map] the [cursor] is in, for the readout; null away from every corner. */
fun cursorCorner(map: LapMap, cursor: MapPoint): MapCorner? =
    map.corners.minByOrNull { abs(it.distanceMeters - cursor.distanceMeters) }
        ?.takeIf { abs(it.distanceMeters - cursor.distanceMeters) <= CornerReadoutMeters }

/** The point of [map]'s line nearest to [distanceMeters] along the lap; null without one. */
fun pointAtDistance(map: LapMap, distanceMeters: Double): MapPoint? =
    map.runs.flatten().minByOrNull { abs(it.distanceMeters - distanceMeters) }

/** "Gaining 0.12 s per 100 m here", or losing, for a rate in seconds per 100 m (negative gains). */
fun deltaRateText(rate: Double): String {
    val amount = "${fixed(abs(rate), 2)} s per 100 m"
    return if (rate < 0) "Gaining $amount here" else "Losing $amount here"
}

/** The time vs best scale's end, "0.20", as its legend writes it. */
fun gainLossLimitText(scale: MapScale): String = fixed(scale.gainLossLimit, 2)

/** Said under the map before a point is read. */
const val MapCursorHint = "Touch or drag along the line to read a point. The ringed dot is the start/finish line."

/** Said under the map of the best lap. */
const val MapBestLapNote = "This is the best lap, so there is no time to compare. Pick another lap on Laps."

/** Said under the map coloured by time vs best. */
const val MapDeltaNote = "Time vs best: where this lap gains or loses time against the best lap, per 100 m."
