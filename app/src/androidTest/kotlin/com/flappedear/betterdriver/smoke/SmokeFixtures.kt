@file:Suppress("MagicNumber") // Fixture dates, geometry and driving.

package com.flappedear.betterdriver.smoke

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoEventText
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackKind
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.TrackStore
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Realistic data for the emulator smoke tests (FBBD-161), written into the app's files folder before
 * the app starts: two saved tracks (a circuit and a point-to-point hill) and three sessions in the
 * recorder's own format, written by the recorder itself.
 *
 * - [VideoCar]: three laps of the analysis parity corpus's corners_measured.vbo at the circuit, with
 *   the RaceBox's accelerometer and accuracy channels and phone video covering the whole session.
 * - [PlainCar]: three laps of segments_mixed.vbo at the same circuit, GPS only and no video, earlier.
 * - [HillCar]: two runs up the hill from a standstill (so also acceleration runs), each followed by
 *   the drive back.
 *
 * Pure Kotlin, so the same code can be checked on the JVM against the analysis.
 */
object SmokeFixtures {
    const val CircuitId = "smoke-ring"
    const val CircuitName = "Smoke Ring"
    const val HillId = "smoke-hill"
    const val HillName = "Smoke Hill"

    /** Each session's car, which its card shows; the tests find the cards by it. */
    const val VideoCar = "Video car"
    const val PlainCar = "Plain car"
    const val HillCar = "Hill car"

    /** The phone video file of the [VideoCar] session. */
    const val VideoFile = "phone_0001.mp4"

    /** The VBO files from the analysis parity corpus the circuit sessions are made of. */
    const val VideoVbo = "corners_measured.vbo"
    const val PlainVbo = "segments_mixed.vbo"

    /** Start times, newest first as Sessions lists them: 12 Sep 2026 10:00 and 08:30 UTC, 5 Sep 11:00. */
    const val VideoStartUtc = 1_789_207_200_000L
    const val PlainStartUtc = 1_789_201_800_000L
    const val HillStartUtc = 1_788_606_000_000L

    private val Gnss = listOf(
        GnssChannels.UTC_MS,
        GnssChannels.LAT,
        GnssChannels.LON,
        GnssChannels.SPEED_KMH,
        GnssChannels.HEADING,
        GnssChannels.SATS,
    )

    // As the RaceBox records them (core-ble RaceBoxChannels).
    private val HorizontalAccuracy = ChannelDef(101, "gnss_hacc", "m", "racebox", 2)
    private val AccelX = ChannelDef(110, "accel_x", "g", "racebox", 3)
    private val AccelY = ChannelDef(111, "accel_y", "g", "racebox", 3)

    /** The corpus's start/finish line across the straight at 52° N, crossed driving north. */
    val circuit = Track(
        id = CircuitId,
        name = CircuitName,
        center = GeoPoint(52.0002, 20.9978),
        startFinish = TrackLine(GeoPoint(52.0, 20.99985393), GeoPoint(52.0, 21.00014607)),
    )

    /** As the analysis's PointToPointRunsTest: a winding road east, start at 100 m and finish at 1,100 m. */
    val hill = Track(
        id = HillId,
        name = HillName,
        center = Road.geo(600.0, 0.0),
        startFinish = Road.lineAt(100.0),
        kind = TrackKind.PointToPoint,
        finish = Road.lineAt(1_100.0),
    )

    /**
     * Writes the tracks and sessions under [filesDir], the app's files folder. [vbo] reads a corpus
     * file's text; [video] opens the phone video file.
     */
    fun seed(filesDir: File, vbo: (String) -> String, video: () -> InputStream) {
        val tracks = TrackStore(File(filesDir, "tracks"))
        tracks.save(circuit)
        tracks.save(hill)
        val sessions = SessionStore(File(filesDir, "sessions"))
        record(sessions, info(VideoStartUtc, VideoCar, circuit), circuit, circuitSamples(vbo(VideoVbo)), video)
        record(sessions, info(PlainStartUtc, PlainCar, circuit), circuit, circuitSamples(vbo(PlainVbo)), null)
        record(sessions, info(HillStartUtc, HillCar, hill), hill, hillSamples(), null)
    }

    private fun info(start: Long, car: String, track: Track) = SessionInfo(
        startUtcMillis = start,
        car = car,
        track = track.displayName,
        devices = listOf("RaceBox Mini S"),
        channels = Gnss + listOf(HorizontalAccuracy, AccelX, AccelY),
    )

    /** One sample set: its time on the session clock and its values. */
    class Sample(val tMicros: Long, val values: List<ChannelValue>)

