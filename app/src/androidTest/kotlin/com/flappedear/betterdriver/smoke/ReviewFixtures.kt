@file:Suppress("MagicNumber") // Fixture geometry, paces and dates.

package com.flappedear.betterdriver.smoke

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.TrackStore
import java.io.File
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two sessions with frame-numbered phone video for the video review tests (FBBD-24, FBBD-93,
 * FBBD-169, FBBD-163), at a track of their own ([SyncRing]) so the seeded smoke data is untouched:
 *
 * - [SyncCarA]: an out lap, two timed laps and a little more. Lap 1 is quicker on the straights,
 *   lap 2 through the corners, so the theoretical best takes parts from both. Its video
 *   (assets review/sync_a.mp4, 96 s) covers the whole session.
 * - [SyncCarB], recorded earlier: an out lap and one timed lap, faster than either lap of A, so the
 *   lap review opens on A's best lap against B's lap. Its video (review/sync_b.mp4, 52 s) covers it.
 *
 * Both videos start with the session (media time 0 is session time 0) and show their own frame
 * number ([FrameCode]); tools/review-fixtures/make.sh made them. Every frame is 1/30 s.
 *
 * The circuit is the analysis tests' rounded rectangle (SimTrack) made smaller: four corners of 25
 * to 60 m radius between straights, 1.1 km a lap, driven at the grip of each bend.
 */
object ReviewFixtures {
    const val SyncRingId = "smoke-sync-ring"
    const val SyncRingName = "Sync Ring"
    const val SyncCarA = "Sync car A"
    const val SyncCarB = "Sync car B"

    const val VideoA = "review/sync_a.mp4"
    const val VideoB = "review/sync_b.mp4"

    /** The fixture videos' frame rate. */
    const val Fps = 30
    private const val VideoASeconds = 96.0
    private const val VideoBSeconds = 52.0

    /** 20 Sep 2026 10:00 and 07:13 UTC: newer than the smoke sessions, A the newest. */
    private const val StartA = 1_789_898_400_000L
    private const val StartB = 1_789_888_380_000L

    private val circuit = Circuit(GeoPoint(53.2, 18.6))

    val syncRing: Track get() = circuit.track()

    /**
     * Saves [syncRing] and records both sessions under [filesDir], the app's files folder; [asset]
     * opens a file of the test APK's assets.
     */
    fun seed(filesDir: File, asset: (String) -> InputStream) {
        TrackStore(File(filesDir, "tracks")).save(syncRing)
        val store = SessionStore(File(filesDir, "sessions"))
        // Each lap's (corner pace, straight pace), from the out lap on.
        val a = circuit.samples(listOf(0.9 to 0.9, 0.86 to 0.94, 0.92 to 0.86, 0.9 to 0.9), laps = 2)
        val b = circuit.samples(listOf(0.95 to 0.95, 0.95 to 0.95, 0.95 to 0.95), laps = 1)
        check(a.last().tMicros + 1_000_000 <= VideoASeconds * 1e6) { "sync_a.mp4 is shorter than its session" }
        check(b.last().tMicros + 1_000_000 <= VideoBSeconds * 1e6) { "sync_b.mp4 is shorter than its session" }
        SmokeFixtures.record(store, info(StartA, SyncCarA), syncRing, a, video = { asset(VideoA) })
        SmokeFixtures.record(store, info(StartB, SyncCarB), syncRing, b, video = { asset(VideoB) })
    }

    private fun info(start: Long, car: String) = SessionInfo(
        startUtcMillis = start,
        car = car,
        track = syncRing.displayName,
        devices = listOf("RaceBox Mini S"),
        channels = SmokeFixtures.Gnss,
    )

    /**
     * Straights and arcs (as SimTrack in the analysis tests), the line across the start of the first
     * straight. The car speeds up at 5 m/s² and brakes at 9 m/s² to the grip of each bend
     * (sqrt(12 r) m/s, at most 45 m/s), times the pace of the lap and of the part (corner or straight).
     */
    private class Circuit(val origin: GeoPoint) {
        private val frame = LocalFrame(origin)
        private val path: List<Xy> = buildPath()
        private val along = DoubleArray(path.size).also {
            for (i in 1 until path.size) it[i] = it[i - 1] + (path[i] - path[i - 1]).length()
        }
        val lapMeters = along.last() + (path.first() - path.last()).length()
        private val limit = speedLimits()

