@file:Suppress("MagicNumber") // Test geometry and simulation.

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderConfig
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A made-up circuit built from straights and arcs, driven from the middle of its first straight
 * (FBBD-172): the car is held to the grip of each bend, brakes at 9 m/s² and speeds up at 5 m/s², and
 * each lap is a little quicker than the one before, so the last lap is the fastest.
 */
class SimTrack(val id: String, private val pieces: List<Piece>, val origin: GeoPoint = GeoPoint(50.1, 14.4)) {
    sealed interface Piece

    data class Straight(val meters: Double) : Piece

    /** [degrees] positive turns left. */
    data class Arc(val radius: Double, val degrees: Double) : Piece

    val frame = LocalFrame(origin)

    /** The line every half metre. */
    val path: List<Xy> = buildList {
        var x = 0.0
        var y = 0.0
        var h = 0.0
        for (p in pieces) {
            when (p) {
                is Straight -> {
                    val n = (p.meters / Step).toInt()
                    repeat(n) {
                        add(Xy(x, y))
                        x += cos(h) * p.meters / n
                        y += sin(h) * p.meters / n
                    }
                }
                is Arc -> {
                    val turn = p.degrees * PI / 180.0
                    val n = (abs(turn) * p.radius / Step).toInt()
                    repeat(n) {
                        add(Xy(x, y))
                        val dh = turn / n
                        val chord = 2 * p.radius * sin(abs(dh) / 2)
                        x += cos(h + dh / 2) * chord
                        y += sin(h + dh / 2) * chord
                        h += dh
                    }
                }
            }
        }
    }

    private val along = DoubleArray(path.size).also {
        for (i in 1 until path.size) it[i] = it[i - 1] + (path[i] - path[i - 1]).length()
    }
    val lapMeters = along.last() + (path.first() - path.last()).length()

    private val limit: DoubleArray = run {
        val n = path.size
        val v = DoubleArray(n) { i ->
            val a = path[(i + n - 4) % n]
            val b = path[i]
            val c = path[(i + 4) % n]
            val cross = abs((b - a).cross(c - a))
            val radius = if (cross < 1e-9) Double.POSITIVE_INFINITY else
                (b - a).length() * (c - b).length() * (a - c).length() / (2 * cross)
            min(45.0, sqrt(12.0 * radius))
        }
        repeat(2) {
            for (k in 1..n) {
                val i = k % n
                v[i] = min(v[i], sqrt(v[(i + n - 1) % n].let { it * it } + 2 * 5.0 * Step))
            }
            for (k in n - 1 downTo 0) v[k] = min(v[k], sqrt(v[(k + 1) % n].let { it * it } + 2 * 9.0 * Step))
        }
        v
    }

    private fun pointAt(s: Double): Xy {
        val m = s.mod(lapMeters)
        val i = along.indexOfLast { it <= m }.coerceIn(0, path.size - 1)
        val j = (i + 1) % path.size
        val span = (if (j == 0) lapMeters else along[j]) - along[i]
        val u = if (span <= 0.0) 0.0 else (m - along[i]) / span
        return path[i] + (path[j] - path[i]) * u
    }

    private fun limitAt(s: Double) = limit[(s.mod(lapMeters) / Step).toInt().coerceIn(0, path.size - 1)]

    fun geo(p: Xy): GeoPoint = frame.toGeo(p)

    /** A 30 m line across the start of the first straight, driven along it. */
    fun track(): Track {
        val heading = path[1] - path[0]
        val left = Xy(-heading.y, heading.x) * (15.0 / heading.length())
        return Track(id, "Sim $id", origin, startFinish = TrackLine(geo(left), geo(left * -1.0)))
    }