    /**
     * Records [samples] with the recorder, its clock following the samples so the session lasts as
     * long as they do. The session names [track] as the app does when it starts at a saved track.
     * With [video], the phone camera's file covers the whole session.
     */
    private fun record(
        store: SessionStore,
        info: SessionInfo,
        track: Track,
        samples: List<Sample>,
        video: (() -> InputStream)?,
    ) {
        val clock = AtomicLong(0L)
        val recording = Recorder(store, nanoTime = clock::get).start(info)
        recording.eventAt(0, "track", "saved ${track.id} rev ${track.revision} ${track.displayName}")
        val end = samples.last().tMicros + 1_000_000
        if (video != null) {
            val dir = File(recording.dir, VideoFiles.DIR).apply { mkdirs() }
            video().use { input -> File(dir, VideoFile).outputStream().use { input.copyTo(it) } }
            val file = VideoEventText.FILE to VideoFile
            val camera = VideoEventText.CAMERA to CameraIds.PHONE
            recording.eventAt(0, EventKinds.VIDEO_START, VideoEventText.format(camera, file))
            val stop = VideoEventText.format(camera, file, VideoEventText.REASON to "user")
            recording.eventAt(end, EventKinds.VIDEO_STOP, stop)
        }
        for (s in samples) {
            clock.set(s.tMicros * 1_000)
            recording.submit(SampleSet(s.tMicros, s.values))
        }
        clock.set(end * 1_000)
        check(recording.stop()) { "the fixture session did not finish" }
        check(recording.droppedCount == 0L) { "the fixture session dropped samples" }
    }

    /**
     * A corpus VBO (degrees, seconds) as RaceBox samples: position, speed, heading from the track, the
     * longitudinal acceleration as accel_x, the lateral one from speed and turn rate as accel_y.
     */
    fun circuitSamples(vbo: String): List<Sample> {
        val rows = VboText.rows(vbo)
        val out = ArrayList<Sample>(rows.size)
        for (i in rows.indices) {
            val r = rows[i]
            val prev = rows[maxOf(i - 1, 0)]
            val next = rows[min(i + 1, rows.lastIndex)]
            val heading = headingDeg(prev.lat, prev.lon, next.lat, next.lon)
            val time = r.getValue("time")
            val values = gnss(time, r.lat, r.lon, r.getValue("velocity"), heading) +
                listOfNotNull(
                    r["accuracy"]?.let { ChannelValue(HorizontalAccuracy.id, it) },
                    r["longacc"]?.let { ChannelValue(AccelX.id, it) },
                    ChannelValue(AccelY.id, lateralG(rows, i)),
                )
            out += Sample(micros(time), values)
        }
        return out
    }

    /**
     * Two runs up [hill]: standing still, a launch to cruising speed, the road to past the finish,
     * a stop, and the drive back west 8 m to the north. The second run is a little slower.
     */
    fun hillSamples(): List<Sample> {
        val out = ArrayList<Sample>()
        val drive = Drive(out)
        for ((launch, cruise) in listOf(6.0 to 38.0, 5.0 to 35.0)) {
            drive.stand(3.0, 0.0, offset = 0.0)
            drive.go(from = 0.0, to = 1_250.0, offset = 0.0, accel = launch, cruise = cruise, brake = 8.0)
            drive.stand(4.0, 1_250.0, offset = 8.0)
            drive.go(from = 1_250.0, to = 0.0, offset = 8.0, accel = 3.0, cruise = 16.0, brake = 5.0)
        }
        drive.stand(3.0, 0.0, offset = 0.0)
        return out
    }

    private class Drive(val out: MutableList<Sample>) {
        var t = 0.0

        fun stand(seconds: Double, x: Double, offset: Double) {
            val end = t + seconds
            while (t < end) {
                add(x, offset, 0.0, 0.0, if (offset == 0.0) 90.0 else 270.0)
                t += Dt
            }
        }

        /** Along the road from [from] to [to]: speeds up at [accel], holds [cruise], stops at [brake] (m/s²). */
        @Suppress("LongParameterList")
        fun go(from: Double, to: Double, offset: Double, accel: Double, cruise: Double, brake: Double) {
            val east = to > from
            var x = from
            var v = 0.0
            while (if (east) x < to else x > to) {
                val left = if (east) to - x else x - to
                val stopping = v * v / (2 * brake)
                val a = when {
                    left <= stopping -> -brake
                    v < cruise -> accel
                    else -> 0.0
                }
                v = (v + a * Dt).coerceIn(0.5, cruise)
                val slope = Road.slope(x)
                val dx = v * Dt / sqrt(1 + slope * slope)
                x += if (east) dx else -dx
                val heading = Math.toDegrees(atan2(if (east) 1.0 else -1.0, if (east) slope else -slope))
                add(x, offset, v, a, (heading + 360) % 360)
                t += Dt
            }
        }

