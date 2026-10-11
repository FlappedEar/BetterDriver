package com.flappedear.betterdriver.timing.replay

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.DefaultMinLapSeconds
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapBook
import com.flappedear.betterdriver.timing.live.LapEvent
import com.flappedear.betterdriver.timing.live.LapTimer
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import kotlin.math.hypot

/**
 * One GNSS position of a lap (FBBD-91). [tMicros] is on the session clock, [lapMicros] since the
 * lap started. [xy] is in the track's frame ([trackFrame]), which is the same for every session of
 * the track, so laps of different sessions can be laid over each other. [distanceMeters] is driven
 * since the lap started. [speedKmh] is the receiver's speed, or null if the session has none.
 */
data class TracePoint(
    val tMicros: Long,
    val lapMicros: Long,
    val lat: Double,
    val lon: Double,
    val xy: Xy,
    val distanceMeters: Double,
    val speedKmh: Double?,
)

/** A lap with its path. The first point is the lap start and the last the lap end, both interpolated. */
data class LapTrace(val sessionId: String, val lap: Lap, val points: List<TracePoint>) {
    init {
        require(points.size >= 2) { "a lap trace needs at least two points" }
    }

    val lengthMeters: Double get() = points.last().distanceMeters

    /** The lap's position, distance and speed at [lapMicros], interpolated between fixes and clamped to the lap. */
    fun at(lapMicros: Long): TracePoint {
        val i = segmentAt(lapMicros.toDouble()) { it.lapMicros.toDouble() }
        val a = points[i]
        val b = points[i + 1]
        val span = (b.lapMicros - a.lapMicros).toDouble()
        val u = if (span <= 0) 0.0 else ((lapMicros - a.lapMicros) / span).coerceIn(0.0, 1.0)
        return interpolate(a, b, u)
    }

    /** Lap time, in microseconds, when the lap had covered [meters]; clamped to the lap. */
    fun lapMicrosAtDistance(meters: Double): Long {
        val i = segmentAt(meters) { it.distanceMeters }
        val a = points[i]
        val b = points[i + 1]
        val span = b.distanceMeters - a.distanceMeters
        val u = if (span <= 0) 0.0 else ((meters - a.distanceMeters) / span).coerceIn(0.0, 1.0)
        return a.lapMicros + Math.round(u * (b.lapMicros - a.lapMicros))
    }

    /** Index of the segment [i, i + 1] holding [x], by the increasing key [of]. */
    private inline fun segmentAt(x: Double, of: (TracePoint) -> Double): Int {
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (of(points[mid]) <= x) lo = mid else hi = mid
        }
        return lo
    }
}

/** Laps of one saved session on one track, with bests as the track screen showed them. */
class SessionLaps(val sessionId: String, val track: Track, val laps: List<LapTrace>, val book: LapBook) {
    /** The fastest timed lap, if any. */
    val best: LapTrace? get() = book.best?.let { b -> laps.firstOrNull { it.lap.number == b.number } }
}

/** The frame of every [TracePoint] of [track]: centred on the track, not on its lines, so edits keep it. */
fun trackFrame(track: Track) = LocalFrame(track.center)

/**
 * Laps of a saved session (FBBD-91): its GNSS fixes replayed through the [LapTimer] for [track],
 * as the track screen timed them live. Works for laps from any session of the track, so the tablet
 * review and the analysis (Phase 3) read laps the same way. [track] needs a start/finish line.
 */
fun replayLaps(
    sessionId: String,
    sets: List<SampleSet>,
    track: Track,
    minLapSeconds: Double = DefaultMinLapSeconds,
): SessionLaps {
    val timer = LapTimer(track, minLapSeconds)
    val frame = trackFrame(track)
    val book = LapBook(timer.sectorCount)
    val fixes = ArrayList<Sample>()
    val done = ArrayList<Lap>()
    for (set in sets) {
        val sample = sampleOf(set, frame)
        if (sample != null && (fixes.isEmpty() || sample.t > fixes.last().t)) {
            fixes += sample
            for (e in timer.update(Fix(sample.t, sample.lat, sample.lon))) if (e is LapEvent.LapDone) done += e.lap
        }
    }
    val traces = done.mapNotNull { lap -> trace(sessionId, lap, fixes, frame)?.also { book.add(lap) } }
    return SessionLaps(sessionId, track, traces, book)
}