    /** Samples of [laps] laps from 200 m before the line, every [stepSeconds], lap k at pace [pace] + 1 % per lap. */
    fun samples(laps: Int, stepSeconds: Double = 0.1, pace: Double = 0.92, startMicros: Long = 0): List<SampleSet> {
        val sets = mutableListOf<SampleSet>()
        var s = -200.0
        var v = 20.0
        var t = 0.0
        var next = 0.0
        val dt = 0.005
        while (s < laps * lapMeters + 100.0) {
            if (t + 1e-9 >= next) {
                val g = geo(pointAt(s))
                sets += SampleSet(
                    startMicros + Math.round(t * 1e6),
                    listOf(
                        ChannelValue(GnssChannels.LAT.id, g.lat),
                        ChannelValue(GnssChannels.LON.id, g.lon),
                        ChannelValue(GnssChannels.SPEED_KMH.id, v * 3.6),
                    ),
                )
                next += stepSeconds
            }
            val lap = ((s + 200.0) / lapMeters).toInt()
            v = min(v + 5.0 * dt, limitAt(s) * min(1.0, pace + 0.01 * lap))
            s += v * dt
            t += dt
        }
        return sets
    }

    fun session(laps: Int, sessionId: String = id): SessionData {
        val info = SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL)
        return SessionData(
            sessionId, info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, samples(laps),
            emptyList(), null, 0, 0,
        )
    }

    /** Records [laps] laps into [store] as a phone would, naming [at] as the track; returns the session id. */
    fun record(store: SessionStore, at: Track?, laps: Int, startUtc: Long): String {
        val rec = Recorder(store, RecorderConfig(queueCapacity = 200_000))
            .start(SessionInfo(startUtcMillis = startUtc, channels = GnssChannels.ALL))
        rec.event(TrackEvent.KIND, TrackEvent.text(at?.let { TrackDetection.Saved(it, 0.0) } ?: TrackDetection.Unknown))
        samples(laps).forEach(rec::submit)
        rec.stop()
        return rec.id
    }

    companion object {
        const val Step = 0.5

        /** Rounded rectangle, 600 m by 300 m, corners of 40, 25, 60 and 30 m radius. */
        fun rectangle(id: String = "rectangle", origin: GeoPoint = GeoPoint(50.1, 14.4)) = SimTrack(
            id,
            listOf(
                Straight(260.0), Arc(40.0, 90.0), Straight(235.0), Arc(25.0, 90.0), Straight(515.0),
                Arc(60.0, 90.0), Straight(210.0), Arc(30.0, 90.0), Straight(270.0),
            ),
            origin,
        )

        /** [rectangle] with a chicane 40 m into the infield in the middle of its top straight. */
        fun chicane(id: String = "rectangle", origin: GeoPoint = GeoPoint(50.1, 14.4)): SimTrack {
            val bump = 4 * 40.0 * sin(PI / 3)
            val side = (515.0 - bump) / 2
            return SimTrack(
                id,
                listOf(
                    Straight(260.0), Arc(40.0, 90.0), Straight(235.0), Arc(25.0, 90.0), Straight(side),
                    Arc(40.0, 60.0), Arc(40.0, -120.0), Arc(40.0, 60.0), Straight(side),
                    Arc(60.0, 90.0), Straight(210.0), Arc(30.0, 90.0), Straight(270.0),
                ),
                origin,
            )
        }

        /** A clockwise circuit: a long straight into a 20 m hairpin, a fast left-right kink, two 35 m rights. */
        fun hairpin(id: String = "hairpin", origin: GeoPoint = GeoPoint(52.4, 16.8)): SimTrack {
            // The kink moves the car 183.85 m along and 117.57 m across; the last straights close the loop.
            val kinkAlong = 2 * 80.0 * sin(PI / 8) * cos(PI / 8) * 2 + 100.0 * cos(PI / 4)
            val kinkAcross = 2 * 80.0 * sin(PI / 8) * sin(PI / 8) * 2 + 100.0 * sin(PI / 4)
            return SimTrack(
                id,
                listOf(
                    Straight(400.0), Arc(20.0, -180.0), Straight(150.0), Arc(80.0, 45.0), Straight(100.0),
                    Arc(80.0, -45.0), Straight(400.0 - 150.0 - kinkAlong), Arc(35.0, -90.0),
                    Straight(40.0 + kinkAcross - 70.0), Arc(35.0, -90.0),
                ),
                origin,
            )
        }
    }
}
