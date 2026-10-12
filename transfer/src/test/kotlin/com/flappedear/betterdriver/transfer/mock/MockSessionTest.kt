@file:Suppress("MagicNumber") // VBO field layouts and unit conversions.

package com.flappedear.betterdriver.transfer.mock

import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.transfer.SessionBundle
import com.flappedear.betterdriver.transfer.SessionBundleExtension
import com.flappedear.betterdriver.transfer.foreign.SessionImport
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.VideoSourceKind
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Builds a mock session with video from a VBO (FBBD-191), so tablet review and coaching can be
 * tried between track days. Skipped unless run with `-PmockVbo=<file.vbo> -PmockVideo=<file.mp4>
 * -PmockVideoStartUtcMs=<ms> -PmockOut=<dir>`; tools/mock-session/make.sh renders the video and
 * runs it.
 *
 * The VBO is imported as the app imports it (SessionImport), timed against a track made from the
 * VBO's own [laptiming] gates, and the video is added as a phone clip whose first frame is at
 * [mockVideoStartUtcMs]. The result is a .bdsession bundle, opened on the tablet like one sent
 * from the phone.
 */
class MockSessionTest {
    @Test
    fun `write a mock session bundle`() {
        val vboPath = System.getProperty("mockVbo")
        assumeTrue(!vboPath.isNullOrBlank(), "set -PmockVbo to build a mock session")
        val vbo = File(vboPath!!)
        val video = File(System.getProperty("mockVideo"))
        val videoStartUtcMs = System.getProperty("mockVideoStartUtcMs").toLong()
        val videoDurationUs = System.getProperty("mockVideoDurationUs").toLong()
        val out = File(System.getProperty("mockOut")).apply { mkdirs() }
        val work = File(out, "work").apply { deleteRecursively() }

        val parsed = Vbo.read(vbo)
        val track = parsed.track(System.getProperty("mockTrackName") ?: "${parsed.name} (mock)")
        val store = SessionStore(File(work, "sessions"))
        val imported = vbo.inputStream().use {
            SessionImport(store, File(work, "staging")).import(it, vbo.name, listOf(track), CircuitList.Empty)
        }
        assertIs<TrackDetection.Saved>(imported.detection, "the session must start inside the track")
        val dir = store.dir(imported.id)

        val index = VideoIndex(dir)
        val clipName = "phone_0001.mp4"
        video.copyTo(File(index.videoDir.apply { mkdirs() }, clipName))
        index.put(
            VideoClip(
                fileName = clipName,
                kind = VideoSourceKind.PHONE,
                sourceId = CameraIds.PHONE,
                sessionOffsetUs = (videoStartUtcMs - imported.info.startUtcMillis) * 1000,
                alignment = Alignment(AlignmentMethod.CAPTURE_CLOCK, 0),
                durationUs = videoDurationUs,
            ),
        )

        val laps = replayLaps(imported.id, store.read(imported.id).sampleSets, track)
        assertTrue(laps.laps.isNotEmpty(), "no laps against the VBO's start/finish line")
        val bundle = File(out, vbo.nameWithoutExtension + "_mock." + SessionBundleExtension)
        bundle.outputStream().use { SessionBundle.write(store, dir, track, it) }
        work.deleteRecursively()
        println("mock session ${imported.id}: ${imported.points} points, ${laps.laps.size} laps -> $bundle")
        for (lap in laps.laps.map { it.lap }) {
            println("  lap ${lap.number}: %.2f s".format((lap.endMicros - lap.startMicros) / 1e6))
        }
    }

    /** The parts of a VBO a mock track needs: its name, gates and positions with heading. */
    private class Vbo(val name: String, val gates: List<Pair<String, TrackLine>>, val fixes: List<DoubleArray>) {

