package com.flappedear.betterdriver.recording

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.PlanCorner
import com.flappedear.betterdriver.analysis.line.PlanPoint
import com.flappedear.betterdriver.analysis.optimal.PedalState
import com.flappedear.betterdriver.coach.linePlanStore
import com.flappedear.betterdriver.feature.track.MapPedal
import com.flappedear.betterdriver.feature.track.MapPoint
import com.flappedear.betterdriver.feature.track.NextCorner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Further than this from the plan's line the car is off the track: the pits, the paddock. */
private const val OffTrackMeters = 50.0

/**
 * Finds the corner the map screen shows (UI-171 B): the next one, never the one just driven. The
 * car is placed on the plan's target line by its nearest point; it shows that point's corner until
 * the car is past where the target goes back on throttle (or its apex, or the corner's end), then
 * the next. Off the line it keeps the corner it showed, without the car. The corners of [plan] tile
 * the lap, so the next after the last is the first. Built once a session; [at] is cheap enough for
 * every screen update (a few thousand points).
 */
class NextCornerFinder(plan: LinePlan) {
    private val corners = plan.corners.filter { it.target.line.isNotEmpty() }
    private val origin = corners.firstOrNull()?.target?.line?.first()
        ?.let { GeoCoordinate(it.latitude, it.longitude) }
    private val views = corners.map(::view)

    /** Every target point in lap order: its corner, its progress and where it is. */
    private val points = corners.flatMapIndexed { i, c ->
        c.target.line.map { Located(i, it.progressMeters, metric(it)) }
    }
    private var shown: Int? = null

    /** The plan has a corner with a target line to draw. */
    val drawable: Boolean get() = points.isNotEmpty() && origin != null

    /** The corner to show with the car at [latitude], [longitude]; null before the car was ever near the line. */
    fun at(latitude: Double?, longitude: Double?): NextCorner? {
        val car = if (latitude != null && longitude != null && drawable) metric(latitude, longitude) else null
        val near = car?.let { c -> points.minBy { distance2(it.at, c) } }
            ?.takeIf { distance2(it.at, car) <= OffTrack2 }
        if (near != null) {
            val target = corners[near.corner].target
            val leave = target.throttle?.progressMeters ?: target.apex?.progressMeters ?: corners[near.corner].endMeters
            shown = if (near.progress >= leave) (near.corner + 1) % corners.size else near.corner
        }
        return shown?.let { views[it].copy(car = car?.takeIf { near != null }) }
    }

    private fun view(c: PlanCorner) = NextCorner(
        label = label(c.name),
        left = c.roadLeft.map(::metric),
        right = c.roadRight.map(::metric),
        line = c.target.line.map(::metric),
        pedals = c.target.line.map { p -> p.pedal?.let(::pedal) },
        car = null,
    )

    private fun metric(p: PlanPoint) = metric(p.latitude, p.longitude)

    private fun metric(latitude: Double, longitude: Double): MapPoint {
        val m = projectCoordinate(GeoCoordinate(latitude, longitude), origin ?: GeoCoordinate(latitude, longitude))
        return MapPoint(m.eastMeters.toFloat(), m.northMeters.toFloat())
    }

    private class Located(val corner: Int, val progress: Double, val at: MapPoint)

    private companion object {
        const val OffTrack2 = OffTrackMeters * OffTrackMeters

        fun distance2(a: MapPoint, b: MapPoint): Double {
            val e = (a.east - b.east).toDouble()
            val n = (a.north - b.north).toDouble()
            return e * e + n * n
        }

        fun pedal(p: PedalState) = when (p) {
            PedalState.Braking -> MapPedal.Brake
            PedalState.Coasting -> MapPedal.Coast
            PedalState.Accelerating -> MapPedal.Throttle
        }

        /** "Corner 6" as the coach line shows it, "T6", "Corners 3–4" "T3–4"; a name without a number as it is. */
        fun label(name: String): String = Regex("\\d+(\u2013\\d+)?").find(name)?.let { "T${it.value}" } ?: name
    }
}

/**
 * The map screen's corners at [trackId] (UI-171 B), from its line plan read off the main thread;
 * null without a saved track or a plan there, and then the track screen has no map screen.
 */
@Composable
fun rememberNextCorners(trackId: String?): NextCornerFinder? {
    val context = LocalContext.current
    val finder by produceState<NextCornerFinder?>(null, trackId) {
        value = trackId?.let { id ->
            withContext(Dispatchers.IO) {
                linePlanStore(context).get(id)?.let(::NextCornerFinder)?.takeIf { it.drawable }
            }
        }
    }
    return finder
}
