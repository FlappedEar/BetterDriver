package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.track.Track
import kotlinx.serialization.Serializable

/** Slack on [Area.reaches], for the flat-earth nearest point and the rounding of an area's edges. */
private const val AreaMarginMeters = 100.0

/** The box of latitudes and longitudes a session's positions fall in (FBBD-168). */
@Serializable
data class Area(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** Whether [track]'s radius reaches into the box, so a position in it may be at the track. */
    fun reaches(track: Track): Boolean {
        val c = track.center
        val nearest = GeoPoint(c.lat.coerceIn(south, north), c.lon.coerceIn(west, east))
        return distanceMeters(c, nearest) <= track.radiusMeters + AreaMarginMeters
    }

    companion object {
        /** The box of [fixes]; null without a usable position. */
        fun of(fixes: Sequence<CoachFix>): Area? {
            var s = Double.POSITIVE_INFINITY
            var w = Double.POSITIVE_INFINITY
            var n = Double.NEGATIVE_INFINITY
            var e = Double.NEGATIVE_INFINITY
            for (f in fixes) {
                if (runCatching { GeoPoint(f.lat, f.lon) }.isFailure) continue
                s = minOf(s, f.lat)
                w = minOf(w, f.lon)
                n = maxOf(n, f.lat)
                e = maxOf(e, f.lon)
            }
            return if (s.isFinite()) Area(s, w, n, e) else null
        }
    }
}

/**
 * What the coach library knows about one session (FBBD-168). [stamp] says which state of the session
 * it was worked out from. [recorded] is the saved track its track event names, if any. Sessions
 * matched by position have their samples [read] once for their [area] (null: no position); [matched]
 * is the track they time laps on among the tracks near that area, which [against] identifies, so it
 * is worked out again only when one of those tracks is saved, edited or deleted.
 */
@Serializable
data class SessionPlace(
    val stamp: String,
    val recorded: String? = null,
    val read: Boolean = false,
    val area: Area? = null,
    val against: String? = null,
    val matched: String? = null,
)