        fun track(): Track {
            val heading = path[1] - path[0]
            val left = Xy(-heading.y, heading.x) * (HalfLine / heading.length())
            return Track(
                id = SyncRingId,
                name = SyncRingName,
                center = origin,
                startFinish = TrackLine(frame.toGeo(left), frame.toGeo(left * -1.0)),
            )
        }

        /**
         * Fixes every 40 ms from 100 m before the line to 60 m past [laps] laps; [paces] holds each
         * lap's (corner, straight) pace from the out lap on.
         */
        fun samples(paces: List<Pair<Double, Double>>, laps: Int): List<SmokeFixtures.Sample> {
            val out = ArrayList<SmokeFixtures.Sample>()
            var s = -StartBefore
            var v = 20.0
            var t = 0.0
            var next = 0.0
            while (s < laps * lapMeters + EndAfter) {
                if (t + 1e-9 >= next) {
                    val p = pointAt(s)
                    val ahead = pointAt(s + 1.0)
                    val heading = (Math.toDegrees(atan2(ahead.x - p.x, ahead.y - p.y)) + 360) % 360
                    val g = frame.toGeo(p)
                    val values: List<ChannelValue> = SmokeFixtures.gnss(t, g.lat, g.lon, v * KmhPerMps, heading)
                    out += SmokeFixtures.Sample(1_000_000L + Math.round(t * 1e6), values)
                    next += SampleSeconds
                }
                val lap = (floor(s / lapMeters).toInt() + 1).coerceIn(0, paces.lastIndex)
                val l = limitAt(s)
                val pace = if (l >= StraightSpeed) paces[lap].second else paces[lap].first
                v = min(v + Accel * Dt, l * pace)
                s += v * Dt
                t += Dt
            }
            return out
        }

        private fun buildPath(): List<Xy> = buildList {
            var x = 0.0
            var y = 0.0
            var h = 0.0
            for ((radius, value) in Pieces) {
                if (radius == null) {
                    val n = (value / Step).toInt()
                    repeat(n) {
                        add(Xy(x, y))
                        x += cos(h) * value / n
                        y += sin(h) * value / n
                    }
                } else {
                    val turn = value * PI / 180.0
                    val n = (abs(turn) * radius / Step).toInt()
                    repeat(n) {
                        add(Xy(x, y))
                        val dh = turn / n
                        val chord = 2 * radius * sin(abs(dh) / 2)
                        x += cos(h + dh / 2) * chord
                        y += sin(h + dh / 2) * chord
                        h += dh
                    }
                }
            }
        }

        private fun speedLimits(): DoubleArray {
            val n = path.size
            val v = DoubleArray(n) { i ->
                val a = path[(i + n - 4) % n]
                val b = path[i]
                val c = path[(i + 4) % n]
                val cross = abs((b - a).cross(c - a))
                val radius = if (cross < 1e-9) Double.POSITIVE_INFINITY else
                    (b - a).length() * (c - b).length() * (a - c).length() / (2 * cross)
                min(MaxSpeed, sqrt(Grip * radius))
            }
            repeat(2) {
                for (k in 1..n) {
                    val i = k % n
                    v[i] = min(v[i], sqrt(v[(i + n - 1) % n].let { it * it } + 2 * Accel * Step))
                }
                for (k in n - 1 downTo 0) v[k] = min(v[k], sqrt(v[(k + 1) % n].let { it * it } + 2 * Brake * Step))
            }
            return v
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

        companion object {
            /** (radius, degrees) for a bend turning left, (null, metres) for a straight. */
            val Pieces: List<Pair<Double?, Double>> = listOf(
                null to 150.0, 40.0 to 90.0, null to 135.0, 25.0 to 90.0, null to 300.0,
                60.0 to 90.0, null to 110.0, 30.0 to 90.0, null to 165.0,
            )
            const val Step = 0.5
            const val HalfLine = 15.0
            const val StartBefore = 100.0
            const val EndAfter = 60.0
            const val SampleSeconds = 0.04
            const val Dt = 0.005
            const val Accel = 5.0
            const val Brake = 9.0
            const val Grip = 12.0
            const val MaxSpeed = 45.0

            /** A speed limit this high is a straight's. */
            const val StraightSpeed = 44.9
            const val KmhPerMps = 3.6
        }
    }
}
