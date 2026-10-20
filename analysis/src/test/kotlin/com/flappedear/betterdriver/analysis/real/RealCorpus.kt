@file:Suppress("MagicNumber", "ReturnCount") // Positions, sizes and times of the corpus files; early outs.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackKind
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.transfer.foreign.ImportedSession
import com.flappedear.betterdriver.transfer.foreign.SessionImport
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream

/**
 * Arek's real recordings (src/test/resources/real, see its README.md), read the way the app reads
 * them: through the session import (FBBD-164) into an ordinary session.
 */
object RealCorpus {
    /** RaceChrono Pro 10.2.4 at Silesia Ring on 27 Sep 2026: 10 Hz GNSS with OBD, 23 min, with RaceChrono's lines. */
    const val SILESIA = "silesia-ring-2026-09-27-racechrono.vbo.gz"

    /** The same day's second session, from 13:53 local: 29 min at 10 Hz with OBD, the same lines (FBBD-238). */
    const val SILESIA_1353 = "silesia-ring-2026-09-27-1353-racechrono.vbo.gz"

    /** The same day's third session, from 14:54 local: 29 min at 10 Hz with OBD, the same lines (FBBD-238). */
    const val SILESIA_1454 = "silesia-ring-2026-09-27-1454-racechrono.vbo.gz"

    /** Arek's three Silesia Ring sessions of 27 Sep 2026, in the order he drove them. */
    val SILESIA_DAY = listOf(SILESIA, SILESIA_1353, SILESIA_1454)

    /**
     * Arek's six RaceChrono Pro 10.2.4 sessions at Autodrom Jastrząb on 29 Aug 2026, in the order he
     * drove them (local start times): 10 Hz GNSS with OBD, 15 to 26 min each, RaceChrono's start line
     * and no splits (FBBD-254).
     */
    val JASTRZAB_DAY = listOf("1000", "1129", "1303", "1437", "1606", "1720")
        .map { "jastrzab-2026-08-29-$it-racechrono.vbo.gz" }

    /**
     * BetterDriver 0.3.1 VBO export, 12 Oct 2026 10:36 UTC: a road drive at 25 Hz with phone video,
     * 27 min of it once 1.5 km at each end were trimmed off.
     */
    const val COMMUTE_1036 = "commute-2026-10-12-1036.vbo.gz"

    /** BetterDriver VBO export, 12 Oct 2026 04:22 UTC: the same road the other way at 25 Hz, 12 min trimmed. */
    const val COMMUTE_0422 = "commute-2026-10-12-0422.vbo.gz"

    val BETTERDRIVER = listOf(COMMUTE_1036, COMMUTE_0422)

    /** The day for a file without a date line; every corpus file has one. */
    val Today: LocalDate = LocalDate.of(2026, 10, 12)
    val ALL = listOf(SILESIA) + BETTERDRIVER

    /** The file as stored, gzip-compressed (the import unwraps gzip itself). */
    fun open(name: String): InputStream =
        checkNotNull(RealCorpus::class.java.getResourceAsStream("/real/$name")) { "missing corpus file $name" }

    /** The file's text, decompressed. */
    fun text(name: String): String = GZIPInputStream(open(name)).use { it.readBytes().decodeToString() }

    /** Data rows of the VBO [name]: every line after its [data] header. */
    fun dataRows(name: String): Int =
        text(name).lineSequence().dropWhile { it.trim() != "[data]" }.drop(1).count { it.isNotBlank() }

    /** Imports [name] into [store] as the app's Import session does, against the saved [tracks]. */
    fun import(store: SessionStore, staging: File, name: String, tracks: List<Track> = emptyList()): ImportedSession =
        open(name).use {
            SessionImport(store, staging).import(it, name, tracks, CircuitList.Empty, today = Today)
        }

    /** A RaceChrono [laptiming] line: the type, the name after "¬", and its two points in degrees. */
    class RaceChronoLine(val type: String, val name: String, val first: GeoPoint, val second: GeoPoint)

    /**
     * The [laptiming] section of the VBO [name]. Coordinates are arc minutes, longitude positive west,
     * as in the data; each line is "<type> <long1> <lat1> <long2> <lat2> ¬ <name>".
     */
    fun raceChronoLines(name: String): List<RaceChronoLine> = text(name).lineSequence()
        .map { it.trim() }
        .dropWhile { it != "[laptiming]" }.drop(1)
        .takeWhile { it.isNotEmpty() && !it.startsWith("[") }
        .map { line ->
            val (fields, label) = line.split("¬").let { it[0].trim().split(Regex("\\s+")) to it[1].trim() }
            val v = fields.drop(1).map { it.toDouble() }
            RaceChronoLine(fields[0], label, GeoPoint(v[1] / 60.0, -v[0] / 60.0), GeoPoint(v[3] / 60.0, -v[2] / 60.0))
        }
        .toList()

