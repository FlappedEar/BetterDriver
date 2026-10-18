package com.flappedear.betterdriver.timing.demo

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.fake.GnssScript
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.track.Track
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A recorded session driven again by the Demo track receiver (FBBD-233), so the coach can be tried
 * on a real outing, Arek's Silesia Ring laps for example, without a track day. The session is read
 * on the phone; nothing of it is in the app.
 *
 * At its [track], only its timed laps are replayed, one after another and then again: each joins the
 * next at the line, so the car laps on and on without out laps, in laps, pit stops or laps half again
 * slower than the median. Without a track,
 * or with fewer than two timed laps, the whole session is replayed in a loop. A point-to-point track's
 * runs are not replayed: they start and finish at different places, so they cannot loop.
 * Fixes come at [rateHz] whatever the recording's rate, in between its points; heading follows the
 * path, speed is the recorded one or, without it, the path's.
 */
class SessionReplay private constructor(
    private val points: List<Point>,
    override val startUtcMillis: Long,
    override val rateHz: Int,
) : GnssScript {
    private class Point(val t: Double, val lat: Double, val lon: Double, val kmh: Double?, val alt: Double?)

    private val period = points.last().t - points.first().t

    override fun fix(n: Long): List<ChannelValue> {
        val at = points.first().t + (n.toDouble() / rateHz) % period
        var i = points.binarySearchBy(at) { it.t }.let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 2)
        while (i < points.size - 2 && points[i + 1].t <= points[i].t) i++
        val a = points[i]
        val b = points[i + 1]
        val f = if (b.t > a.t) ((at - a.t) / (b.t - a.t)).coerceIn(0.0, 1.0) else 0.0
        val lat = a.lat + (b.lat - a.lat) * f
        val lon = a.lon + (b.lon - a.lon) * f
        val pathKmh = if (b.t > a.t) distanceMeters(a.geo, b.geo) / (b.t - a.t) * KmhPerMs else 0.0
        val kmh = if (a.kmh != null && b.kmh != null) a.kmh + (b.kmh - a.kmh) * f else pathKmh
        return listOfNotNull(
            ChannelValue(GnssChannels.UTC_MS.id, utcMillis(n).toDouble()),
            ChannelValue(GnssChannels.LAT.id, lat),
            ChannelValue(GnssChannels.LON.id, lon),
            ChannelValue(GnssChannels.SPEED_KMH.id, kmh),
            ChannelValue(GnssChannels.HEADING.id, heading(a, b)),
            (a.alt ?: b.alt)?.let { ChannelValue(GnssChannels.ALT_M.id, it) },
            ChannelValue(GnssChannels.SATS.id, Satellites),
        )
    }

    private val Point.geo get() = GeoPoint(lat, lon)

    /** Degrees clockwise from north, from [a] to [b]. */
    private fun heading(a: Point, b: Point): Double {
        val dLon = Math.toRadians(b.lon - a.lon)
        val la = Math.toRadians(a.lat)
        val lb = Math.toRadians(b.lat)
        val y = sin(dLon) * cos(lb)
        val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + FullCircle) % FullCircle
    }

    companion object {
        private const val KmhPerMs = 3.6
        private const val FullCircle = 360.0
        private const val Satellites = 16.0
        private const val MicrosPerSecond = 1_000_000.0

        /** A lap slower than this times the median lap had a stop or a spin in it. */
        private const val SlowLap = 1.5

        /**
         * The replay of a session's [sets], laps cut at [track]'s line when it times two or more; null
         * when the session has fewer than two positions or lasts no time.
         */
        fun of(sets: List<SampleSet>, track: Track?, startUtcMillis: Long, rateHz: Int = 25): SessionReplay? {
            // A run from start to a separate finish cannot loop: the car would jump back to the start.
            if (track?.pointToPoint == true) return null
            val all = sets.sortedBy { it.tMicros }.mapNotNull(::point)
            val fixes = all.map { Fix((it.t * MicrosPerSecond).toLong(), it.lat, it.lon) }
            val laps = track?.takeIf { it.timeable }?.let { timeSession(it, fixes.asSequence()).laps }.orEmpty()
            // A lap with a stop in it is timed too when the track has no pit lines: leave out slow ones.
            val timed = laps.filter { it.kind == LapKind.Timed }.let { all ->
                val median = all.map { it.timeMillis }.sorted().getOrNull(all.size / 2) ?: 0L
                all.filter { it.timeMillis <= median * SlowLap }
            }
            val points = if (timed.size >= 2) {
                joined(timed.map { cut(all, it.startMicros / MicrosPerSecond, it.endMicros / MicrosPerSecond) })
            } else {
                all
            }
            val lasts = points.size >= 2 && points.last().t > points.first().t
            return if (lasts) SessionReplay(points, startUtcMillis, rateHz) else null
        }

        /**
         * The timed laps one after another, each starting where the one before ends: out and in laps and
         * pit stops between them are left out. Every lap starts and ends on the line, so they join there.
         */
        private fun joined(laps: List<List<Point>>): List<Point> {
            var at = 0.0
            return laps.filter { it.size >= 2 }.flatMap { lap ->
                val from = lap.first().t
                lap.map { Point(at + it.t - from, it.lat, it.lon, it.kmh, it.alt) }.also { at += lap.last().t - from }
            }
        }

        private fun point(set: SampleSet): Point? {
            var lat: Double? = null
            var lon: Double? = null
            var kmh: Double? = null
            var alt: Double? = null
            for (v in set.values) {
                when (v.channelId) {
                    GnssChannels.LAT.id -> lat = v.value
                    GnssChannels.LON.id -> lon = v.value
                    GnssChannels.SPEED_KMH.id -> kmh = v.value
                    GnssChannels.ALT_M.id -> alt = v.value
                }
            }
            val la = lat?.takeIf { it.isFinite() }
            val lo = lon?.takeIf { it.isFinite() }
            if (la == null || lo == null) return null
            return Point(set.tMicros / MicrosPerSecond, la, lo, kmh?.takeIf { it.isFinite() }, alt)
        }

        /** [all] from [from] to [to] seconds, with points at both ends placed on the path. */
        private fun cut(all: List<Point>, from: Double, to: Double): List<Point> {
            val inside = all.filter { it.t > from && it.t < to }
            return listOfNotNull(at(all, from)) + inside + listOfNotNull(at(all, to))
        }

        private fun at(all: List<Point>, t: Double): Point? {
            val j = all.indexOfFirst { it.t >= t }
            if (j <= 0) return all.getOrNull(j)
            val a = all[j - 1]
            val b = all[j]
            val f = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 0.0
            val kmh = if (a.kmh != null && b.kmh != null) a.kmh + (b.kmh - a.kmh) * f else null
            return Point(t, a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, kmh, a.alt)
        }
    }
}
