package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.replay.LapAlignment
import com.flappedear.betterdriver.timing.replay.LapTrace
import com.flappedear.betterdriver.timing.replay.SessionLaps
import com.flappedear.betterdriver.timing.replay.trackFrame

/**
 * The lap being driven at one moment. [number] and [lapMicros] (time since the lap started) are
 * always known; [kind] is null for the lap still being driven when the session stopped.
 * [deltaMicros] is the live delta to the session's best lap, matched by track position as on the
 * track screen; null on out and in laps, and without a best lap.
 */
data class OverlayLap(
    val number: Int,
    val kind: LapKind?,
    val lapMicros: Long,
    val deltaMicros: Long?,
    val isBest: Boolean,
)

/** What the data overlay shows at one moment of the session; a null field is shown as a dash or left out. */
data class OverlayFrame(
    val speedKmh: Double?,
    val lap: OverlayLap?,
    /** In the frame of [OverlayData.outline]. */
    val position: Xy?,
    val g: GForce?,
)

/** Channel ids of a receiver's accelerometer in g: [forward] positive speeding up, [right] positive to the right. */
data class AccelChannels(val forward: Int, val right: Int)

/**
 * Everything the video export's data overlay draws (FBBD-152), looked up by session time: speed,
 * position, G, and the lap with its delta to the session's best lap.
 *
 * Built once per export from the session's sample sets and its replayed laps ([laps], null when
 * the session was not at a saved track with a start/finish line: then there is no lap or delta).
 * G comes from [accel] when the session has that accelerometer, else from the GNSS path.
 *
 * Not thread-safe: the exporter draws frames one at a time.
 */
class OverlayData(sets: List<SampleSet>, laps: SessionLaps?, accel: AccelChannels? = null) {
    private val x: TimeSeries
    private val y: TimeSeries
    private val speed: TimeSeries
    private val gLong: TimeSeries
    private val gLat: TimeSeries
    private val traces: List<LapTrace> = laps?.laps.orEmpty().sortedBy { it.lap.startMicros }
    private val best: LapTrace? = laps?.best
    private val starts = LongArray(traces.size) { traces[it].lap.startMicros }
    private val alignments = HashMap<Int, LapAlignment>()

    /** The track's shape for the map: the best lap's path, else the longest lap's, else every position. */
    val outline: List<Xy>

    init {
        val sorted = sets.sortedBy { it.tMicros }
        // One fix per time, each with the receiver's speed when the set holds one.
        val fixes = ArrayList<Fix>(sorted.size)
        val fixSpeeds = ArrayList<Double?>(sorted.size)
        for (s in sorted) {
            val f = s.toFix() ?: continue
            if (fixes.isEmpty() || f.tMicros > fixes.last().tMicros) {
                fixes += f
                fixSpeeds += s.valueOf(GnssChannels.SPEED_KMH.id)?.div(KmhPerMs)
            }
        }
        val frame = laps?.let { trackFrame(it.track) }
            ?: fixes.firstOrNull()?.let { runCatching { LocalFrame(GeoPoint(it.lat, it.lon)) }.getOrNull() }
        val path = fixes.map { f -> frame?.toXy(f.lat, f.lon) ?: Xy(0.0, 0.0) }
        val times = LongArray(fixes.size) { fixes[it].tMicros }
        x = TimeSeries.of(times.indices.map { times[it] to path[it].x })
        y = TimeSeries.of(times.indices.map { times[it] to path[it].y })
        speed = TimeSeries.of(sorted.mapNotNull { s -> s.valueOf(GnssChannels.SPEED_KMH.id)?.let { s.tMicros to it } })

        val forward = accel?.let { a -> sorted.mapNotNull { s -> s.valueOf(a.forward)?.let { s.tMicros to it } } }
        val right = accel?.let { a -> sorted.mapNotNull { s -> s.valueOf(a.right)?.let { s.tMicros to it } } }
        val hasAccel = forward.orEmpty().size >= 2 && right.orEmpty().size >= 2
        if (hasAccel) {
            gLong = TimeSeries.of(forward.orEmpty()).smoothed(AccelHalfWindowMicros)
            gLat = TimeSeries.of(right.orEmpty()).smoothed(AccelHalfWindowMicros)
        } else {
            val v = if (fixSpeeds.all { it != null }) DoubleArray(fixSpeeds.size) { fixSpeeds[it]!! } else null
            val g = gnssG(times, path, v)
            gLong = TimeSeries.of(times.indices.mapNotNull { i -> g[i]?.let { times[i] to it.longitudinal } })
            gLat = TimeSeries.of(times.indices.mapNotNull { i -> g[i]?.let { times[i] to it.lateral } })
        }
        outline = (best ?: traces.maxByOrNull { it.lengthMeters })?.points?.map { it.xy } ?: path
    }

    fun at(sessionMicros: Long): OverlayFrame {
        val px = x.at(sessionMicros)
        val py = y.at(sessionMicros)
        val gl = gLong.at(sessionMicros)
        val gt = gLat.at(sessionMicros)
        return OverlayFrame(
            speedKmh = speed.at(sessionMicros),
            lap = lapAt(sessionMicros),
            position = if (px != null && py != null) Xy(px, py) else null,
            g = if (gl != null && gt != null) GForce(gl, gt) else null,
        )
    }

    /** The lap at [t]; after the last finished lap, the one being driven when the session stopped. */
    private fun lapAt(t: Long): OverlayLap? {
        val last = traces.lastOrNull()?.lap
        val trace = if (traces.isEmpty() || t < starts.first()) null else traces[floorIndex(starts, t)]
        return when {
            last == null || trace == null -> null
            t >= last.endMicros -> OverlayLap(last.number + 1, null, t - last.endMicros, null, isBest = false)
            // A lap whose path could not be traced (a GNSS gap at the line) leaves a hole: no lap is shown there.
            t >= trace.lap.endMicros -> null
            else -> {
                val lapMicros = t - trace.lap.startMicros
                val isBest = trace.lap.number == best?.lap?.number
                OverlayLap(trace.lap.number, trace.lap.kind, lapMicros, deltaAt(trace, lapMicros, isBest), isBest)
            }
        }
    }

    private fun deltaAt(trace: LapTrace, lapMicros: Long, isBest: Boolean): Long? {
        val reference = best
        return when {
            reference == null || trace.lap.kind != LapKind.Timed -> null
            isBest -> 0L
            else -> {
                val alignment = alignments.getOrPut(trace.lap.number) { LapAlignment(reference, trace) }
                // Time this lap took to get here minus the time the best lap took to the same place.
                lapMicros - alignment.referenceMicrosAt(lapMicros)
            }
        }
    }

    private companion object {
        /** About a third of a second in all: smooth enough to read, quick enough for a braking point. */
        const val AccelHalfWindowMicros = 150_000L
    }
}

private fun SampleSet.valueOf(channelId: Int): Double? =
    values.firstOrNull { it.channelId == channelId }?.value?.takeIf { it.isFinite() }

private const val KmhPerMs = 3.6