    /**
     * RaceChrono 10.2.4's line as a BetterDriver [TrackLine]: RaceChrono stores a gate as its centre
     * and a second point along the track, the gate square to the two and as wide as they are apart
     * (as FlappedEar Telemetry's vbo_parser reads it, VboFixture.gate). In this file the car drives
     * from the second point towards the centre: timed the other way round, no lap is found.
     * BetterDriver's line runs from the driver's left to right.
     */
    fun trackLine(line: RaceChronoLine): TrackLine {
        val frame = LocalFrame(line.first)
        val forward = frame.toXy(line.second) * -1.0
        val left = Xy(-forward.y, forward.x) * 0.5
        return TrackLine(frame.toGeo(left), frame.toGeo(left * -1.0))
    }

    /**
     * Silesia Ring as Arek would save it from this file: RaceChrono's own start/finish and split
     * lines (sector lines in split-number order, the order the lap crosses them), centred on the
     * start/finish line. [id] and [revision] let a test make it again or edit it.
     */
    fun silesiaTrack(id: String = "silesia-ring", revision: Int = 1): Track =
        raceChronoTrack(SILESIA, id, "Silesia Ring", revision)

    /** Autodrom Jastrząb as Arek would save it from his 29 Aug 2026 files: RaceChrono's start/finish line. */
    fun jastrzabTrack(id: String = "jastrzab", revision: Int = 1): Track =
        raceChronoTrack(JASTRZAB_DAY.first(), id, "Autodrom Jastrząb", revision)

    /** The Polish circuit of the VBO [file], from its RaceChrono lines, as [silesiaTrack] says. */
    private fun raceChronoTrack(file: String, id: String, name: String, revision: Int): Track {
        val lines = raceChronoLines(file)
        val start = lines.single { it.type == "Start" }
        val splits = lines.filter { it.type == "Split" }.sortedBy { it.name.substringAfter("Split ").trim().toInt() }
        return Track(
            id = id,
            name = name,
            center = start.first,
            country = "PL",
            startFinish = trackLine(start),
            sectorLines = splits.map(::trackLine),
            revision = revision,
        )
    }

    /** One fix of a VBO, in degrees east and north, with its speed. */
    class VboFix(val lat: Double, val lon: Double, val kmh: Double)

    /** The fixes of the VBO [name], in file order. */
    fun fixes(name: String): List<VboFix> {
        val lines = text(name).lines().map { it.trim() }
        val names = lines[lines.indexOf("[column names]") + 1].split(Regex("\\s+"))
        val lat = names.indexOf("lat")
        val lon = names.indexOf("long")
        val kmh = names.indexOf("velocity")
        return lines.drop(lines.indexOf("[data]") + 1).filter { it.isNotEmpty() }.map { row ->
            val v = row.split(Regex("\\s+"))
            VboFix(v[lat].toDouble() / 60.0, -v[lon].toDouble() / 60.0, v[kmh].toDouble())
        }
    }

    /**
     * A point-to-point track on one of the road drives, made from the drive itself as Arek would
     * place its lines on a map: the start line square to the road where the car first does 20 km/h
     * after a fifth of the drive, the finish where it does so after four fifths. Null for a drive
     * that never gets going.
     */
    fun roadTrack(name: String): Track? {
        val fixes = fixes(name)
        fun movingAfter(share: Double) = (fixes.indices.drop((fixes.size * share).toInt()))
            .firstOrNull { fixes[it].kmh >= 20.0 && it >= 10 && it + 10 < fixes.size }
        val start = movingAfter(0.2) ?: return null
        val finish = movingAfter(0.8) ?: return null
        fun point(i: Int) = GeoPoint(fixes[i].lat, fixes[i].lon)
        fun line(i: Int): TrackLine {
            val frame = LocalFrame(point(i))
            val d = frame.toXy(point(i + 10)) - frame.toXy(point(i - 10))
            return TrackLine.across(point(i), Math.toDegrees(kotlin.math.atan2(d.x, d.y)))
        }
        return Track(
            id = "road-" + name.substringBefore(".vbo"),
            name = "Road",
            center = point(start),
            radiusMeters = Track.MaxRadiusMeters,
            startFinish = line(start),
            kind = TrackKind.PointToPoint,
            finish = line(finish),
        )
    }
}
