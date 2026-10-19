package com.flappedear.betterdriver.timing.demo

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.fake.GnssScript
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A recorded session driven again by the Demo track receiver (FBBD-233), so the coach can be tried
 * on a real outing, Arek's Silesia Ring laps for example, without a track day. The session is read
 * on the phone, or a replay built into the app is read back with [read] (FBBD-237).
 *
 * At its [track], only its timed laps are replayed, one after another and then again: each joins the
 * next at the line, so the car laps on and on without out laps, in laps, pit stops, laps in which
 * the car stands still or laps half again slower than the median. A session without a saved track
 * or two such laps, or at a point-to-point track, is not replayed: it would jump from where it
 * ended back to where it began on every loop.
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

    /**
     * The joined laps as text, what [read] takes back: a format line, then one point per line,
     * "seconds lat lon km/h altitude" with "-" for a speed or altitude the recording lacks. Only
     * the timed laps travel: no out lap, in lap, pit stop or date.
     */
    fun write(): String = buildString {
        append(FormatLine).append('\n')
        for (p in points) {
            append("%.3f %.8f %.8f %s %s\n".format(Locale.ROOT, p.t, p.lat, p.lon, p.kmh.text(2), p.alt.text(1)))
        }
    }

    /**
     * Whether [track] times this replay: two or more timed laps in one loop. A saved copy whose line
     * was moved, flipped or removed would record the replay without a lap (FBBD-237).
     */
    fun timesLaps(track: Track): Boolean {
        if (!track.timeable || track.pointToPoint) return false
        val fixes = points.map { Fix((it.t * MicrosPerSecond).toLong(), it.lat, it.lon) }
        return timeSession(track, fixes.asSequence()).laps.count { it.kind == LapKind.Timed } >= 2
    }

    private fun Double?.text(decimals: Int) = this?.let { "%.${decimals}f".format(Locale.ROOT, it) } ?: "-"

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

        /** Slower than this, the car is standing. */
        private const val StoppedKmh = 5.0

        /**
         * The replay of a session's timed laps at [track], cut at its line; null when the track times
         * fewer than two, since anything else would jump from its end back to its start on each loop.
         */
        fun of(sets: List<SampleSet>, track: Track?, startUtcMillis: Long, rateHz: Int = 25): SessionReplay? {
            val all = sets.sortedBy { it.tMicros }.mapNotNull(::point)
            val timed = timedLaps(all, track)
            if (timed.size < 2) return null
            val laps = timed.map { cut(all, it.startMicros / MicrosPerSecond, it.endMicros / MicrosPerSecond) }
            return SessionReplay(joined(laps), startUtcMillis, rateHz)
        }

        /**
         * [replays] one after another, each starting at the line where the one before ends: several
         * sessions at one track as one loop (FBBD-238). Starts at [startUtcMillis].
         */
        fun joined(replays: List<SessionReplay>, startUtcMillis: Long, rateHz: Int = 25): SessionReplay {
            require(replays.isNotEmpty()) { "nothing to join" }
            return SessionReplay(joined(replays.map { it.points }), startUtcMillis, rateHz)
        }

        /** First line of [write]'s text. */
        const val FormatLine = "betterdriver-replay 1"

        /**
         * The replay [write] wrote, starting at [startUtcMillis]. Throws [IllegalArgumentException] for
         * text that is not one, or with fewer than two points.
         */
        fun read(text: String, startUtcMillis: Long, rateHz: Int = 25): SessionReplay {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            require(lines.firstOrNull() == FormatLine) { "not a replay" }
            val points = lines.drop(1).map { line ->
                val f = line.split(' ')
                require(f.size == ReplayFields) { "bad replay line: $line" }
                val (t, lat, lon) = f.take(Speed).map { it.toDouble() }
                Point(t, lat, lon, f[Speed].toDoubleOrNull(), f[Altitude].toDoubleOrNull())
            }
            require(points.size >= 2 && points.last().t > points.first().t) { "replay too short" }
            return SessionReplay(points, startUtcMillis, rateHz)
        }

        private const val ReplayFields = 5
        /** Fields before it: seconds, latitude, longitude. */
        private const val Speed = 3
        private const val Altitude = 4

        /** The timed laps [track] finds in [all] that are not slow; none at a point-to-point track. */
        private fun timedLaps(all: List<Point>, track: Track?): List<Lap> {
            // A run from start to a separate finish cannot loop: the car would jump back to the start.
            if (track == null || !track.timeable || track.pointToPoint) return emptyList()
            val fixes = all.map { Fix((it.t * MicrosPerSecond).toLong(), it.lat, it.lon) }
            val timed = timeSession(track, fixes.asSequence()).laps.filter { it.kind == LapKind.Timed }
            // A lap with a stop in it is timed too when the track has no pit lines: leave out slow ones.
            // The lower median: of two laps, one with a stop, the stop is the slow one.
            val median = timed.map { it.timeMillis }.sorted().getOrNull((timed.size - 1) / 2) ?: 0L
            return timed.filter { it.timeMillis <= median * SlowLap && !stops(all, it) }
        }

        /**
         * The car stands still somewhere in [lap]: a pit stop or a wait on track that is not slow
         * enough to leave out by time, as in Arek's Silesia Ring laps (FBBD-237).
         */
        private fun stops(all: List<Point>, lap: Lap): Boolean {
            val from = lap.startMicros / MicrosPerSecond
            val to = lap.endMicros / MicrosPerSecond
            return all.indices.any { i -> all[i].t in from..to && speedAt(all, i) < StoppedKmh }
        }

        /** The recorded speed at point [i], or without one the path's to the next point, as [fix] has it. */
        private fun speedAt(all: List<Point>, i: Int): Double {
            val a = all[i]
            val b = all.getOrNull(i + 1)
            return a.kmh ?: if (b != null && b.t > a.t) {
                distanceMeters(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) / (b.t - a.t) * KmhPerMs
            } else {
                Double.MAX_VALUE
            }
        }

        /**
         * The timed laps one after another, each starting where the one before ends: out and in laps and
         * pit stops between them are left out. Every lap starts and ends on the line, but laps from
         * different sessions cross it metres apart, so the car drives across that gap at its speed
         * there, and from the last lap's end back to the first lap's start to close the loop (Codex).
         */
        private fun joined(laps: List<List<Point>>): List<Point> {
            val out = mutableListOf<Point>()
            // Seconds to drive from the end of out to the point at the speed there; none for the same spot.
            fun bridgeSeconds(path: List<Point>, to: Point): Double {
                val end = path.last()
                val gap = distanceMeters(GeoPoint(end.lat, end.lon), GeoPoint(to.lat, to.lon))
                if (gap < SameSpot) return 0.0
                val kmh = end.kmh ?: if (path.size >= 2) speedAt(path, path.size - 2) else StoppedKmh
                return gap / (maxOf(kmh, StoppedKmh) / KmhPerMs)
            }
            for (lap in laps.filter { it.size >= 2 }) {
                val at = out.lastOrNull()?.let { it.t + bridgeSeconds(out, lap.first()) } ?: 0.0
                val from = lap.first().t
                out += lap.map { Point(at + it.t - from, it.lat, it.lon, it.kmh, it.alt) }
            }
            val start = out.firstOrNull() ?: return out
            val bridge = bridgeSeconds(out, start)
            if (bridge > 0.0) out += Point(out.last().t + bridge, start.lat, start.lon, start.kmh, start.alt)
            return out
        }

        /** Closer than this, two points on the line are the same spot. */
        private const val SameSpot = 0.5

        /** The set's position, or null without a fix: the same check as timing, so (0, 0) is no fix. */
        private fun point(set: SampleSet): Point? {
            val fix = set.toFix() ?: return null
            var kmh: Double? = null
            var alt: Double? = null
            for (v in set.values) {
                when (v.channelId) {
                    GnssChannels.SPEED_KMH.id -> kmh = v.value
                    GnssChannels.ALT_M.id -> alt = v.value
                }
            }
            return Point(set.tMicros / MicrosPerSecond, fix.lat, fix.lon, kmh?.takeIf { it.isFinite() }, alt)
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