        /**
         * A track with the VBO's start/finish line and splits (in name order), each square to the car's
         * heading, recognised as far out as the VBO's farthest fix (so its first fix is detected).
         */
        fun track(trackName: String): Track {
            val lines = gates.map { (label, gate) -> label to across(gate) }
            val start = lines.first { it.first == "Start" }.second
            val splits = lines.filter { it.first.startsWith("Split") }.sortedBy { it.first }.map { it.second }
            val center = midpoint(start)
            val reach = fixes.maxOfOrNull { metres(center, GeoPoint(it[0], it[1])) } ?: 0.0
            return Track(
                id = "mock-" + trackName.lowercase().filter { it.isLetterOrDigit() },
                name = trackName,
                center = center,
                radiusMeters = (reach + RadiusMarginMeters).coerceAtMost(Track.MaxRadiusMeters),
                startFinish = start,
                sectorLines = splits,
            )
        }

        /** The gate as a line through its middle, square to the heading of the nearest moving fix. */
        private fun across(gate: TrackLine): TrackLine {
            val mid = midpoint(gate)
            val nearest = fixes.filter { it[3] > MovingKmh }.minBy { metres(mid, GeoPoint(it[0], it[1])) }
            val width = metres(gate.left, gate.right).coerceIn(MinWidth, MaxWidth)
            return TrackLine.across(mid, nearest[2], width)
        }

        companion object {
            private const val MovingKmh = 20.0
            private const val MinWidth = 20.0
            private const val MaxWidth = 40.0
            private const val RadiusMarginMeters = 500.0
            private const val EarthRadiusMetres = 6_371_008.8

            fun read(file: File): Vbo {
                var section = ""
                var name = file.nameWithoutExtension
                var columns: List<String> = emptyList()
                val gates = ArrayList<Pair<String, TrackLine>>()
                val fixes = ArrayList<DoubleArray>()
                file.forEachLine(Charsets.UTF_8) { raw ->
                    val line = raw.trim()
                    when {
                        line.isEmpty() -> Unit
                        line.startsWith("[") && line.endsWith("]") -> section = line.lowercase()
                        section == "[session data]" && line.startsWith("name ") ->
                            name = line.removePrefix("name ").trim()
                        section == "[laptiming]" -> gate(line)?.let { gates += it }
                        section == "[column names]" -> if (columns.isEmpty()) columns = line.split(Regex("\\s+"))
                        section == "[data]" -> {
                            val f = line.split(Regex("\\s+"))
                            // NaN for a column the VBO lacks (heading is then derived below).
                            val v = { c: String -> f.getOrNull(columns.indexOf(c))?.toDouble() ?: Double.NaN }
                            fixes += doubleArrayOf(v("lat") / 60, -v("long") / 60, v("heading"), v("velocity"))
                        }
                    }
                }
                return Vbo(name, gates, withHeadings(fixes))
            }

            /** A heading the VBO lacks taken from the neighbouring positions, as SessionImport derives it. */
            private fun withHeadings(fixes: List<DoubleArray>): List<DoubleArray> = fixes.mapIndexed { i, p ->
                if (!p[2].isNaN()) return@mapIndexed p
                val a = fixes[maxOf(i - 1, 0)]
                val b = fixes[minOf(i + 1, fixes.lastIndex)]
                val d = LocalFrame(GeoPoint(a[0], a[1])).toXy(b[0], b[1])
                p.copyOf().also { it[2] = (Math.toDegrees(atan2(d.x, d.y)) + 360) % 360 }
            }

            /** "Start  lon1 lat1 lon2 lat2 ¬ name": minutes, longitude positive west. */
            private fun gate(line: String): Pair<String, TrackLine>? {
                val f = line.substringBefore('¬').trim().split(Regex("\\s+"))
                if (f.size < 5) return null
                val n = f.drop(1).take(4).map { it.toDouble() / 60 }
                val label = if (f[0] == "Start") "Start" else line.substringAfter('¬').trim()
                return label to TrackLine(GeoPoint(n[1], -n[0]), GeoPoint(n[3], -n[2]))
            }

            private fun midpoint(l: TrackLine) =
                GeoPoint((l.left.lat + l.right.lat) / 2, (l.left.lon + l.right.lon) / 2)

            private fun metres(a: GeoPoint, b: GeoPoint): Double {
                val y = Math.toRadians(b.lat - a.lat)
                val x = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
                return EarthRadiusMetres * sqrt(x * x + y * y)
            }
        }
    }
}