        private fun add(x: Double, offset: Double, v: Double, a: Double, heading: Double) {
            val g = Road.geo(x, Road.y(x) + offset)
            // Lateral acceleration: speed squared times the road's curvature.
            val lateral = v * v * Road.curvature(x) / StandardGravity
            val values = gnss(t, g.lat, g.lon, v * KmhPerMps, heading) + listOf(
                ChannelValue(HorizontalAccuracy.id, 0.5),
                ChannelValue(AccelX.id, a / StandardGravity),
                ChannelValue(AccelY.id, lateral),
            )
            out += Sample(micros(t), values)
        }
    }

    /** The hill road: y = 40 sin(x / 150) metres north of a line east from 49.3° N, 19.9° E. */
    private object Road {
        private const val Lat = 49.3
        private const val Lon = 19.9
        private const val MetersPerDegreeLat = 111_195.0
        private val metersPerDegreeLon = MetersPerDegreeLat * cos(Lat * PI / 180.0)

        fun y(x: Double) = 40.0 * sin(x / 150.0)

        fun slope(x: Double) = 40.0 / 150.0 * cos(x / 150.0)

        fun curvature(x: Double): Double {
            val second = -40.0 / (150.0 * 150.0) * sin(x / 150.0)
            val s = slope(x)
            return second / (1 + s * s).let { it * sqrt(it) }
        }

        fun geo(x: Double, y: Double) = GeoPoint(Lat + y / MetersPerDegreeLat, Lon + x / metersPerDegreeLon)

        /** A 30 m line across the road, crossed forwards driving east (left end to the north). */
        fun lineAt(x: Double) = TrackLine(geo(x, y(x) + 15), geo(x, y(x) - 15))
    }

    private fun gnss(seconds: Double, lat: Double, lon: Double, kmh: Double, heading: Double) = listOf(
        ChannelValue(GnssChannels.UTC_MS.id, Math.round(seconds * 1_000).toDouble()),
        ChannelValue(GnssChannels.LAT.id, lat),
        ChannelValue(GnssChannels.LON.id, lon),
        ChannelValue(GnssChannels.SPEED_KMH.id, kmh),
        ChannelValue(GnssChannels.HEADING.id, heading),
        ChannelValue(GnssChannels.SATS.id, 18.0),
    )

    /** Session time: one second after the recording started, as a receiver's first fix. */
    private fun micros(seconds: Double) = 1_000_000L + Math.round(seconds * 1e6)

    /** Clockwise from north, from one point to the next. */
    private fun headingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val north = lat2 - lat1
        val east = (lon2 - lon1) * cos(Math.toRadians(lat1))
        if (north == 0.0 && east == 0.0) return 0.0
        return (Math.toDegrees(atan2(east, north)) + 360) % 360
    }

    /** Lateral acceleration in g at row [i]: speed times turn rate, over half a second either side. */
    private fun lateralG(rows: List<Map<String, Double>>, i: Int): Double {
        val a = rows[maxOf(i - Span, 0)]
        val b = rows[i]
        val c = rows[min(i + Span, rows.lastIndex)]
        val dt = c.getValue("time") - a.getValue("time")
        if (dt <= 0) return 0.0
        val h1 = headingDeg(a.lat, a.lon, b.lat, b.lon)
        val h2 = headingDeg(b.lat, b.lon, c.lat, c.lon)
        val turn = ((h2 - h1 + 540) % 360) - 180
        val rate = Math.toRadians(turn) / (dt / 2)
        return b.getValue("velocity") / KmhPerMps * rate / StandardGravity
    }

    private val Map<String, Double>.lat get() = getValue("latitude")
    private val Map<String, Double>.lon get() = getValue("longitude")

    private const val Dt = 0.04
    private const val Span = 5
    private const val KmhPerMps = 3.6
    private const val StandardGravity = 9.80665

}

/** The rows of a VBO file in degrees, as the parity corpus writes them: column name to value. */
object VboText {
    fun rows(text: String): List<Map<String, Double>> {
        var section = ""
        var names = emptyList<String>()
        val rows = ArrayList<Map<String, Double>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("[") && line.endsWith("]") -> section = line.trim('[', ']').lowercase()
                section == "column names" -> names = line.split(Regex("\\s+"))
                section == "data" -> {
                    val cells = line.split(Regex("\\s+")).map { it.toDouble() }
                    rows += names.zip(cells).toMap()
                }
            }
        }
        return rows
    }
}
