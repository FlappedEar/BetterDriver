package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapEvent
import com.flappedear.betterdriver.timing.live.LapTimer
import com.flappedear.betterdriver.timing.track.Track

/** A finished lap with its path. */
class TracedLap(val lap: Lap, val trace: LapTrace)

private const val MicrosPerSecond = 1_000_000.0
private const val KmhPerMps = 3.6

/**
 * Laps with their paths (FBBD-96), from fixes in time order. Wraps the live lap timer, so laps here
 * are exactly the laps on the track screen. Speed comes from the receiver; when a fix has none it is
 * worked out from the distance to the previous fix.
 */
class LapCollector(track: Track) {
    val timer = LapTimer(track)
    val frame: LocalFrame get() = timer.frame

    private var lastPoint: Xy? = null
    private var lastT = 0L
    private var lastKmh = 0.0

    private var pathStart: Xy? = null
    private val points = mutableListOf<Xy>()
    private val times = mutableListOf<Long>()
    private val speeds = mutableListOf<Double>()
    private val measuredSpeeds = mutableListOf<Boolean>()
    private val throttles = mutableListOf<Double?>()
    private val steers = mutableListOf<Double?>()
    private val rpms = mutableListOf<Double?>()

    /** Position of the last fix in [frame], and its speed in km/h. */
    var position: Xy? = null
        private set
    var speedKmh: Double = 0.0
        private set

    /** [speedKmh] came from the receiver, not worked out from positions (FBBD-177). */
    var speedMeasured: Boolean = true
        private set

    /** The pedal at the last fix in percent (FBBD-307), null without a recent one. */
    var throttlePct: Double? = null
        private set

    /** The steering wheel at the last fix in degrees (FBBD-321), null without a recent one. */
    var steerDeg: Double? = null
        private set

    /** The engine speed at the last fix (FBBD-326), null without a recent one. */
    var rpm: Double? = null
        private set

    /** Laps finished by [fix]; a lap whose start was not at the line has no usable path and is left out. */
    fun update(fix: CoachFix): List<LapEvent> {
        val p = frame.toXy(fix.lat, fix.lon)
        val measured = fix.speedKmh?.takeIf { it.isFinite() && it >= 0 }
        val kmh = measured ?: derivedKmh(p, fix.tMicros)
        speedMeasured = measured != null
        if (fix.tMicros > lastT || lastPoint == null) {
            lastPoint = p
            lastT = fix.tMicros
            lastKmh = kmh
        }
        position = p
        speedKmh = kmh
        throttlePct = fix.throttlePct?.takeIf { it.isFinite() }
        steerDeg = fix.steerDeg?.takeIf { it.isFinite() }
        rpm = fix.rpm?.takeIf { it.isFinite() }
        val events = timer.update(fix.toFix())
        finished.clear()
        for (e in events) if (e is LapEvent.LapDone) onLapDone(e.lap, kmh)
        if (timer.lapStartedAtLine && fix.tMicros > timer.lapStartMicros) {
            points += p
            times += fix.tMicros - timer.lapStartMicros
            speeds += kmh
            measuredSpeeds += speedMeasured
            throttles += throttlePct
            steers += steerDeg
            rpms += rpm
        }
        return events
    }

    /** Laps with a path finished by the last [update]. */
    val finished = mutableListOf<TracedLap>()

    private fun onLapDone(lap: Lap, kmh: Double) {
        val start = pathStart
        val end = timer.lapStartPoint
        if (start != null && end != null) {
            val trace = LapTrace(
                listOf(start) + points + end,
                listOf(0L) + times + lap.timeMicros,
                listOf(speeds.firstOrNull() ?: kmh) + speeds + kmh,
                listOf(measuredSpeeds.firstOrNull() ?: speedMeasured) + measuredSpeeds + speedMeasured,
                throttles.takeIf { t -> t.any { it != null } }
                    ?.let { t -> listOf(t.first()) + t + throttlePct },
                steers.takeIf { t -> t.any { it != null } }
                    ?.let { t -> listOf(t.first()) + t + steerDeg },
                rpms.takeIf { t -> t.any { it != null } }
                    ?.let { t -> listOf(t.first()) + t + rpm },
            )
            finished += TracedLap(lap, trace)
        }
        pathStart = end
        points.clear()
        times.clear()
        speeds.clear()
        measuredSpeeds.clear()
        throttles.clear()
        steers.clear()
        rpms.clear()
    }

    private fun derivedKmh(p: Xy, t: Long): Double {
        val q = lastPoint ?: return 0.0
        val dt = (t - lastT) / MicrosPerSecond
        return if (dt <= 0) lastKmh else (p - q).length() / dt * KmhPerMps
    }
}

/** Every lap with a path in a session's fixes. */
fun tracedLaps(track: Track, fixes: Sequence<CoachFix>): List<TracedLap> {
    val c = LapCollector(track)
    val laps = mutableListOf<TracedLap>()
    for (f in fixes) {
        c.update(f)
        laps += c.finished
    }
    return laps
}

/** The GNSS fix with speed in a recorder sample set, or null if it has no position. */
fun SampleSet.toCoachFix(): CoachFix? {
    var lat: Double? = null
    var lon: Double? = null
    var speed: Double? = null
    for (v in values) {
        when (v.channelId) {
            GnssChannels.LAT.id -> lat = v.value
            GnssChannels.LON.id -> lon = v.value
            GnssChannels.SPEED_KMH.id -> speed = v.value
        }
    }
    val usable = lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
        // Receivers without a fix can report 0, 0.
        !(lat == 0.0 && lon == 0.0)
    return if (usable) CoachFix(tMicros, lat!!, lon!!, speed?.takeIf { it.isFinite() }) else null
}