/** The fix where a session's position is first known, for picking its track. */
fun firstPosition(sets: List<SampleSet>): GeoPoint? = sets.asSequence()
    .firstNotNullOfOrNull { it.toFix() }
    ?.let { runCatching { GeoPoint(it.lat, it.lon) }.getOrNull() }

/** The position in [set], with its speed; null without a fix. Receivers without a fix can report 0, 0. */
private fun sampleOf(set: SampleSet, frame: LocalFrame): Sample? {
    val fix = set.toFix() ?: return null
    val speed = set.values.firstOrNull { it.channelId == GnssChannels.SPEED_KMH.id }?.value
    return Sample(fix.tMicros, fix.lat, fix.lon, frame.toXy(fix.lat, fix.lon), speed?.takeIf { it.isFinite() })
}

private class Sample(val t: Long, val lat: Double, val lon: Double, val xy: Xy, val speed: Double?)

private fun trace(sessionId: String, lap: Lap, fixes: List<Sample>, frame: LocalFrame): LapTrace? {
    val start = sampleAt(fixes, lap.startMicros, frame)
    val end = sampleAt(fixes, lap.endMicros, frame)
    if (start == null || end == null) return null
    val inside = fixes.filter { it.t > lap.startMicros && it.t < lap.endMicros }
    val points = ArrayList<TracePoint>(inside.size + 2)
    var distance = 0.0
    var prev: Sample? = null
    for (s in listOf(start) + inside + end) {
        prev?.let { distance += hypot(s.xy.x - it.xy.x, s.xy.y - it.xy.y) }
        points += TracePoint(s.t, s.t - lap.startMicros, s.lat, s.lon, s.xy, distance, s.speed)
        prev = s
    }
    return LapTrace(sessionId, lap, points)
}

/** A sample at [t] interpolated between the fixes around it; null outside the fixes. */
private fun sampleAt(fixes: List<Sample>, t: Long, frame: LocalFrame): Sample? {
    if (fixes.isEmpty() || t < fixes.first().t || t > fixes.last().t) return null
    var lo = 0
    var hi = fixes.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (fixes[mid].t <= t) lo = mid else hi = mid
    }
    val a = fixes[lo]
    val b = fixes[hi]
    val u = if (b.t == a.t) 0.0 else (t - a.t).toDouble() / (b.t - a.t)
    val lat = a.lat + u * (b.lat - a.lat)
    val lon = a.lon + u * (b.lon - a.lon)
    val speed = if (a.speed != null && b.speed != null) a.speed + u * (b.speed - a.speed) else a.speed ?: b.speed
    return Sample(t, lat, lon, frame.toXy(lat, lon), speed)
}

private fun interpolate(a: TracePoint, b: TracePoint, u: Double): TracePoint {
    fun mix(x: Double, y: Double) = x + u * (y - x)
    val speed = if (a.speedKmh != null && b.speedKmh != null) mix(a.speedKmh, b.speedKmh) else a.speedKmh
    return TracePoint(
        tMicros = a.tMicros + Math.round(u * (b.tMicros - a.tMicros)),
        lapMicros = a.lapMicros + Math.round(u * (b.lapMicros - a.lapMicros)),
        lat = mix(a.lat, b.lat),
        lon = mix(a.lon, b.lon),
        xy = Xy(mix(a.xy.x, b.xy.x), mix(a.xy.y, b.xy.y)),
        distanceMeters = mix(a.distanceMeters, b.distanceMeters),
        speedKmh = speed,
    )
}
